package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.DuelEntity
import io.github.deedg.cineswipe.data.model.ArenaMatch
import io.github.deedg.cineswipe.data.model.Fighter
import io.github.deedg.cineswipe.data.model.RankingReliability
import io.github.deedg.cineswipe.domain.ArenaCandidate
import io.github.deedg.cineswipe.domain.Matchmaker
import io.github.deedg.cineswipe.domain.StarScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Films à placer en priorité dans l'Arène (après un visionnage sur Plex ou Jellyfin) : chacun
 * joue [perFilm] duels de suite, contre les adversaires qui le situent le mieux.
 */
data class ArenaFocus(val queue: List<Long>, val played: Int = 0, val perFilm: Int = ArenaRepository.FOCUS_DUELS) {
    val current: Long? get() = queue.firstOrNull()
}

class ArenaRepository(
    private val database: CineSwipeDatabase,
    private val matchmaker: Matchmaker = Matchmaker(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Portée des recalculs en arrière-plan ([requestRerank]). */
    private val scope: CoroutineScope? = null,
) {
    suspend fun watchedCount(): Int = database.userMovieDao().watchedCount()

    private val mutableFocus = MutableStateFlow<ArenaFocus?>(null)

    /** Placement en cours, sinon null. */
    val focus: StateFlow<ArenaFocus?> = mutableFocus.asStateFlow()

    /** Place ces films (les autres placements en attente passent après). */
    fun focusOn(ids: List<Long>) {
        if (ids.isEmpty()) return
        val waiting = mutableFocus.value?.queue.orEmpty()
        mutableFocus.value = ArenaFocus((ids + waiting).distinct())
    }

    fun clearFocus() {
        mutableFocus.value = null
    }

    /** Le film en cours de placement ne peut pas jouer (plus dans les vus) : au suivant. */
    fun skipFocus(tmdbId: Long) {
        mutableFocus.update { focus ->
            if (focus?.current != tmdbId) focus else focus.queue.drop(1).takeIf { it.isNotEmpty() }?.let { ArenaFocus(it) }
        }
    }

    /**
     * Un duel vient d'être joué. Si le film en placement y était, il compte ; au bout de
     * [ArenaFocus.perFilm] duels, il est placé : son identifiant est renvoyé et le suivant prend la main.
     */
    fun focusPlayed(aId: Long, bId: Long): Long? {
        var placed: Long? = null
        mutableFocus.update { focus ->
            val current = focus?.current
            if (focus == null || (current != aId && current != bId)) return@update focus
            val played = focus.played + 1
            if (played < focus.perFilm) {
                focus.copy(played = played)
            } else {
                placed = current
                focus.queue.drop(1).takeIf { it.isNotEmpty() }?.let { ArenaFocus(it) }
            }
        }
        return placed
    }

    /** Fiabilité du classement (confiance moyenne dans les étoiles), au pour cent près. */
    fun observeReliability(): Flow<RankingReliability> =
        database.userMovieDao().observeRanked()
            .map { ranked ->
                val value = if (ranked.isEmpty()) 0.0 else ranked.sumOf { it.confidence } / ranked.size
                RankingReliability(films = ranked.size, value = (value * 100).roundToInt() / 100.0)
            }
            .distinctUntilChanged()

    suspend fun pickMatch(cooldownIds: Set<Long>, recentPairs: Set<Pair<Long, Long>>, anchorId: Long? = null): ArenaMatch? {
        val candidates = database.userMovieDao().watchedCandidates()
        val pairCounts = database.arenaDao().pairCounts().associate { Matchmaker.pairKey(it.lowId, it.highId) to it.duels }
        val (a, b) = matchmaker.pick(candidates, cooldownIds, recentPairs, pairCounts, anchorId) ?: return null
        return buildMatch(a.tmdbId, b.tmdbId)
    }

    /** Recharge un match (après une annulation) avec les classements à jour. */
    suspend fun reloadMatch(aId: Long, bId: Long): ArenaMatch? = buildMatch(aId, bId)

    /** Enregistre un duel ([draw] : égalité) ; tout le classement est recalculé. */
    suspend fun recordDuel(winnerId: Long, loserId: Long, draw: Boolean = false): DuelEntity? =
        database.arenaDao().recordDuel(winnerId, loserId, draw, clock())

    suspend fun undoLastDuel(): DuelEntity? = database.arenaDao().undoLastDuel(clock())

    suspend fun undoDuel(id: Long): DuelEntity? = database.arenaDao().undoDuel(id, clock())

    private val rerankMutex = Mutex()

    @Volatile
    private var rerankPending = false

    /**
     * Recalcul du classement après un changement des films vus hors de l'Arène (fiche, cartes) :
     * les demandes rapprochées se regroupent, au plus un calcul en cours et un en attente.
     */
    fun requestRerank() {
        val target = scope ?: return
        rerankPending = true
        target.launch {
            rerankMutex.withLock {
                if (!rerankPending) return@withLock
                rerankPending = false
                database.arenaDao().rerank()
            }
        }
    }

    /**
     * Recalcule le classement. Lancé au démarrage : après une mise à jour de l'appli, les
     * incertitudes et confiances des films n'ont pas encore été calculées.
     */
    suspend fun refresh() {
        database.arenaDao().rerank()
    }

    private suspend fun buildMatch(aId: Long, bId: Long): ArenaMatch? {
        val ranked = database.userMovieDao().ranked()
        val byId = ranked.associateBy { it.tmdbId }
        val stars = StarScale.computeStars(ranked.associate { it.tmdbId to it.rating })
        val a = fighter(byId[aId] ?: return null, stars) ?: return null
        val b = fighter(byId[bId] ?: return null, stars) ?: return null
        return ArenaMatch(a, b)
    }

    private suspend fun fighter(candidate: ArenaCandidate, stars: Map<Long, Float>): Fighter? {
        val movie = database.movieDao().get(candidate.tmdbId) ?: return null
        return Fighter(
            tmdbId = candidate.tmdbId,
            title = movie.title,
            year = movie.year,
            posterPath = movie.posterPath,
            rating = candidate.rating,
            duels = candidate.duels,
            stars = stars[candidate.tmdbId] ?: 1f,
            provisional = StarScale.isProvisional(candidate.confidence),
        )
    }

    companion object {
        /** Duels joués par un film en placement. */
        const val FOCUS_DUELS = 3
    }
}
