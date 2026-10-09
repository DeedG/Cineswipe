package io.github.deedg.cineswipe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarScaleTest {

    @Test
    fun bucketsFollowTheRankOutOfOneHundred() {
        val expected = mapOf(
            0 to 5f, 4 to 5f,
            5 to 4.5f, 14 to 4.5f,
            15 to 4f, 29 to 4f,
            30 to 3.5f, 49 to 3.5f,
            50 to 3f, 69 to 3f,
            70 to 2.5f, 84 to 2.5f,
            85 to 2f, 94 to 2f,
            95 to 1f, 99 to 1f,
        )
        expected.forEach { (rank, stars) ->
            assertEquals("rang $rank", stars, StarScale.starsForRank(rank, 100), 0f)
        }
    }

    @Test
    fun bestFilmGetsFiveStarsEvenInATinyLibrary() {
        val stars = StarScale.computeStars(mapOf(1L to 1600.0, 2L to 1500.0, 3L to 1400.0))
        assertEquals(5f, stars.getValue(1L), 0f)
        assertTrue(stars.getValue(3L) < stars.getValue(2L))
    }

    @Test
    fun tiesShareTheSameStars() {
        val stars = StarScale.computeStars(mapOf(1L to 1500.0, 2L to 1500.0, 3L to 1400.0, 4L to 1300.0))
        assertEquals(stars.getValue(1L), stars.getValue(2L), 0f)
    }

    @Test
    fun emptyLibraryHasNoStars() {
        assertTrue(StarScale.computeStars(emptyMap()).isEmpty())
    }

    @Test
    fun starsAreProvisionalUntilTheyAreLikelyRight() {
        assertTrue(StarScale.isProvisional(0.0))
        assertTrue(StarScale.isProvisional(0.69))
        assertFalse(StarScale.isProvisional(0.7))
        assertFalse(StarScale.isProvisional(1.0))
    }

    @Test
    fun bucketsMatchTheStars() {
        val total = 100
        for (rank in 0 until total) {
            val bucket = StarScale.bucketForRank(rank, total)
            val expected = if (bucket < StarScale.buckets.size) StarScale.buckets[bucket].second else 1f
            assertEquals(expected, StarScale.starsForRank(rank, total), 0f)
        }
        val ratings = doubleArrayOf(1700.0, 1500.0, 1500.0, 1300.0)
        // Ex aequo : même palier (rang 1 sur 4 = 25 % → 4 étoiles) ; le dernier, à 75 % → 2,5 étoiles.
        assertEquals(listOf(0, 2, 2, 5), StarScale.computeBuckets(ratings).toList())
    }
}
