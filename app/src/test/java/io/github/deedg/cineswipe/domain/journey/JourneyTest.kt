package io.github.deedg.cineswipe.domain.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyTest {
    @Test
    fun theStepsComeInOrder() {
        // Rien de fait : le recensement d'abord, les recos attendent.
        val start = Journey.state(series = false, watched = 0, duels = 0, skipped = false)
        assertEquals(JourneyStep.CENSUS, start.step)
        assertFalse(start.recosOpen)
        assertEquals(0f, start.censusProgress)

        // Des duels sans assez de films vus ne font pas sauter l'étape 1.
        assertEquals(JourneyStep.CENSUS, Journey.state(false, Journey.FILMS_WATCHED - 1, 500, false).step)

        // Objectif de films atteint : place aux duels.
        val arena = Journey.state(false, Journey.FILMS_WATCHED, 5, false)
        assertEquals(JourneyStep.ARENA, arena.step)
        assertTrue(arena.censusDone)
        assertFalse(arena.recosOpen)
        assertEquals(5f / Journey.FILMS_DUELS, arena.arenaProgress)

        // Les deux objectifs atteints : les recos s'ouvrent.
        val done = Journey.state(false, 200, Journey.FILMS_DUELS, false)
        assertEquals(JourneyStep.RECOS, done.step)
        assertTrue(done.recosOpen)
    }

    @Test
    fun skippingOpensTheRecosButKeepsTheStep() {
        val skipped = Journey.state(series = false, watched = 3, duels = 0, skipped = true)
        assertTrue(skipped.recosOpen)
        assertEquals(JourneyStep.CENSUS, skipped.step)
    }

    @Test
    fun seriesHaveSmallerGoals() {
        val films = Journey.state(series = false, watched = Journey.SERIES_WATCHED, duels = Journey.SERIES_DUELS, skipped = false)
        val series = Journey.state(series = true, watched = Journey.SERIES_WATCHED, duels = Journey.SERIES_DUELS, skipped = false)
        assertEquals(JourneyStep.CENSUS, films.step)
        assertEquals(JourneyStep.RECOS, series.step)
        assertTrue(JourneyState.DONE.recosOpen)
    }
}
