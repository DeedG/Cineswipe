package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.model.DuelHistoryRow
import io.github.deedg.cineswipe.data.model.LibraryItem
import io.github.deedg.cineswipe.data.model.LibraryStats
import io.github.deedg.cineswipe.data.model.MovieDetails
import io.github.deedg.cineswipe.domain.StarScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class LibraryRepository(
    private val database: CineSwipeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Un film entre dans les vus ou en sort : le classement est à recalculer. */
    private val onWatchedChanged: () -> Unit = {},
) {
    private val userMovies = database.userMovieDao()

    fun observeLibrary(): Flow<List<LibraryItem>> = userMovies.observeLibrary()

    fun observeStats(): Flow<LibraryStats> = userMovies.observeStats()

    fun observeDetails(tmdbId: Long): Flow<MovieDetails?> = combine(
        database.movieDao().observe(tmdbId),
        userMovies.observe(tmdbId),
        userMovies.observeRanked(),
    ) { movie, user, watched ->
        movie?.let {
            val stars = if (user?.status == WatchStatus.WATCHED) {
                StarScale.computeStars(watched.associate { c -> c.tmdbId to c.rating })[tmdbId]
            } else {
                null
            }
            MovieDetails(movie = it, user = user, stars = stars)
        }
    }

    suspend fun details(tmdbId: Long): MovieDetails? {
        val movie = database.movieDao().get(tmdbId) ?: return null
        val user = userMovies.get(tmdbId)
        val stars = if (user?.status == WatchStatus.WATCHED) {
            StarScale.computeStars(userMovies.ranked().associate { it.tmdbId to it.rating })[tmdbId]
        } else {
            null
        }
        return MovieDetails(movie = movie, user = user, stars = stars)
    }

    suspend fun markWatched(tmdbId: Long) {
        database.libraryDao().setWatched(tmdbId, watched = true, now = clock())
        onWatchedChanged()
    }

    suspend fun unmarkWatched(tmdbId: Long) {
        database.libraryDao().setWatched(tmdbId, watched = false, now = clock())
        onWatchedChanged()
    }

    /**
     * Série : où en est l'utilisateur. Une série pas encore vue passe dans les vues (elle peut
     * ensuite combattre dans l'Arène). [season] n'a de sens qu'en cours ou abandonnée.
     */
    suspend fun setProgress(tmdbId: Long, progress: SeriesProgress, season: Int?) {
        val dao = database.libraryDao()
        val wasWatched = dao.userMovie(tmdbId)?.status == WatchStatus.WATCHED
        if (!wasWatched) dao.setWatched(tmdbId, watched = true, now = clock())
        val withSeason = progress == SeriesProgress.IN_PROGRESS || progress == SeriesProgress.ABANDONED
        dao.setProgress(tmdbId, progress, season.takeIf { withSeason }, clock())
        if (!wasWatched) onWatchedChanged()
    }

    /** À voir, pas pour moi, ou retour à neutre. Le classement et les duels sont conservés. */
    suspend fun setStatus(tmdbId: Long, status: WatchStatus) {
        val previous = database.libraryDao().applyStatus(tmdbId, status, clock())
        if (status == WatchStatus.WATCHED || previous?.status == WatchStatus.WATCHED) onWatchedChanged()
    }

    /** Duels du journal, du plus récent au plus ancien. */
    fun observeDuelHistory(limit: Int = HISTORY_LIMIT): Flow<List<DuelHistoryRow>> = database.arenaDao().observeHistory(limit)

    /** Nombre de duels du journal (les duels importés de la V4 n'y sont pas). */
    fun observeLoggedDuels(): Flow<Int> = database.arenaDao().observeDuelCount()

    /** Recommencer le classement : les films vus restent, les duels sont effacés. */
    suspend fun resetRanking() {
        database.libraryDao().resetRanking()
        database.arenaDao().rerank()
    }

    /** Repartir de zéro : films vus, listes, classement et demandes en attente effacés. */
    suspend fun eraseAll() = database.libraryDao().eraseAll()

    private companion object {
        const val HISTORY_LIMIT = 500
    }
}
