package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.RequestTicket
import io.github.deedg.cineswipe.data.model.CardStatusRow
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPageDto
import io.github.deedg.cineswipe.domain.reco.RecoReason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Ce qu'un feed de cartes affiche. */
sealed interface FeedRequest {
    /** Recos personnalisées (voir [RecoRepository]). */
    data object Recos : FeedRequest

    /** ⚡ Éclair : recos TMDB à partir d'un film. */
    data class Lightning(val seedId: Long) : FeedRequest

    data class Search(val query: String) : FeedRequest

    data class Discover(val filters: DiscoverFilters) : FeedRequest
}

/** Durée des films de la Découverte (les mêmes paliers que « Ce soir », plus les films longs). */
enum class DiscoverLength(
    val minMinutes: Int?,
    val maxMinutes: Int?,
    /** Séries : durée d'un épisode. */
    val seriesMinMinutes: Int?,
    val seriesMaxMinutes: Int?,
) {
    ANY(null, null, null, null),
    SHORT(null, 100, null, 30),
    MEDIUM(null, 130, null, 50),
    LONG(131, null, 51, null),
    ;

    fun min(series: Boolean): Int? = if (series) seriesMinMinutes else minMinutes

    fun max(series: Boolean): Int? = if (series) seriesMaxMinutes else maxMinutes

    /** Vrai si la durée convient (une durée inconnue ne convient qu'à « toutes durées »). */
    fun accepts(runtime: Int?, series: Boolean = false): Boolean {
        if (this == ANY) return true
        val minutes = runtime?.takeIf { it > 0 } ?: return false
        val min = min(series)
        val max = max(series)
        return (min == null || minutes >= min) && (max == null || minutes <= max)
    }
}

/** Filtres de l'écran Découverte. */
data class DiscoverFilters(
    val genreId: Int? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val minRating: Float = 0f,
    /** Seulement les films disponibles sur le serveur du homelab (pris dans la base, pas sur TMDB). */
    val onlyAvailable: Boolean = false,
    val length: DiscoverLength = DiscoverLength.ANY,
) {
    fun toQuery(series: Boolean = false): DiscoverQuery = DiscoverQuery(
        genreId = genreId,
        runtimeMin = length.min(series),
        runtimeMax = length.max(series),
        minRating = minRating.takeIf { it > 0f }?.toDouble(),
        minVotes = if (minRating > 0f) 100 else null,
        yearMin = yearMin,
        yearMax = yearMax,
    )
}

/** Position dans un feed : page suivante et films déjà repérés mais pas encore affichés. */
class FeedCursor internal constructor(
    internal val page: Int,
    internal val pending: List<TmdbMovieSummaryDto>,
    internal val exhausted: Boolean,
    /** Recos : candidats des listes TMDB gardés pour le lot suivant. */
    internal val reco: RecoCarry? = null,
) {
    companion object {
        val START = FeedCursor(page = 1, pending = emptyList(), exhausted = false)
    }
}

data class FeedBatch(
    val movies: List<MovieEntity>,
    val next: FeedCursor,
    val endReached: Boolean,
    /** Recos : pourquoi chaque film est proposé. */
    val reasons: Map<Long, RecoReason> = emptyMap(),
)

/** Actions possibles sur une carte. [REQUEST] = À voir + demande au homelab (Seerr ou Radarr). */
enum class CardAction { WATCHED, UNWATCH, WATCHLIST, REQUEST, DISMISS }

/** De quoi annuler une action à l'identique. */
data class ActionRecord(
    val movie: MovieEntity,
    val action: CardAction,
    val previous: UserMovieEntity?,
    /** Demande au homelab, annulée avec l'action. */
    val request: RequestTicket? = null,
    /** Série : rien n'est encore demandé, l'utilisateur choisit les saisons à demander à ce service. */
    val askSeasons: HomelabService? = null,
)

/** Moteur des feeds de cartes : recos, Éclair, recherche et découverte. */
class FeedRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val homelab: HomelabRepository,
    private val recos: RecoRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Un film entre dans les vus ou en sort : le classement est à recalculer. */
    private val onWatchedChanged: () -> Unit = {},
    /** Monde des séries : les durées sont celles d'un épisode. */
    val series: Boolean = false,
) {

    /** Statut à jour de chaque film qui en a un, pour les badges des cartes. */
    fun observeCardStatuses(): Flow<Map<Long, CardStatusRow>> =
        database.userMovieDao().observeCardStatuses().map { rows -> rows.associateBy { it.tmdbId } }

    /**
     * Prochain lot de cartes enrichies. [sessionIds] = films déjà passés dans ce feed, jamais
     * reproposés. Un lot vide signifie qu'il n'y a plus rien à montrer.
     */
    suspend fun load(request: FeedRequest, cursor: FeedCursor, sessionIds: Set<Long>): FeedBatch {
        if (request is FeedRequest.Recos) {
            val batch = recos.nextBatch(sessionIds, cursor.reco)
            return FeedBatch(
                movies = batch.cards.map { it.movie },
                next = FeedCursor(page = cursor.page + 1, pending = emptyList(), exhausted = false, reco = batch.carry),
                endReached = batch.cards.isEmpty(),
                reasons = batch.cards.associate { it.movie.tmdbId to it.reason },
            )
        }
        if (request is FeedRequest.Discover && request.filters.onlyAvailable && settings.homelabConnected.first()) {
            return loadAvailable(request.filters, cursor, sessionIds)
        }
        val excluded = HashSet<Long>(sessionIds).apply { addAll(excludedIds(request)) }
        val pending = cursor.pending.filterTo(ArrayList()) { it.id !in excluded }
        val known = pending.mapTo(HashSet()) { it.id }
        var page = cursor.page
        var exhausted = cursor.exhausted
        var rounds = 0
        while (pending.size < BATCH_SIZE && !exhausted && rounds < MAX_ROUNDS) {
            rounds++
            val round = fetchRound(request, page)
            page++
            exhausted = round.last
            round.candidates.forEach { candidate ->
                if (candidate.isCandidate(request) && candidate.id !in excluded && known.add(candidate.id)) {
                    pending += candidate
                }
            }
        }
        val batch = pending.take(BATCH_SIZE)
        val rest = pending.drop(BATCH_SIZE).take(MAX_PENDING)
        return FeedBatch(
            movies = enrich(batch),
            next = FeedCursor(page = page, pending = rest, exhausted = exhausted),
            endReached = batch.isEmpty() || (exhausted && rest.isEmpty()),
        )
    }

    suspend fun apply(movie: MovieEntity, action: CardAction): ActionRecord {
        val status = when (action) {
            CardAction.WATCHED -> WatchStatus.WATCHED
            CardAction.UNWATCH -> WatchStatus.NONE
            CardAction.WATCHLIST -> WatchStatus.WATCHLIST
            CardAction.DISMISS -> WatchStatus.DISMISSED
            CardAction.REQUEST -> {
                if (series) {
                    // Série : elle passe dans la liste À voir, et l'utilisateur choisit les saisons à demander.
                    val service = settings.homelabConfig.first().requestService ?: return apply(movie, CardAction.WATCHLIST)
                    return apply(movie, CardAction.WATCHLIST).copy(action = CardAction.REQUEST, askSeasons = service)
                }
                val ticket = homelab.requestMovie(movie)
                    // Plus de service de demandes (réglages changés entre-temps) : simple ajout à la liste.
                    ?: return apply(movie, CardAction.WATCHLIST)
                return ActionRecord(movie = movie, action = action, previous = ticket.previous, request = ticket)
            }
        }
        val previous = database.libraryDao().applyCardAction(movie, status, clock())
        if (status == WatchStatus.WATCHED || previous?.status == WatchStatus.WATCHED) onWatchedChanged()
        return ActionRecord(movie = movie, action = action, previous = previous)
    }

    /** Série : demande des saisons choisies (null = toute la série). */
    suspend fun requestSeasons(movie: MovieEntity, seasons: List<Int>?): RequestTicket? = homelab.requestMovie(movie, seasons)

    /** Série vue d'un swipe : où en est l'utilisateur (la saison ne vaut que pour en cours ou décrochée). */
    suspend fun setProgress(tmdbId: Long, progress: SeriesProgress, season: Int?) {
        val withSeason = progress == SeriesProgress.IN_PROGRESS || progress == SeriesProgress.ABANDONED
        database.libraryDao().setProgress(tmdbId, progress, season.takeIf { withSeason }, clock())
    }

    suspend fun undo(record: ActionRecord) {
        database.libraryDao().restore(record.movie.tmdbId, record.previous)
        record.request?.let { homelab.cancelLater(it.outboxId) }
        if (record.action == CardAction.WATCHED || record.action == CardAction.UNWATCH || record.previous?.status == WatchStatus.WATCHED) {
            onWatchedChanged()
        }
    }

    /** Le film vient d'être affiché : il sera écarté des recos pendant un moment. */
    suspend fun markShown(tmdbId: Long, reason: RecoReason? = null) {
        database.libraryDao().markShown(tmdbId, clock())
        reason?.let { recos.recordShown(it.source) }
    }

    /** Bilan des sources des recos : une action (ou son annulation) sur une carte des recos. */
    suspend fun recordFeedback(reason: RecoReason, action: CardAction, undo: Boolean = false) {
        recos.recordAction(reason.source, action, undo)
    }

    /**
     * Découverte « dispo » : les films du serveur, pris dans la base. Les fiches encore partielles
     * (synchro récente) sont complétées depuis TMDB au passage, dans la limite de quelques-unes par lot.
     */
    private suspend fun loadAvailable(filters: DiscoverFilters, cursor: FeedCursor, sessionIds: Set<Long>): FeedBatch {
        val candidates = database.homelabDao().availableMovies(filters.yearMin, filters.yearMax)
        val movies = ArrayList<MovieEntity>()
        var index = cursor.page - 1
        var fetches = 0
        while (movies.size < BATCH_SIZE && index < candidates.size) {
            var movie = candidates[index++]
            if (movie.tmdbId in sessionIds) continue
            if (movie.fetchedAt == null && fetches < MAX_AVAILABLE_FETCHES) {
                fetches++
                movie = try {
                    tmdb.ensureDetails(movie.tmdbId)
                } catch (error: TmdbException) {
                    movie
                }
            }
            if (filters.genreId != null && filters.genreId !in movie.genreIds) continue
            if (filters.minRating > 0f && (movie.tmdbRating ?: 0.0) < filters.minRating) continue
            if (!filters.length.accepts(movie.runtimeMinutes, series)) continue
            movies += movie
        }
        val exhausted = index >= candidates.size
        return FeedBatch(
            movies = movies,
            next = FeedCursor(page = index + 1, pending = emptyList(), exhausted = exhausted),
            endReached = movies.isEmpty() || exhausted,
        )
    }

    private suspend fun excludedIds(request: FeedRequest): List<Long> {
        val excludeAvailable = settings.homelabConnected.first()
        val dao = database.userMovieDao()
        return when (request) {
            FeedRequest.Recos -> dao.recosExcludedIds(SHOWN_COOLDOWN_MS, clock(), excludeAvailable)
            is FeedRequest.Lightning, is FeedRequest.Discover -> dao.actedIds(excludeAvailable)
            is FeedRequest.Search -> emptyList()
        }
    }

    private class Round(val candidates: List<TmdbMovieSummaryDto>, val last: Boolean)

    private suspend fun fetchRound(request: FeedRequest, page: Int): Round = when (request) {
        FeedRequest.Recos -> Round(emptyList(), last = true)
        is FeedRequest.Lightning -> {
            val result = tmdb.recommendations(request.seedId, page)
            // Les recommandations d'un film vu ou à voir nourrissent aussi les recos.
            recos.learnFromPage(request.seedId, result)
            pageRound(page, result)
        }
        is FeedRequest.Search -> pageRound(page, tmdb.search(request.query, page))
        is FeedRequest.Discover -> pageRound(page, tmdb.discover(request.filters.toQuery(series), page))
    }

    private fun pageRound(page: Int, result: TmdbPageDto): Round =
        Round(result.results, last = page >= result.totalPages.coerceAtMost(MAX_TMDB_PAGE))

    /** Fiches complètes des films du lot, dans l'ordre (repli sur la fiche partielle en cas d'échec). */
    private suspend fun enrich(summaries: List<TmdbMovieSummaryDto>): List<MovieEntity> {
        if (summaries.isEmpty()) return emptyList()
        val language = settings.language.first()
        val genreNames = try {
            tmdb.genres().associate { it.id to it.name }
        } catch (error: TmdbException) {
            emptyMap()
        }
        val semaphore = Semaphore(ENRICH_CONCURRENCY)
        return coroutineScope {
            summaries.map { summary ->
                async {
                    semaphore.withPermit {
                        try {
                            tmdb.ensureDetails(summary.id)
                        } catch (error: TmdbException) {
                            TmdbMapper.summaryToEntity(summary, genreNames, language)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun TmdbMovieSummaryDto.isCandidate(request: FeedRequest): Boolean {
        if (adult) return false
        if (TmdbMapper.cleanText(title) == null && TmdbMapper.cleanText(originalTitle) == null) return false
        // Une carte sans affiche fait triste figure, sauf pour une recherche explicite.
        return request is FeedRequest.Search || TmdbMapper.imagePath(posterPath) != null
    }

    companion object {
        const val BATCH_SIZE = 10
        const val MAX_PENDING = 60
        const val MAX_ROUNDS = 3
        const val MAX_TMDB_PAGE = 500
        const val ENRICH_CONCURRENCY = 6

        /** Fiches partielles complétées au plus par lot de la découverte « dispo ». */
        const val MAX_AVAILABLE_FETCHES = 12

        /**
         * Un film montré sans réaction n'est pas reproposé avant 30 jours, puis 60, 120… (16 mois
         * au plus) : il revient s'il correspond toujours aux goûts.
         */
        const val SHOWN_COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000
    }
}
