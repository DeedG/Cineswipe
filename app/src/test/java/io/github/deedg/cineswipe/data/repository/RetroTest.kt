package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RetroTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int = 10): Long = LocalDateTime.of(year, month, day, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val nolan = TmdbTag(525, "Christopher Nolan")

    private fun row(
        id: Long,
        watchedAt: Long?,
        rating: Double = 1500.0,
        censusAt: Long? = null,
        genres: List<String> = listOf("Drame"),
        directors: List<TmdbTag> = emptyList(),
        runtime: Int = 120,
    ) = RetroRow(id, "Film $id", 2000, "/$id.jpg", runtime, genres, directors, rating, watchedAt, censusAt, WatchSource.MANUAL)

    @Test
    fun theYearCountsFilmsWithARealDateAndSetsBackfillAside() {
        val census = at(2026, 10, 2)
        val bulk = at(2026, 10, 5)
        val rows = listOf(
            row(1, at(2026, 3), rating = 1700.0, directors = listOf(nolan), genres = listOf("Science-Fiction", "Drame")),
            row(2, at(2026, 3, 20), rating = 1600.0, directors = listOf(nolan)),
            row(3, at(2026, 7), rating = 1400.0, runtime = 90),
            // Recensement : la date est celle de la réponse.
            row(4, census, rating = 1900.0, censusAt = census),
            row(5, at(2025, 12), rating = 1650.0),
            row(6, null),
        ) + (10L..17L).map { row(it, bulk, rating = 1800.0) }
        val duels = listOf(at(2026, 1), at(2026, 2), at(2025, 6))

        assertEquals(listOf(2026, 2025), RetroRepository.years(rows, duels, zone))
        val review = RetroRepository.compute(rows, duels, 2026, zone)
        assertEquals(3, review.films)
        assertEquals(9, review.backfill)
        assertEquals(5, review.hours)
        assertEquals(2, review.months[2])
        assertEquals(1, review.months[6])
        assertEquals(2, review.bestMonth)
        assertEquals(2, review.duels)
        // Le top ne garde que les films vus cette année, par note de classement.
        assertEquals(listOf(1L, 2L, 3L), review.top.map { it.tmdbId })
        assertEquals("Drame", review.genre)
        assertEquals(3, review.genreFilms)
        assertEquals(nolan, review.director)
        assertEquals(2, review.directorFilms)
        assertEquals(1L, review.first?.tmdbId)
        assertEquals(3L, review.last?.tmdbId)
        // Les étoiles viennent du classement entier.
        assertTrue(review.top.first().stars > review.top.last().stars)
    }

    @Test
    fun aYearWithOnlyBackfillIsEmpty() {
        val census = at(2026, 10, 2)
        val review = RetroRepository.compute(listOf(row(4, census, censusAt = census)), emptyList(), 2026, zone)
        assertTrue(review.empty)
        assertEquals(1, review.backfill)
        assertNull(review.bestMonth)
        assertNull(review.first)
        assertNull(review.director)
    }
}
