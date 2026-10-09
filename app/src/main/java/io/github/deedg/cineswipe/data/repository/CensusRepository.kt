package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CensusCandidateRow
import io.github.deedg.cineswipe.data.db.CensusUndo
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.domain.census.CensusCandidate
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusFeatures
import io.github.deedg.cineswipe.domain.census.CensusFilter
import io.github.deedg.cineswipe.domain.census.CensusModel
import io.github.deedg.cineswipe.domain.census.CensusPlanner
import io.github.deedg.cineswipe.domain.census.CensusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Film d'une page du recensement, avec ce qui a servi à le choisir. */
data class CensusItem(
    val movie: MovieEntity,
    val features: CensusFeatures,
    /** Chance que tu l'aies vu, selon le modèle au moment de la page. */
    val probability: Double = 0.0,
)

/** Avancement : films passés en revue, films vus, et films notables au total (TMDB). */
data class CensusProgress(
    val answered: Int,
    val watched: Int,
    val universe: Int?,
)

/** De quoi annuler une page : les lignes d'avant et le modèle d'avant. */
class CensusCommit internal constructor(
    internal val undo: CensusUndo,
    internal val previousModel: CensusModel,
    val items: List<CensusItem>,
    val answers: Map<Long, CensusChoice>,
)

/**
 * Recensement des films vus : parcourir le catalogue (les films les plus vus, les films
 * français, une décennie, les sagas et les voisins des films vus) en commençant par ceux que tu
 * as le plus de chances d'avoir vus. Une réponse « pas vu » est neutre : le film sort du
 * recensement, rien ne change pour les recos.
 */
class CensusRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val recos: RecoRepository,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.censusDao()
    private val planner = CensusPlanner(random)
    private val poolMutex = Mutex()
    private val rerankMutex = Mutex()

    @Volatile
    private var rerankPending = false

    /** Sagas et voisins des films tout juste vus, en cours d'ajout au catalogue. */
    private val expansions = CopyOnWriteArrayList<Job>()

    fun observeProgress(): Flow<CensusProgress> = combine(
        dao.observeAnswered(),
        dao.observeWatched(),
        settings.censusState.map { it.universe }.distinctUntilChanged(),
    ) { answered, watched, universe -> CensusProgress(answered, watched, universe) }

    /**
     * Prochaine page : choisit les films avec le modèle « déjà vu ». Quand il reste trop peu de
     * films prometteurs (pas seulement trop peu de films : des films peu connus s'accumulent sans
     * jamais être choisis), le catalogue est complété depuis TMDB. Sans réseau, la page se fait
     * avec ce qui est déjà en base.
     */
    suspend fun nextPage(filter: CensusFilter, size: Int): List<CensusItem> = withContext(Dispatchers.Default) { computePage(filter, size) }

    private suspend fun computePage(filter: CensusFilter, size: Int): List<CensusItem> {
        val model = model()
        var candidates = candidates(filter)
        var failure: TmdbException? = null
        val threshold = promisingThreshold()
        if (candidates.count { model.probability(it.features) >= threshold } < size * POOL_PAGES) {
            failure = attempt { fillPool(filter) }
            candidates = candidates(filter)
        }
        if (candidates.isEmpty() && expansions.isNotEmpty()) {
            // Catalogue épuisé : les suites et voisins des films tout juste vus arrivent peut-être.
            withTimeoutOrNull(EXPANSION_WAIT_MS) { expansions.toList().joinAll() }
            candidates = candidates(filter)
        }
        if (candidates.isEmpty()) {
            failure?.let { throw it }
            return emptyList()
        }
        val ids = planner.page(candidates, model, size)
        val movies = dao.movies(ids).associateBy { it.tmdbId }
        val features = candidates.associate { it.tmdbId to it.features }
        return ids.mapNotNull { id ->
            movies[id]?.let { movie ->
                val itemFeatures = features.getValue(id)
                CensusItem(movie, itemFeatures, model.probability(itemFeatures))
            }
        }
    }

    private suspend fun candidates(filter: CensusFilter): List<CensusCandidate> {
        val links = dao.graphLinks().associate { it.movieId to it.links }
        return dao.candidates(filter.yearMin, filter.yearMax, filter.language)
            .map { CensusCandidate(it.tmdbId, features(it, links[it.tmdbId] ?: 0)) }
    }

    /**
     * Seuil d'un film « prometteur » : la moitié de ta part de films vus parmi ceux recensés
     * (25 % au départ), entre 5 et 20 %.
     */
    private suspend fun promisingThreshold(): Double {
        val counts = dao.answerCounts()
        val share = (counts.seen + PRIOR_SEEN) / (counts.answered + PRIOR_ANSWERED)
        return (share * PROMISING_SHARE).coerceIn(MIN_PROMISING, MAX_PROMISING)
    }

    /**
     * Enregistre les réponses d'une page (les films sans réponse comptent comme « pas vu ») et
     * fait apprendre le modèle. Les films vus enrichissent le catalogue (leurs sagas et leurs
     * voisins) et prennent leur place dans l'Arène.
     */
    suspend fun commit(items: List<CensusItem>, choices: Map<Long, CensusChoice>): CensusCommit {
        val answers = items.associate { it.movie.tmdbId to (choices[it.movie.tmdbId] ?: CensusChoice.NONE) }
        dao.insertMoviesIfAbsent(items.map { it.movie })
        val undo = dao.answer(answers, clock())
        val previous = model()
        val updated = previous.learn(items.map { it.features to answers.getValue(it.movie.tmdbId).seen })
        settings.updateCensusState { it.copy(weights = updated.weights, updates = updated.updates) }
        val seen = answers.filterValues { it.seen }.keys.toList()
        if (seen.isNotEmpty()) {
            requestRerank()
            val job = scope.launch { expand(seen) }
            expansions += job
            job.invokeOnCompletion { expansions.remove(job) }
        }
        return CensusCommit(undo, previous, items, answers)
    }

    /**
     * Enregistre des réponses en dehors de l'écran (qui se ferme) : seules les réponses données
     * comptent, les films sans réponse restent à recenser.
     */
    fun commitLater(items: List<CensusItem>, choices: Map<Long, CensusChoice>) {
        val decided = items.filter { (choices[it.movie.tmdbId] ?: CensusChoice.NONE) != CensusChoice.NONE }
        if (decided.isEmpty()) return
        scope.launch { commit(decided, choices) }
    }

    /** Annule une page : réponses et modèle reviennent à l'état d'avant. */
    suspend fun undo(commit: CensusCommit) {
        dao.restore(commit.undo)
        settings.updateCensusState { it.copy(weights = commit.previousModel.weights, updates = commit.previousModel.updates) }
        if (commit.answers.values.any { it.seen }) database.arenaDao().rerank()
    }

    /**
     * Ajoute au catalogue les autres films des sagas déjà commencées (« tu as vu Matrix : et
     * Matrix Reloaded ? »), quelques sagas à la fois. Lancé à l'ouverture du recensement.
     */
    suspend fun exploreWatchedSagas(limit: Int = SAGAS_PER_VISIT) {
        // Les fiches chargées par une version plus ancienne ne connaissent pas encore leur saga.
        recos.enrichTaste(DETAILS_PER_VISIT)
        val known = settings.censusState.first().sagas
        dao.watchedCollections().filter { it !in known }.take(limit).forEach { collection ->
            if (attempt { addSaga(collection) } is TmdbException.MissingKey) return
        }
    }

    /**
     * Recalcule le classement de l'Arène pour les films tout juste vus. Les demandes rapprochées
     * (cartes enchaînées) se regroupent : au plus un calcul en cours et un en attente.
     */
    private fun requestRerank() {
        rerankPending = true
        scope.launch {
            rerankMutex.withLock {
                if (!rerankPending) return@withLock
                rerankPending = false
                database.arenaDao().rerank()
            }
        }
    }

    private suspend fun model(): CensusModel {
        val state = settings.censusState.first()
        return if (state.weights.isEmpty()) CensusModel() else CensusModel(state.weights, state.updates)
    }

    /** Films vus tout juste : leur saga et leurs recommandations rejoignent le catalogue. */
    private suspend fun expand(seen: List<Long>) {
        seen.take(EXPANDED_PER_PAGE).forEach { id ->
            val movie = try {
                tmdb.ensureDetails(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TmdbException) {
                return
            }
            movie.collectionId?.let { collection ->
                if (collection !in settings.censusState.first().sagas) attempt { addSaga(collection) }
            }
            attempt {
                val linked = recos.explore(id)
                addSummaries(linked, CensusSource.GRAPH, boost = false)
            }
        }
    }

    private suspend fun addSaga(collection: Long) {
        val parts = tmdb.collection(collection).parts
        addSummaries(parts, CensusSource.SAGA, boost = true)
        settings.updateCensusState { it.copy(sagas = it.sagas + collection) }
    }

    /**
     * Complète le catalogue des listes TMDB qui servent ce filtre. Sans filtre : surtout les films
     * les plus vus, une page de films français, et une page d'une décennie (celle la moins
     * explorée), pour faire remonter les classiques moins votés.
     */
    private suspend fun fillPool(filter: CensusFilter) = poolMutex.withLock {
        val plan: List<Pair<Segment, Int>> = when (filter) {
            CensusFilter.ALL -> listOf(Segment.POPULAR to 3, Segment.FRENCH to 1, leastExploredDecade() to 1)
            CensusFilter.FRENCH -> listOf(Segment.FRENCH to 5)
            else -> listOf(Segment.decade(filter) to 5)
        }
        coroutineScope {
            plan.flatMap { (segment, pages) -> nextPages(segment, pages).map { page -> segment to page } }
                .map { (segment, page) -> async { segment to (page to tmdb.discover(segment.query, page)) } }
                .awaitAll()
        }.forEach { (segment, result) ->
            val (page, response) = result
            addSummaries(response.results, segment.source, boost = false)
            settings.updateCensusState { state ->
                val cursor = state.cursors[segment.key] ?: CensusCursor()
                state.copy(
                    cursors = state.cursors + (segment.key to cursor.copy(nextPage = maxOf(cursor.nextPage, page + 1), totalPages = response.totalPages)),
                    universe = if (segment == Segment.POPULAR) response.totalResults else state.universe,
                )
            }
        }
    }

    /** La décennie dont la liste a été la moins lue (parmi celles qui ne sont pas épuisées). */
    private suspend fun leastExploredDecade(): Segment {
        val cursors = settings.censusState.first().cursors
        val decades = CensusFilter.entries.filter { it.yearMin != null || it.yearMax != null }.map(Segment::decade)
        val open = decades.filterNot { cursors[it.key]?.exhausted == true }.ifEmpty { decades }
        return open.minBy { cursors[it.key]?.nextPage ?: 1 }
    }

    /** Les prochaines pages à lire d'une liste (aucune si elle est épuisée). */
    private suspend fun nextPages(segment: Segment, count: Int): List<Int> {
        val cursor = settings.censusState.first().cursors[segment.key] ?: CensusCursor()
        if (cursor.exhausted) return emptyList()
        val last = minOf(cursor.totalPages ?: Int.MAX_VALUE, CensusCursor.MAX_TMDB_PAGE)
        return (cursor.nextPage until cursor.nextPage + count).filter { it <= last }
    }

    private suspend fun addSummaries(results: List<TmdbMovieSummaryDto>, source: CensusSource, boost: Boolean) {
        val usable = results.filter { RecoRepository.isShowable(it) }
        if (usable.isEmpty()) return
        val language = settings.language.first()
        val genreNames = try {
            tmdb.genres().associate { it.id to it.name }
        } catch (error: TmdbException) {
            emptyMap()
        }
        dao.addToPool(usable.map { TmdbMapper.summaryToEntity(it, genreNames, language) }, source, boost, clock())
    }

    private suspend fun attempt(block: suspend () -> Unit): TmdbException? = try {
        block()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: TmdbException) {
        error
    }

    /** Liste TMDB qui alimente le catalogue. */
    private data class Segment(val key: String, val source: CensusSource, val query: DiscoverQuery) {
        companion object {
            /** Les films les plus vus : ceux qui ont le plus de votes. */
            val POPULAR = Segment("popular", CensusSource.POPULAR, DiscoverQuery(minVotes = UNIVERSE_MIN_VOTES, sortBy = "vote_count.desc"))

            /** Les films français les plus vus (moins votés sur TMDB, d'où un seuil plus bas). */
            val FRENCH = Segment("french", CensusSource.FRENCH, DiscoverQuery(originalLanguage = "fr", minVotes = 30, sortBy = "vote_count.desc"))

            fun decade(filter: CensusFilter) = Segment(
                key = "period:${filter.name}",
                source = CensusSource.DECADE,
                query = DiscoverQuery(yearMin = filter.yearMin, yearMax = filter.yearMax, minVotes = 50, sortBy = "vote_count.desc"),
            )
        }
    }

    companion object {
        /** Taille d'une page de la grille (3 × 3, sans défilement). */
        const val PAGE_SIZE = 9

        /** Le catalogue est complété quand il reste moins de trois pages de films prometteurs. */
        private const val POOL_PAGES = 3
        private const val PROMISING_SHARE = 0.5
        private const val MIN_PROMISING = 0.05
        private const val MAX_PROMISING = 0.2
        private const val PRIOR_SEEN = 2.0
        private const val PRIOR_ANSWERED = 8.0

        /** Films notables : au moins 200 votes sur TMDB. */
        const val UNIVERSE_MIN_VOTES = 200
        private const val EXPANDED_PER_PAGE = 4
        private const val SAGAS_PER_VISIT = 12

        /** Fiches de films vus mises à jour à chaque ouverture (pour découvrir leur saga). */
        private const val DETAILS_PER_VISIT = 40

        /** Attente maximale des sagas et voisins quand le catalogue est épuisé. */
        private const val EXPANSION_WAIT_MS = 10_000L

        fun features(row: CensusCandidateRow, graphLinks: Int) = CensusFeatures(
            voteCount = row.voteCount,
            voteAverage = row.tmdbRating,
            year = row.year,
            genreIds = row.genreIds,
            language = row.originalLanguage,
            graphLinks = graphLinks,
            sagaSeen = row.boost,
        )
    }
}
