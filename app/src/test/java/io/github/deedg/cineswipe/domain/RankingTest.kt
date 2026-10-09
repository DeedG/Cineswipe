package io.github.deedg.cineswipe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RankingTest {

    private fun priors(vararg ids: Long) = ids.map { RankingPrior(it) }

    @Test
    fun withoutDuelsEachFilmKeepsItsStartingRating() {
        val ratings = Ranking.compute(
            listOf(RankingPrior(1), RankingPrior(2, rating = 1620.0, duels = 12)),
            emptyList(),
        )
        assertEquals(1500.0, ratings.getValue(1).rating, 1e-6)
        assertEquals(Ranking.INITIAL_DEVIATION, ratings.getValue(1).deviation, 1e-6)
        assertEquals(1620.0, ratings.getValue(2).rating, 1e-6)
        assertEquals(Ranking.priorDeviation(12), ratings.getValue(2).deviation, 1e-6)
        assertEquals(12, ratings.getValue(2).duels)
    }

    @Test
    fun oneWinSplitsTwoEqualFilmsSymmetrically() {
        val ratings = Ranking.compute(priors(1, 2), listOf(DuelOutcome(1, 2)))
        val winner = ratings.getValue(1)
        val loser = ratings.getValue(2)
        assertTrue(winner.rating > 1550.0)
        assertEquals(3000.0, winner.rating + loser.rating, 1e-3)
        assertEquals(winner.deviation, loser.deviation, 1e-6)
        assertTrue(winner.deviation < Ranking.INITIAL_DEVIATION)
        assertEquals(1, winner.duels)
    }

    /** L'Elo de la V4 dépendait de l'ordre des matchs ; ici, seul l'ensemble des résultats compte. */
    @Test
    fun theOrderOfTheDuelsDoesNotMatter() {
        val random = Random(3)
        val duels = (1..60).map {
            val a = random.nextLong(1, 9)
            var b = random.nextLong(1, 9)
            while (b == a) b = random.nextLong(1, 9)
            DuelOutcome(a, b, draw = it % 7 == 0)
        }
        val ids = (1L..8L).toList().toLongArray()
        val forward = Ranking.compute(priors(*ids), duels)
        val backward = Ranking.compute(priors(*ids), duels.reversed())
        ids.forEach { id ->
            assertEquals(forward.getValue(id).rating, backward.getValue(id).rating, 0.01)
            assertEquals(forward.getValue(id).deviation, backward.getValue(id).deviation, 0.01)
        }
    }

    @Test
    fun theStartingPointOnlySpeedsThingsUp() {
        val duels = listOf(DuelOutcome(1, 2), DuelOutcome(2, 3), DuelOutcome(1, 3), DuelOutcome(3, 4, draw = true))
        val cold = Ranking.compute(priors(1, 2, 3, 4), duels)
        val warm = Ranking.compute(
            listOf(1L, 2L, 3L, 4L).map { RankingPrior(it, current = 1500.0 + it * 37) },
            duels,
        )
        listOf(1L, 2L, 3L, 4L).forEach { assertEquals(cold.getValue(it).rating, warm.getValue(it).rating, 0.01) }
    }

    @Test
    fun aDrawBetweenEqualFilmsKeepsThemEqualButMoreCertain() {
        val ratings = Ranking.compute(priors(1, 2), listOf(DuelOutcome(1, 2, draw = true)))
        assertEquals(1500.0, ratings.getValue(1).rating, 1e-6)
        assertEquals(1500.0, ratings.getValue(2).rating, 1e-6)
        assertTrue(ratings.getValue(1).deviation < Ranking.INITIAL_DEVIATION)
    }

    @Test
    fun moreDuelsMeanLessUncertainty() {
        val few = Ranking.compute(priors(1, 2, 3), listOf(DuelOutcome(1, 2), DuelOutcome(3, 1)))
        val many = Ranking.compute(
            priors(1, 2, 3),
            List(6) { DuelOutcome(1, 2) } + List(6) { DuelOutcome(3, 1) } + List(3) { DuelOutcome(2, 1) },
        )
        assertTrue(many.getValue(1).deviation < few.getValue(1).deviation)
        assertEquals(15, many.getValue(1).duels)
    }

    @Test
    fun selfDuelsAreIgnoredAndUnknownFilmsStartAt1500() {
        val ratings = Ranking.compute(priors(1), listOf(DuelOutcome(1, 1), DuelOutcome(9, 1)))
        assertEquals(setOf(1L, 9L), ratings.keys)
        assertTrue(ratings.getValue(9).rating > 1500.0)
        assertEquals(1, ratings.getValue(1).duels)
    }

    @Test
    fun inheritedRatingsAreLessCertainWithFewerDuels() {
        assertEquals(Ranking.INITIAL_DEVIATION, Ranking.priorDeviation(0), 0.0)
        assertTrue(Ranking.priorDeviation(4) < Ranking.INITIAL_DEVIATION)
        assertTrue(Ranking.priorDeviation(10) < Ranking.priorDeviation(4))
        assertEquals(Ranking.MIN_PRIOR_DEVIATION, Ranking.priorDeviation(500), 0.0)
    }

    @Test
    fun starConfidenceIsHighForAClearRankingAndLowWithoutDuels() {
        assertTrue(Ranking.starConfidence(emptyMap()).isEmpty())

        val clear = (1L..20L).associateWith { FilmRating(rating = 1000.0 + it * 60, deviation = 20.0, duels = 30) }
        assertTrue(Ranking.starConfidence(clear).values.all { it > 0.95 })

        // Aucun duel : tous ex aequo à 5 étoiles, alors qu'un vrai classement les répartirait.
        val unknown = (1L..20L).associateWith { FilmRating(rating = 1500.0, deviation = 350.0, duels = 0) }
        val confidence = Ranking.starConfidence(unknown)
        assertTrue(confidence.values.average() < 0.3)
        // Tirages fixés : même entrée, même résultat.
        assertEquals(confidence, Ranking.starConfidence(unknown))
    }

    /** La confiance annoncée correspond à la réalité, sur un classement simulé. */
    @Test
    fun starConfidenceIsCalibratedOnASimulatedUser() {
        val random = Random(12)
        val truth = (1L..120L).associateWith { random.nextDouble(-3.0, 3.0) }
        val duels = (1..480).map {
            val a = random.nextLong(1, 121)
            var b = random.nextLong(1, 121)
            while (b == a) b = random.nextLong(1, 121)
            val aWins = random.nextDouble() < Ranking.sigmoid(truth.getValue(a) - truth.getValue(b))
            if (aWins) DuelOutcome(a, b) else DuelOutcome(b, a)
        }
        val ratings = Ranking.compute(truth.keys.map { RankingPrior(it) }, duels)
        val confidence = Ranking.starConfidence(ratings)

        val ids = truth.keys.toList()
        val shown = StarScale.computeBuckets(DoubleArray(ids.size) { ratings.getValue(ids[it]).rating })
        val real = StarScale.computeBuckets(DoubleArray(ids.size) { truth.getValue(ids[it]) })
        val actual = ids.indices.count { kotlin.math.abs(shown[it] - real[it]) <= 1 }.toDouble() / ids.size
        val predicted = confidence.values.average()
        assertEquals(actual, predicted, 0.12)
    }
}
