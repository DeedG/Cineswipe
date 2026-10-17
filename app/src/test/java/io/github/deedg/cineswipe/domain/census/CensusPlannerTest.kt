package io.github.deedg.cineswipe.domain.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CensusPlannerTest {

    /** Même année, même langue : seul le nombre de votes départage (le film 1 est le plus vu). */
    private fun candidate(id: Long, votes: Int) =
        CensusCandidate(id, CensusFeatures(voteCount = votes, year = 2010, language = "en"))

    @Test
    fun aPageHoldsTheLikeliestFilmsAndSomeExploration() {
        val candidates = (1L..500L).map { candidate(it, votes = (600 - it.toInt()) * 50) }

        val page = CensusPlanner(Random(7)).page(candidates.shuffled(Random(3)), CensusModel(), size = 12)

        assertEquals(12, page.size)
        assertEquals(12, page.toSet().size)
        // 12 − ⌈12 × 20 %⌉ = 9 films parmi les plus probables, puis 3 tirés parmi les 300 suivants.
        assertTrue(page.containsAll((1L..9L).toList()))
        assertEquals(3, page.count { it > 9 })
        assertTrue(page.all { it <= 309 })
        // L'ordre de la page est mélangé : les plus probables ne sont pas tous en tête.
        assertFalse(page.take(9).toSet() == (1L..9L).toSet())
    }

    @Test
    fun aSmallCatalogueIsShownWhole() {
        val candidates = (1L..5L).map { candidate(it, 1_000) }
        assertEquals((1L..5L).toSet(), CensusPlanner(Random(1)).page(candidates, CensusModel(), size = 12).toSet())
        assertTrue(CensusPlanner().page(emptyList(), CensusModel(), size = 12).isEmpty())
        assertTrue(CensusPlanner().page(candidates, CensusModel(), size = 0).isEmpty())
    }

    @Test
    fun aTapCyclesThroughSeenLovedDislikedAndBack() {
        assertEquals(CensusChoice.SEEN, CensusChoice.NONE.next())
        assertEquals(CensusChoice.LOVED, CensusChoice.SEEN.next())
        assertEquals(CensusChoice.DISLIKED, CensusChoice.LOVED.next())
        assertEquals(CensusChoice.NONE, CensusChoice.DISLIKED.next())
        assertEquals(CensusChoice.NONE, CensusChoice.WATCHLIST.next())
        assertEquals(CensusChoice.NONE, CensusChoice.DISMISSED.next())
        // « Vu » : les trois réponses des films et les quatre avancements des séries.
        assertEquals(
            setOf(
                CensusChoice.SEEN, CensusChoice.LOVED, CensusChoice.DISLIKED,
                CensusChoice.FINISHED, CensusChoice.IN_PROGRESS, CensusChoice.EPISODES, CensusChoice.ABANDONED,
            ),
            CensusChoice.entries.filter { it.seen }.toSet(),
        )
    }
}
