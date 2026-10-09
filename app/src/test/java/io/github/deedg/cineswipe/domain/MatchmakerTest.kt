package io.github.deedg.cineswipe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatchmakerTest {

    private fun candidate(id: Long, rating: Double, deviation: Double = 80.0, duels: Int = 10) =
        ArenaCandidate(id, rating, duels, deviation)

    private fun newFilm(id: Long) = candidate(id, Ranking.INITIAL_RATING, deviation = Ranking.INITIAL_DEVIATION, duels = 0)

    @Test
    fun noMatchWithLessThanTwoFilms() {
        assertNull(Matchmaker(Random(1)).pick(emptyList()))
        assertNull(Matchmaker(Random(1)).pick(listOf(candidate(1, 1500.0))))
    }

    @Test
    fun neverPairsAFilmWithItself() {
        val matchmaker = Matchmaker(Random(42))
        val candidates = (1L..20L).map { candidate(it, 1400.0 + it * 10, deviation = 60.0 + it * 15) }
        repeat(1_000) {
            val (a, b) = matchmaker.pick(candidates)!!
            assertNotEquals(a.tmdbId, b.tmdbId)
        }
    }

    /** Un nouveau film affronte d'abord un film bien connu de son niveau, pas un film très éloigné. */
    @Test
    fun aNewFilmMeetsAWellKnownFilmOfItsLevel() {
        val matchmaker = Matchmaker(Random(7), shortlist = 1, explorationRate = 0.0)
        val candidates = listOf(newFilm(1), candidate(2, 1500.0), candidate(3, 2100.0))
        val pairs = (1..500).map {
            val (a, b) = matchmaker.pick(candidates)!!
            setOf(a.tmdbId, b.tmdbId)
        }
        assertTrue(pairs.count { it == setOf(1L, 2L) } > 350)
        // Deux films déjà bien classés et très éloignés : presque rien à apprendre.
        assertTrue(pairs.count { it == setOf(2L, 3L) } < 25)
    }

    @Test
    fun uncertainFilmsArePickedMoreOften() {
        val matchmaker = Matchmaker(Random(5), explorationRate = 0.0)
        val candidates = listOf(newFilm(1)) + (2L..10L).map { candidate(it, 1500.0 + it) }
        val appearances = (1..2_000).count {
            val (a, b) = matchmaker.pick(candidates)!!
            a.tmdbId == 1L || b.tmdbId == 1L
        }
        // Tirage uniforme : environ 22 % des duels. Ici, nettement plus.
        assertTrue("apparitions : $appearances", appearances > 2_000 * 0.6)
    }

    @Test
    fun aPairAlreadyPlayedIsLessAttractive() {
        val matchmaker = Matchmaker(Random(11), explorationRate = 0.0)
        val candidates = listOf(newFilm(1), candidate(2, 1500.0), candidate(3, 1500.0))
        val played = mapOf(Matchmaker.pairKey(1, 2) to 3)
        val withNew = (1..1_000).mapNotNull {
            val (a, b) = matchmaker.pick(candidates, pairCounts = played)!!
            setOf(a.tmdbId, b.tmdbId).takeIf { 1L in it }
        }
        assertTrue(withNew.count { 3L in it } > withNew.size * 0.7)
    }

    @Test
    fun cooldownFilmsAreSkippedWhenPossible() {
        val matchmaker = Matchmaker(Random(3))
        val candidates = (1L..4L).map { candidate(it, 1500.0) }
        repeat(200) {
            val (a, b) = matchmaker.pick(candidates, cooldownIds = setOf(1L, 2L))!!
            assertEquals(setOf(3L, 4L), setOf(a.tmdbId, b.tmdbId))
        }
    }

    @Test
    fun recentPairIsAvoidedWhenAnotherOpponentExists() {
        val matchmaker = Matchmaker(Random(11), explorationRate = 0.0)
        val candidates = listOf(candidate(1, 1500.0), candidate(2, 1501.0), candidate(3, 1700.0))
        val recent = setOf(Matchmaker.pairKey(1, 2))
        repeat(200) {
            val (a, b) = matchmaker.pick(candidates, recentPairs = recent)!!
            assertNotEquals(Matchmaker.pairKey(1, 2), Matchmaker.pairKey(a.tmdbId, b.tmdbId))
        }
    }

    @Test
    fun expectedGainPrefersCloseAndWellKnownOpponents() {
        val anchor = newFilm(1)
        val close = Matchmaker.expectedGain(anchor, candidate(2, 1500.0))
        val far = Matchmaker.expectedGain(anchor, candidate(3, 2200.0))
        val vague = Matchmaker.expectedGain(anchor, candidate(4, 1500.0, deviation = 300.0))
        assertTrue(close > far)
        assertTrue(close > 0.0)
        // Un adversaire incertain apprend moins sur le premier film, mais apprend lui-même : gain réel.
        assertTrue(vague > 0.0)
    }
}
