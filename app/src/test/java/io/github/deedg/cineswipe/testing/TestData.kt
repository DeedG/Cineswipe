package io.github.deedg.cineswipe.testing

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.domain.Ranking

/** Petite bibliothèque de test : trois films vus classés, un film dispo, une fiche en cache. */
object TestData {
    const val WATCHED = 3

    /** Duels comptés (8 + 9 + 7 côtés), hérités d'une ancienne version : sans historique détaillé. */
    const val DUELS = 12

    private data class Film(
        val id: Long,
        val title: String,
        val year: Int,
        val director: String?,
        val rating: Double? = null,
        val duels: Int = 0,
        val watched: Boolean = false,
        val available: Boolean = false,
        val cacheOnly: Boolean = false,
    )

    private val FILMS = listOf(
        Film(329865, "Premier Contact", 2016, "Denis Villeneuve", rating = 1610.0, duels = 8, watched = true),
        Film(64690, "Drive", 2011, "Nicolas Winding Refn", rating = 1625.0, duels = 9, watched = true, available = true),
        Film(157336, "Interstellar", 2014, "Christopher Nolan", rating = 1600.0, duels = 7, watched = true),
        Film(101, "Léon", 1994, "Luc Besson", available = true),
        Film(202, "Film du cache", 2020, director = null, cacheOnly = true),
    )

    suspend fun seed(database: CineSwipeDatabase, now: Long = System.currentTimeMillis()) {
        val dao = database.libraryDao()
        FILMS.forEach { film ->
            dao.upsertMovie(
                MovieEntity(
                    tmdbId = film.id,
                    title = film.title,
                    year = film.year,
                    posterPath = "/${film.id}.jpg",
                    overview = "Synopsis de ${film.title}.",
                    director = film.director,
                    cast = listOf(CastMember("Acteur Principal")),
                    fetchedAt = if (film.director != null) now else null,
                ),
            )
            dao.upsertUserMovie(
                if (film.cacheOnly) {
                    UserMovieEntity(tmdbId = film.id, shownCount = 1, updatedAt = now)
                } else {
                    UserMovieEntity(
                        tmdbId = film.id,
                        status = if (film.watched) WatchStatus.WATCHED else WatchStatus.NONE,
                        watchedAt = if (film.watched) now else null,
                        watchedSource = if (film.watched) WatchSource.MANUAL else null,
                        rating = film.rating ?: Ranking.INITIAL_RATING,
                        duels = film.duels,
                        priorRating = film.rating ?: Ranking.INITIAL_RATING,
                        priorDuels = film.duels,
                        availability = if (film.available) Availability.AVAILABLE else Availability.NONE,
                        updatedAt = now,
                    )
                },
            )
        }
    }
}
