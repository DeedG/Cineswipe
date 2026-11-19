package io.github.deedg.cineswipe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartingRatingTest {
    private fun film(id: Long, rating: Double, genre: Int, director: Long = id) =
        FilmTraits(id, tmdbRating = rating, voteCount = 5_000, genres = listOf(genre), directors = listOf(director))

    @Test
    fun nothingToLearnFromTooFewFilms() {
        assertNull(StartingRating.learn(List(StartingRating.MIN_TRAINING - 1) { film(it.toLong(), 7.0, 18) to 0.0 }))
    }

    @Test
    fun learnsTastesAndStaysModest() {
        // Les drames (18) plaisent, les films d'horreur (27) non, et la note TMDB compte un peu.
        val ranked = (0 until 40).map { i ->
            val genre = if (i % 2 == 0) 18 else 27
            val rating = 6.0 + (i % 5) * 0.5
            film(i.toLong(), rating, genre) to (if (genre == 18) 150.0 else -150.0) + (rating - 7.0) * 80
        }
        val model = StartingRating.learn(ranked)!!
        val drama = model.shift(film(1000, 7.0, 18))
        val horror = model.shift(film(1001, 7.0, 27))
        assertTrue("drame $drama", drama > 30)
        assertTrue("horreur $horror", horror < -30)
        // Un meilleur film TMDB part plus haut, à genre égal.
        assertTrue(model.shift(film(1002, 8.5, 18)) > drama)
        // Jamais plus que le plafond, même pour un film extrême.
        assertTrue(model.shift(film(1003, 10.0, 18)) <= StartingRating.MAX_SHIFT)
        // Un genre jamais vu : seule la note TMDB joue.
        assertEquals(0.0, model.shift(film(1004, 7.0 - 0.0, 99)), 40.0)
    }
}
