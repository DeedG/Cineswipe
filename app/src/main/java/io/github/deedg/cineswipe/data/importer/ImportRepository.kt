package io.github.deedg.cineswipe.data.importer

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Pourquoi un import a échoué. */
enum class ImportFailure { UNREADABLE, EMPTY, MISSING_KEY, INVALID_KEY, NETWORK, ERROR }

/** Bilan d'un import, affiché à la fin. */
data class ImportReport(
    val sources: Set<ImportSource>,
    /** Sauvegarde CineSwipe restaurée telle quelle (classement et duels compris). */
    val restored: Boolean,
    val restoredFilms: Int,
    val restoredDuels: Int,
    val counts: ImportCounts,
    /** Films introuvables sur TMDB (« Titre (année) »). */
    val notFound: List<String>,
    /** Films pas traités faute de réseau : relancer l'import les reprend (rien n'est dupliqué). */
    val skipped: List<String> = emptyList(),
    /** Listes perso reprises d'une sauvegarde CineSwipe. */
    val lists: Int = 0,
)

sealed interface ImportState {
    data object Idle : ImportState

    data object Reading : ImportState

    /**
     * Fichiers lus, en attente de confirmation. [restore] : sauvegarde CineSwipe et bibliothèque
     * vide, elle sera restaurée en entier. [needsTmdb] : des films n'ont ni identifiant TMDB ni
     * fiche en cache, il faut la clé pour les retrouver.
     */
    data class Ready(val batch: ImportBatch, val restore: Boolean, val needsTmdb: Boolean) : ImportState

    data class Running(val done: Int, val total: Int) : ImportState

    data class Done(val report: ImportReport) : ImportState

    data class Failed(val reason: ImportFailure) : ImportState
}

/**
 * Import de films depuis d'autres applis (Letterboxd, Trakt, IMDb, CSV) ou d'une sauvegarde
 * CineSwipe. Les films sont retrouvés sur TMDB (identifiant, IMDb, ou titre et année) puis
 * fusionnés sans rien effacer ; les notes reprises servent de point de départ à l'Arène.
 * L'import tourne dans la portée de l'appli : quitter l'écran ne l'interrompt pas.
 */
class ImportRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Après un import : compléter les fiches des films ajoutés (profil de goût des recos). */
    private val afterImport: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Derniers fichiers lus : « Réessayer » y revient après une coupure réseau. */
    private var lastReady: ImportState.Ready? = null

    /** Lit les fichiers choisis (archives ZIP comprises). */
    fun load(files: List<ImportFile>) {
        if (mutableState.value is ImportState.Running) return
        job?.cancel()
        mutableState.value = ImportState.Reading
        job = scope.launch {
            mutableState.value = try {
                val batch = withContext(Dispatchers.Default) { ImportFormats.parse(files) }
                if (batch.items.isEmpty() && batch.backup == null) {
                    ImportState.Failed(if (files.isEmpty() || batch.ignored.isNotEmpty()) ImportFailure.UNREADABLE else ImportFailure.EMPTY)
                } else {
                    val restore = batch.backup != null && !database.importDao().hasUserData()
                    ImportState.Ready(batch, restore = restore, needsTmdb = needsTmdb(batch)).also { lastReady = it }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ImportState.Failed(ImportFailure.UNREADABLE)
            }
        }
    }

    /** Fichier illisible côté interface (accès refusé, trop gros). */
    fun failReading() {
        if (mutableState.value !is ImportState.Running) mutableState.value = ImportState.Failed(ImportFailure.UNREADABLE)
    }

    /** Lance l'import des fichiers lus. */
    fun start() {
        val ready = mutableState.value as? ImportState.Ready ?: return
        mutableState.value = ImportState.Running(0, ready.batch.items.size)
        job = scope.launch {
            mutableState.value = try {
                ImportState.Done(run(ready)).also { scope.launch { afterImport() } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: TmdbException.MissingKey) {
                ImportState.Failed(ImportFailure.MISSING_KEY)
            } catch (error: TmdbException.InvalidKey) {
                ImportState.Failed(ImportFailure.INVALID_KEY)
            } catch (error: TmdbException) {
                ImportState.Failed(ImportFailure.NETWORK)
            } catch (error: Exception) {
                // Erreur inattendue (base, réponse illisible) : l'écran ne doit pas rester bloqué sur « en cours ».
                ImportState.Failed(ImportFailure.ERROR)
            }
        }
    }

    /** Retour à l'écran de départ (sauf pendant un import). */
    fun reset() {
        if (mutableState.value is ImportState.Running) return
        job?.cancel()
        lastReady = null
        mutableState.value = ImportState.Idle
    }

    /** Après un échec, retour aux fichiers lus (la bibliothèque a pu changer entre-temps). */
    fun retry() {
        if (mutableState.value !is ImportState.Failed) return
        val ready = lastReady ?: return reset()
        job = scope.launch {
            val restore = ready.batch.backup != null && !database.importDao().hasUserData()
            mutableState.value = ready.copy(restore = restore, needsTmdb = needsTmdb(ready.batch))
        }
    }

    private suspend fun needsTmdb(batch: ImportBatch): Boolean {
        if (settings.tmdbApiKey.first() != null) return false
        return batch.items.any { it.tmdbId == null }
    }

    private suspend fun run(ready: ImportState.Ready): ImportReport {
        val batch = ready.batch
        val now = clock()
        val dao = database.importDao()
        var restoredFilms = 0
        var restoredDuels = 0
        val backup = batch.backup
        val restored = backup != null && ready.restore && dao.restore(BackupRows.of(backup, now))
        if (restored && backup != null) {
            restoredFilms = backup.films.count { it.status == "WATCHED" }
            restoredDuels = backup.duels.size
        }
        val backupMovies = backup?.catalog?.associateBy { it.tmdbId }.orEmpty()
        val items = batch.items
        val total = items.size
        val done = AtomicInteger(0)
        mutableState.value = ImportState.Running(0, total)
        val genres = GenreNames(tmdb, settings.language.first())
        val permits = Semaphore(CONCURRENCY)
        // Un film injoignable faute de réseau (après les nouvelles tentatives) est mis de côté :
        // une coupure passagère ne fait pas perdre tout l'import.
        val offline = java.util.Collections.synchronizedList(ArrayList<ImportItem>())
        val resolved = coroutineScope {
            items.map { item ->
                async {
                    permits.withPermit {
                        val movie = try {
                            resolve(item, backupMovies.keys, genres)
                        } catch (error: TmdbException.Network) {
                            offline += item
                            null
                        }
                        mutableState.value = ImportState.Running(done.incrementAndGet(), total)
                        item to movie
                    }
                }
            }.awaitAll()
        }
        val found = resolved.filter { it.second != null }
        if (offline.isNotEmpty() && found.isEmpty() && !restored) throw TmdbException.Network(java.io.IOException("TMDB injoignable"))
        // Plusieurs lignes peuvent désigner le même film (watchlist Letterboxd et note IMDb…) : on les réunit.
        val entries = ImportMerger.combine(
            found.map { (item, movie) -> ImportEntry(movie!!, item.watched, item.watchlist, item.rating10, item.watchedAt) },
        )
        val counts = dao.applyImport(entries, now)
        // Listes perso de la sauvegarde : réunies par nom avec celles qui existent déjà.
        val lists = backup?.let { file ->
            val (imported, movies) = BackupRows.lists(file)
            if (imported.isEmpty()) 0 else database.listDao().mergeImported(imported, movies, now)
        } ?: 0
        database.arenaDao().rerank()
        fun label(item: ImportItem) =
            listOfNotNull(item.title ?: item.imdbId ?: item.tmdbId?.toString(), item.year?.let { "($it)" }).joinToString(" ")
        val offlineKeys = offline.map { it.key }.toHashSet()
        val notFound = resolved.filter { it.second == null && it.first.key !in offlineKeys }.map { label(it.first) }
        return ImportReport(
            sources = batch.sources,
            restored = restored,
            restoredFilms = restoredFilms,
            restoredDuels = restoredDuels,
            counts = counts,
            notFound = notFound,
            skipped = offline.map(::label),
            lists = lists,
        )
    }

    /** Fiche TMDB d'un film importé, ou null s'il est introuvable. */
    private suspend fun resolve(item: ImportItem, inBackup: Set<Long>, genres: GenreNames): MovieEntity? {
        val dao = database.importDao()
        item.tmdbId?.let { id ->
            dao.movie(id)?.let { return it }
            // Film d'une sauvegarde : sa fiche (partielle) vient avec, elle sera complétée plus tard.
            val partial = MovieEntity(tmdbId = id, title = item.title ?: "?", year = item.year)
            if (id in inBackup || settings.tmdbApiKey.first() == null) return partial
            return try {
                retrying { tmdb.ensureDetails(id) }
            } catch (error: TmdbException.Http) {
                if (error.code == NOT_FOUND) null else partial
            }
        }
        if (settings.tmdbApiKey.first() == null) throw TmdbException.MissingKey()
        item.imdbId?.let { imdb ->
            val found = try {
                retrying { tmdb.findByImdb(imdb) }.firstOrNull { !it.adult }
            } catch (error: TmdbException.Http) {
                null
            }
            if (found != null) return dao.movie(found.id) ?: genres.entity(found)
        }
        val title = item.title?.takeIf { it.isNotBlank() } ?: return null
        val match = search(title, item.year) ?: return null
        return dao.movie(match.id) ?: genres.entity(match)
    }

    private suspend fun search(title: String, year: Int?): TmdbMovieSummaryDto? {
        suspend fun page(withYear: Int?) = try {
            retrying { tmdb.search(title, 1, withYear) }.results
        } catch (error: TmdbException.Http) {
            emptyList()
        }
        ImportMatcher.best(title, year, page(year))?.let { return it }
        // Année décalée (sortie en festival, date d'un autre pays) : recherche sans l'année.
        return if (year != null) ImportMatcher.best(title, year, page(null)) else null
    }

    /** Réessaie après une coupure réseau ou un « trop de requêtes » de TMDB. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: TmdbException) {
                val retryable = error is TmdbException.Network || (error is TmdbException.Http && error.code == TOO_MANY_REQUESTS)
                if (!retryable || attempt >= RETRIES) throw error
            }
            attempt++
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** Noms des genres, chargés une fois pour les fiches tirées des résultats de recherche. */
    private class GenreNames(private val tmdb: TmdbRepository, private val language: String) {
        private var names: Map<Int, String>? = null

        suspend fun entity(dto: TmdbMovieSummaryDto): MovieEntity {
            val genres = names ?: runCatching { tmdb.genres().associate { it.id to it.name } }.getOrDefault(emptyMap()).also { names = it }
            return TmdbMapper.summaryToEntity(dto, genres, language)
        }
    }

    private companion object {
        /** Recherches en parallèle (TMDB tolère une quarantaine de requêtes par seconde). */
        const val CONCURRENCY = 6
        const val RETRIES = 3
        const val RETRY_DELAY_MS = 1_500L
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
    }
}
