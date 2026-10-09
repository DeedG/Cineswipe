package io.github.deedg.cineswipe.data.repository

import android.content.Context
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.DuelEntity
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.RecoSourceEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.census.CensusModel
import io.github.deedg.cineswipe.domain.reco.RecoRanker
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.domain.reco.TasteFamily
import io.github.deedg.cineswipe.domain.reco.TasteFilm
import io.github.deedg.cineswipe.domain.reco.TasteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Film du classement, pour les diagnostics. */
data class FilmDiagnostic(
    val tmdbId: Long,
    val title: String,
    val rating: Double,
    val deviation: Double,
    val duels: Int,
    val confidence: Double,
)

data class RankingDiagnostics(
    val films: Int,
    val loggedDuels: Int,
    val inheritedDuels: Int,
    val draws: Int,
    val reliability: Double,
    /** Part des duels gagnés par le film aujourd'hui le mieux classé des deux (null sans duel). */
    val consistency: Double?,
    /** Films par confiance : moins de 50 %, 50 à 70 %, 70 à 90 %, 90 % et plus. */
    val confidenceBuckets: List<Int>,
    val medianDeviation: Double,
    /** Estimation des duels qui restent pour une fiabilité d'environ 90 % (8 duels par film). */
    val duelsTo90: Int,
    val uncertain: List<FilmDiagnostic>,
)

data class SourceDiagnostic(
    val source: RecoSource,
    val shown: Double,
    val liked: Double,
    val disliked: Double,
    /** Chance estimée qu'une carte de cette source plaise (moyenne de sa loi bêta). */
    val rate: Double,
)

data class RecoDiagnostics(
    val seeds: Int,
    val links: Int,
    val cachedMovies: Int,
    val tasteToEnrich: Int,
    val sources: List<SourceDiagnostic>,
    val topGenres: List<Pair<String, Double>>,
    val topDirectors: List<Pair<String, Double>>,
    val topActors: List<Pair<String, Double>>,
    val topThemes: List<Pair<String, Double>>,
)

data class CensusDiagnostics(
    val pool: Int,
    val answered: Int,
    val universe: Int?,
    val sagas: Int,
    val modelUpdates: Int,
    val topWeights: List<Pair<String, Double>>,
)

data class Diagnostics(
    val ranking: RankingDiagnostics,
    val recos: RecoDiagnostics,
    val census: CensusDiagnostics,
)

/**
 * Outils de diagnostic : état du classement, des recos et du recensement, et export de toutes
 * les données en JSON (sauvegarde, ou pour les envoyer et les analyser).
 */
class DiagnosticsRepository(
    private val context: Context,
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val versionName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    suspend fun load(): Diagnostics = withContext(Dispatchers.Default) {
        Diagnostics(ranking(), recos(), census())
    }

    private suspend fun ranking(): RankingDiagnostics {
        val dao = database.diagnosticsDao()
        val ranked = dao.rankedFilms()
        val decisive = dao.decisiveDuels()
        val confidence = ranked.map { it.confidence }
        val deviations = ranked.map { it.deviation }.sorted()
        return RankingDiagnostics(
            films = ranked.size,
            loggedDuels = dao.duelCount(),
            inheritedDuels = dao.inheritedDuels() / 2,
            draws = dao.drawCount(),
            reliability = if (ranked.isEmpty()) 0.0 else confidence.average(),
            consistency = if (decisive == 0) null else dao.consistentDuels().toDouble() / decisive,
            confidenceBuckets = listOf(
                confidence.count { it < 0.5 },
                confidence.count { it >= 0.5 && it < StarScale.PROVISIONAL_CONFIDENCE },
                confidence.count { it >= StarScale.PROVISIONAL_CONFIDENCE && it < 0.9 },
                confidence.count { it >= 0.9 },
            ),
            medianDeviation = if (deviations.isEmpty()) 0.0 else deviations[deviations.size / 2],
            duelsTo90 = ranked.sumOf { max(0, TARGET_DUELS - it.duels) } / 2,
            uncertain = ranked.sortedByDescending { it.deviation }.take(UNCERTAIN_COUNT),
        )
    }

    private suspend fun recos(): RecoDiagnostics {
        val dao = database.diagnosticsDao()
        val rows = database.recoDao().tasteRows()
        val weights = buildMap {
            putAll(TasteProfile.watchedWeights(rows.filter { it.status == WatchStatus.WATCHED }.associate { it.tmdbId to it.rating }))
            rows.forEach { row ->
                when (row.status) {
                    WatchStatus.WATCHLIST -> put(row.tmdbId, TasteProfile.WATCHLIST_WEIGHT)
                    WatchStatus.DISMISSED -> put(row.tmdbId, TasteProfile.DISMISSED_WEIGHT)
                    else -> Unit
                }
            }
        }
        val profile = TasteProfile(rows.map { TasteFilm(it.tmdbId, it.title, weights[it.tmdbId] ?: 0.0, RecoRepository.features(it)) })
        val genreNames = try {
            tmdb.genres().associate { it.id.toString() to it.name }
        } catch (error: TmdbException) {
            emptyMap()
        }
        fun top(family: TasteFamily, minFilms: Int) =
            profile.top(family, TOP_COUNT, minFilms).map { (genreNames[it.name].takeIf { family == TasteFamily.GENRE } ?: it.name) to it.value }
        val stats = database.recoDao().sources().associateBy { it.source }
        return RecoDiagnostics(
            seeds = dao.seedCount(),
            links = dao.linkCount(),
            cachedMovies = dao.movieCount(),
            tasteToEnrich = database.recoDao().tasteToEnrich(TmdbMapper.DETAILS_VERSION, Int.MAX_VALUE).size,
            sources = RecoSource.entries.map { source ->
                val entity = stats[source] ?: RecoSourceEntity(source = source)
                val (priorLiked, priorDisliked) = RecoRanker.prior(source, coldStart = false)
                val skipped = (entity.shown - entity.liked - entity.disliked).coerceAtLeast(0.0)
                val alpha = priorLiked + entity.liked
                val beta = priorDisliked + entity.disliked + RecoRanker.SKIP_FAILURE * skipped
                SourceDiagnostic(source, entity.shown, entity.liked, entity.disliked, alpha / (alpha + beta))
            },
            topGenres = top(TasteFamily.GENRE, 2),
            topDirectors = top(TasteFamily.DIRECTOR, 2),
            topActors = top(TasteFamily.CAST, 2),
            topThemes = top(TasteFamily.KEYWORD, 3),
        )
    }

    private suspend fun census(): CensusDiagnostics {
        val state = settings.censusState.first()
        val model = if (state.weights.isEmpty()) CensusModel() else CensusModel(state.weights, state.updates)
        return CensusDiagnostics(
            pool = database.censusDao().poolSize(),
            answered = database.censusDao().observeAnswered().first(),
            universe = state.universe,
            sagas = state.sagas.size,
            modelUpdates = model.updates,
            topWeights = model.weights.entries.sortedByDescending { abs(it.value) }.take(TOP_WEIGHTS).map { it.key to it.value },
        )
    }

    /**
     * Écrit toutes les données dans un fichier JSON (films et réponses, duels, bilan des recos, et
     * le cache des fiches utiles) et le renvoie, prêt à partager.
     */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val file = exportData()
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date(clock()))
        File(directory, "cineswipe-$stamp.json").apply { writeText(json.encodeToString(ExportFile.serializer(), file)) }
    }

    /** Toutes les données, prêtes à écrire (export partagé ou sauvegarde automatique). */
    suspend fun exportData(): ExportFile = withContext(Dispatchers.IO) {
        val dao = database.diagnosticsDao()
        val users = dao.userMovies()
        val movies = dao.exportedMovies(MIN_EXPORT_VOTES).associateBy { it.tmdbId }
        ExportFile(
            version = versionName,
            exportedAt = clock(),
            films = users.map { it.toExport(movies[it.tmdbId]) },
            duels = dao.duels().map { it.toExport() },
            recoSources = database.recoDao().sources().map { ExportSource(it.source.name, it.shown, it.liked, it.disliked) },
            catalog = movies.values.map { it.toExport() },
            lists = exportLists(),
        )
    }

    private suspend fun exportLists(): List<ExportList> {
        val dao = database.listDao()
        val items = dao.items().groupBy { it.listId }
        val movies = database.movieDao()
        return dao.lists().map { list ->
            ExportList(
                name = list.name,
                createdAt = list.createdAt,
                films = items[list.id].orEmpty().map { item ->
                    val movie = movies.get(item.tmdbId)
                    ExportListFilm(item.tmdbId, movie?.title, movie?.year, item.addedAt)
                },
            )
        }
    }

    /** L'export en JSON. */
    fun encode(file: ExportFile): String = json.encodeToString(ExportFile.serializer(), file)

    /** Résumé en texte brut, à coller dans un message. */
    fun summary(diagnostics: Diagnostics): String = buildString {
        val (ranking, recos, census) = diagnostics
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(clock()))
        appendLine("CineSwipe $versionName · diagnostic du $date")
        appendLine()
        appendLine("ARÈNE")
        appendLine("Films classés : ${ranking.films} · duels : ${ranking.loggedDuels} (+${ranking.inheritedDuels} hérités) · nuls : ${ranking.draws}")
        appendLine(
            "Fiabilité : ${percent(ranking.reliability)} · cohérence : ${ranking.consistency?.let(::percent) ?: "–"}" +
                " · écart médian : ${ranking.medianDeviation.toInt()}",
        )
        val (low, medium, high, top) = ranking.confidenceBuckets
        appendLine("Confiance : <50 % $low · 50-70 % $medium · 70-90 % $high · ≥90 % $top")
        appendLine("Pour viser 90 % : encore ~${ranking.duelsTo90} duels")
        appendLine(
            "Plus incertains : " + ranking.uncertain.joinToString(" ; ") {
                "${it.title} (${it.rating.toInt()} ± ${it.deviation.toInt()}, ${it.duels} duels)"
            },
        )
        appendLine()
        appendLine("RECOS")
        appendLine("Graines : ${recos.seeds} · liens : ${recos.links} · fiches : ${recos.cachedMovies} · à enrichir : ${recos.tasteToEnrich}")
        appendLine(
            "Sources : " + recos.sources.joinToString(" ; ") {
                "${it.source.name} ${it.shown.toInt()} vues, ${decimal(it.liked)} ♥, ${decimal(it.disliked)} ✕ → ${percent(it.rate)}"
            },
        )
        appendLine("Genres : ${affinities(recos.topGenres)}")
        appendLine("Réalisateurs : ${affinities(recos.topDirectors)}")
        appendLine("Acteurs : ${affinities(recos.topActors)}")
        appendLine("Thèmes : ${affinities(recos.topThemes)}")
        appendLine()
        appendLine("RECENSEMENT")
        appendLine(
            "Catalogue : ${census.pool} · passés en revue : ${census.answered} · films notables TMDB : ${census.universe ?: "–"}" +
                " · sagas : ${census.sagas} · apprentissages : ${census.modelUpdates}",
        )
        append("Poids : ").append(census.topWeights.joinToString(", ") { (key, value) -> "$key ${signed(value)}" })
    }

    companion object {
        /** Dossier du cache partagé par le FileProvider (voir `res/xml/file_paths.xml`). */
        const val EXPORT_DIRECTORY = "exports"
        private const val TARGET_DUELS = 8
        private const val UNCERTAIN_COUNT = 10
        private const val TOP_COUNT = 6
        private const val TOP_WEIGHTS = 12

        /** Fiches du cache exportées : celles des films de l'utilisateur, et les films connus. */
        private const val MIN_EXPORT_VOTES = 300

        private fun percent(value: Double) = "${(value * 100).roundToInt()} %"

        private fun decimal(value: Double) = String.format(Locale.FRANCE, "%.1f", value)

        private fun signed(value: Double) = String.format(Locale.FRANCE, "%+.2f", value)

        private fun affinities(values: List<Pair<String, Double>>) =
            values.joinToString(", ") { (name, value) -> "$name ${signed(value)}" }.ifEmpty { "–" }
    }
}

@Serializable
data class ExportFile(
    val app: String = "CineSwipe",
    val version: String,
    val exportedAt: Long,
    val schema: Int = 6,
    val films: List<ExportFilm>,
    val duels: List<ExportDuel>,
    val recoSources: List<ExportSource>,
    val catalog: List<ExportMovie>,
    /** Listes perso (depuis le schéma 6). */
    val lists: List<ExportList> = emptyList(),
)

@Serializable
data class ExportList(
    val name: String,
    val createdAt: Long,
    val films: List<ExportListFilm>,
)

@Serializable
data class ExportListFilm(
    val tmdbId: Long,
    val title: String? = null,
    val year: Int? = null,
    val addedAt: Long,
)

@Serializable
data class ExportFilm(
    val tmdbId: Long,
    val title: String? = null,
    val year: Int? = null,
    val status: String,
    val rating: Double,
    val deviation: Double,
    val confidence: Double,
    val duels: Int,
    val priorRating: Double,
    val priorDuels: Int,
    val impression: String? = null,
    val watchedAt: Long? = null,
    val watchedSource: String? = null,
    val availability: String,
    val shownCount: Int,
    val lastShownAt: Long? = null,
    val censusAt: Long? = null,
    val updatedAt: Long,
    val importedRating: Double? = null,
)

@Serializable
data class ExportDuel(
    val id: Long,
    val winnerId: Long,
    val loserId: Long,
    val draw: Boolean,
    val createdAt: Long,
    val winnerRatingBefore: Double,
    val loserRatingBefore: Double,
    val winnerRatingAfter: Double,
    val loserRatingAfter: Double,
)

@Serializable
data class ExportSource(
    val source: String,
    val shown: Double,
    val liked: Double,
    val disliked: Double,
)

@Serializable
data class ExportMovie(
    val tmdbId: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val genres: List<String> = emptyList(),
    val tmdbRating: Double? = null,
    val voteCount: Int? = null,
    val runtimeMinutes: Int? = null,
    val originalLanguage: String? = null,
    val popularity: Double? = null,
    val trailerKey: String? = null,
    val collectionId: Long? = null,
)

private fun UserMovieEntity.toExport(movie: MovieEntity?) = ExportFilm(
    tmdbId = tmdbId,
    title = movie?.title,
    year = movie?.year,
    status = status.name,
    rating = rating,
    deviation = deviation,
    confidence = confidence,
    duels = duels,
    priorRating = priorRating,
    priorDuels = priorDuels,
    impression = impression?.name,
    watchedAt = watchedAt,
    watchedSource = watchedSource?.name,
    availability = availability.name,
    shownCount = shownCount,
    lastShownAt = lastShownAt,
    censusAt = censusAt,
    updatedAt = updatedAt,
    importedRating = importedRating,
)

private fun DuelEntity.toExport() = ExportDuel(
    id = id,
    winnerId = winnerId,
    loserId = loserId,
    draw = draw,
    createdAt = createdAt,
    winnerRatingBefore = winnerRatingBefore,
    loserRatingBefore = loserRatingBefore,
    winnerRatingAfter = winnerRatingAfter,
    loserRatingAfter = loserRatingAfter,
)

private fun MovieEntity.toExport() = ExportMovie(
    tmdbId = tmdbId,
    title = title,
    originalTitle = originalTitle,
    year = year,
    posterPath = posterPath,
    backdropPath = backdropPath,
    overview = overview,
    director = director,
    cast = cast.map { it.name },
    genreIds = genreIds,
    genres = genres,
    tmdbRating = tmdbRating,
    voteCount = voteCount,
    runtimeMinutes = runtimeMinutes,
    originalLanguage = originalLanguage,
    popularity = popularity,
    trailerKey = trailerKey,
    collectionId = collectionId,
)
