package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.taste.TasteCode
import io.github.deedg.cineswipe.domain.taste.TasteMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Résultat d'une comparaison, avec les fiches à afficher. */
data class FriendMatch(val friend: TasteCode, val match: TasteMatch, val movies: Map<Long, MovieEntity>)

/**
 * Comparer ses goûts entre amis : mon profil de goût en code (QR ou texte), et la comparaison
 * avec celui d'un ami. Tout reste sur le téléphone : rien n'est envoyé ailleurs.
 */
class TasteShareRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val series: Boolean,
) {
    /** Mes titres et leurs demi-étoiles (tout le classement). */
    private suspend fun myRatings(): Map<Long, Int> {
        val ranked = database.userMovieDao().ranked()
        val stars = StarScale.computeStars(ranked.associate { it.tmdbId to it.rating })
        return stars.mapValues { (_, value) -> TasteCode.halfStars(value) }
    }

    /**
     * Mon code : mes préférés, mes moins aimés et les titres les plus vus (ceux qu'un ami a le plus
     * de chances d'avoir vus aussi), et ma liste À voir. Assez court pour un QR code lisible.
     */
    suspend fun myCode(name: String): TasteCode = withContext(Dispatchers.Default) {
        val ratings = myRatings()
        val movies = database.recoDao().movies(ratings.keys.toList()).associateBy { it.tmdbId }
        val byStars = ratings.entries.sortedByDescending { it.value }.map { it.key }
        val chosen = LinkedHashSet<Long>()
        chosen += byStars.take(TOP)
        chosen += byStars.takeLast(BOTTOM)
        chosen += ratings.keys.sortedByDescending { movies[it]?.voteCount ?: 0 }.filter { it !in chosen }.take(POPULAR)
        val watchlist = database.diagnosticsDao().userMovies()
            .filter { it.status == WatchStatus.WATCHLIST }
            .sortedByDescending { it.updatedAt }
            .take(WATCHLIST)
            .map { it.tmdbId }
        TasteCode(name.trim(), series, chosen.associateWith { ratings.getValue(it) }, watchlist)
    }

    /** Comparaison avec le code d'un ami ; les fiches qui manquent sont chargées (au mieux). */
    suspend fun compare(friend: TasteCode): FriendMatch = withContext(Dispatchers.Default) {
        val mine = myRatings()
        val users = database.diagnosticsDao().userMovies()
        val watchlist = users.filter { it.status == WatchStatus.WATCHLIST }.map { it.tmdbId }.toSet()
        val others = users.filter { it.status == WatchStatus.DISMISSED }.map { it.tmdbId }.toSet()
        val match = TasteMatch.compute(friend, mine, watchlist, others)
        val shown = (match.agreements + match.disagreements + match.bothWant + match.theyLoved + match.toRecommend).distinct()
        val cached = database.recoDao().movies(shown).associateBy { it.tmdbId }
        val missing = shown.filter { it !in cached }
        val fetched = coroutineScope {
            missing.map { id ->
                async {
                    try {
                        tmdb.ensureDetails(id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().associateBy { it.tmdbId }
        }
        FriendMatch(friend, match, cached + fetched)
    }

    private companion object {
        const val TOP = 50
        const val BOTTOM = 20
        const val POPULAR = 60
        const val WATCHLIST = 40
    }
}
