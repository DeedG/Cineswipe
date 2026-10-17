package io.github.deedg.cineswipe.domain.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cycle des touchers du recensement : films (vu, adoré, bof) et séries (avancement). */
class CensusChoiceTest {

    @Test
    fun filmsCycleThroughImpressions() {
        val cycle = generateSequence(CensusChoice.NONE.next()) { it.next().takeIf { next -> next != CensusChoice.NONE } }.toList()
        assertEquals(listOf(CensusChoice.SEEN, CensusChoice.LOVED, CensusChoice.DISLIKED), cycle)
    }

    @Test
    fun seriesCycleThroughProgress() {
        val cycle = generateSequence(CensusChoice.NONE.next(series = true)) { it.next(series = true).takeIf { next -> next != CensusChoice.NONE } }.toList()
        assertEquals(listOf(CensusChoice.FINISHED, CensusChoice.IN_PROGRESS, CensusChoice.EPISODES, CensusChoice.ABANDONED), cycle)
        assertTrue(cycle.all { it.seen })
        // À voir ou pas pour moi : un toucher efface.
        assertEquals(CensusChoice.NONE, CensusChoice.WATCHLIST.next(series = true))
    }
}
