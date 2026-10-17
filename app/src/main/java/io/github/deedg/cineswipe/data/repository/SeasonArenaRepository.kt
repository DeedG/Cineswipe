package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.SeasonDuelEntity
import io.github.deedg.cineswipe.data.db.SeasonRankEntity
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.domain.ArenaCandidate
import io.github.deedg.cineswipe.domain.DuelOutcome
import io.github.deedg.cineswipe.domain.Matchmaker
import io.github.deedg.cineswipe.domain.Ranking
import io.github.deedg.cineswipe.domain.RankingPrior
import io.github.deedg.cineswipe.domain.StarScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Une saison vue, prête pour l'Arène des saisons. */
data class SeasonItem(
    val key: Long,
    val tmdbId: Long,
    val season: Int,
    val title: String,
    val poster: String?,
    val year: Int?,
    val episodes: Int?,
    val rating: Double,
    val deviation: Double,
    val confidence: Double,
    val duels: Int,
    val stars: Float = 0f,
)

/** Un duel de saisons ; [duelId] pour l'annuler. */
data class SeasonMatch(val a: SeasonItem, val b: SeasonItem)

/**
 * Arène des saisons : les saisons vues se classent entre elles, à part du classement des séries
 * (une série inégale peut avoir une saison 4 au sommet et une saison 7 tout en bas). Saisons vues :
 * toutes celles d'une série finie, celles d'avant la saison en cours, et jusqu'à la saison où l'on
 * a décroché ; rien pour une série vue par épisodes ou sans saison connue.
 */
class SeasonArenaRepository(
    private val database: CineSwipeDatabase,
    private val matchmaker: Matchmaker = Matchmaker(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val dao get() = database.seasonArenaDao()
    private val mutex = Mutex()

    /** Saisons vues (et leur ligne de classement, créée au besoin), avec leurs étoiles. */
    suspend fun items(): List<SeasonItem> = withContext(Dispatchers.Default) {
        mutex.withLock { load() }
    }

    private suspend fun load(): List<SeasonItem> {
        val users = database.diagnosticsDao().userMovies().filter { it.status == WatchStatus.WATCHED }
        val movies = database.recoDao().movies(users.map { it.tmdbId }).associateBy { it.tmdbId }
        val eligible = users.flatMap { user -> movies[user.tmdbId]?.let { seen(user, it) }.orEmpty() }
        if (eligible.isEmpty()) return emptyList()
        dao.insertRanks(eligible.map { (movie, season) -> SeasonRankEntity(SeasonRankEntity.key(movie.tmdbId, season), movie.tmdbId, season) })
        val ranks = dao.ranks().associateBy { it.key }
        val items = eligible.mapNotNull { (movie, season) ->
            val rank = ranks[SeasonRankEntity.key(movie.tmdbId, season)] ?: return@mapNotNull null
            val info = movie.seasons.firstOrNull { it.number == season }
            SeasonItem(
                key = rank.key,
                tmdbId = movie.tmdbId,
                season = season,
                title = movie.title,
                poster = info?.poster ?: movie.posterPath,
                year = info?.year,
                episodes = info?.episodeCount,
                rating = rank.rating,
                deviation = rank.deviation,
                confidence = rank.confidence,
                duels = rank.duels,
            )
        }
        val stars = StarScale.computeStars(items.associate { it.key to it.rating })
        return items.map { it.copy(stars = stars[it.key] ?: 0f) }
    }

    /** Saisons vues d'une série, d'après son avancement. */
    private fun seen(user: UserMovieEntity, movie: MovieEntity): List<Pair<MovieEntity, Int>> {
        val year = today().year
        val aired = movie.seasons.filter { (it.episodeCount ?: 0) > 0 && (it.year == null || it.year <= year) }.map { it.number }
            .ifEmpty { (1..(movie.seasonCount ?: 0)).toList() }
        val upTo = when (user.progress) {
            null, SeriesProgress.FINISHED -> Int.MAX_VALUE
            SeriesProgress.IN_PROGRESS -> (user.currentSeason ?: return emptyList()) - 1
            SeriesProgress.ABANDONED -> user.currentSeason ?: return emptyList()
            SeriesProgress.EPISODES -> return emptyList()
        }
        return aired.filter { it in 1..upTo }.map { movie to it }
    }

    /** Prochain duel (deux saisons au moins), en évitant les saisons tout juste vues. */
    suspend fun pickMatch(cooldown: Set<Long> = emptySet(), recentPairs: Set<Pair<Long, Long>> = emptySet()): SeasonMatch? {
        val items = items()
        if (items.size < 2) return null
        val byKey = items.associateBy { it.key }
        val pairCounts = dao.duels().groupingBy { Matchmaker.pairKey(it.winnerKey, it.loserKey) }.eachCount()
        val (a, b) = matchmaker.pick(
            items.map { ArenaCandidate(it.key, it.rating, it.duels, it.deviation, it.confidence) },
            cooldown,
            recentPairs,
            pairCounts,
        ) ?: return null
        return SeasonMatch(byKey.getValue(a.tmdbId), byKey.getValue(b.tmdbId))
    }

    /** Enregistre un duel et recalcule le classement des saisons ; renvoie l'identifiant du duel. */
    suspend fun vote(winner: Long, loser: Long, draw: Boolean = false): Long = mutex.withLock {
        val id = dao.insertDuel(SeasonDuelEntity(winnerKey = winner, loserKey = loser, draw = draw, createdAt = clock()))
        rerank()
        id
    }

    suspend fun undo(duelId: Long) = mutex.withLock {
        dao.deleteDuel(duelId)
        rerank()
    }

    /** Notes de toutes les saisons à partir du journal (même modèle que l'Arène). */
    private suspend fun rerank() = withContext(Dispatchers.Default) {
        val ranks = dao.ranks()
        val ratings = Ranking.compute(
            ranks.map { RankingPrior(it.key, current = it.rating) },
            dao.duels().map { DuelOutcome(it.winnerKey, it.loserKey, it.draw) },
        )
        val confidence = Ranking.starConfidence(ratings.filterKeys { key -> ranks.any { it.key == key } })
        val now = clock()
        ranks.forEach { rank ->
            val rating = ratings[rank.key] ?: return@forEach
            dao.updateRank(rank.key, rating.rating, rating.deviation, confidence[rank.key] ?: rank.confidence, rating.duels, now)
        }
    }

    companion object {
        /** Fiabilité moyenne du classement des saisons (0 à 1). */
        fun reliability(items: List<SeasonItem>): Double = if (items.isEmpty()) 0.0 else items.sumOf { it.confidence } / items.size
    }
}
