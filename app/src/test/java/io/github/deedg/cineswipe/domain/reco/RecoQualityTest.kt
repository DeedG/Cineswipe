package io.github.deedg.cineswipe.domain.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoQualityTest {
    @Test
    fun nothingToSayWithoutAnyFilm() {
        assertNull(RecoQualities.of(series = false, watched = 0, reliability = 0.0))
    }

    @Test
    fun theWeakestLinkIsTheAdvice() {
        // Peu de films, classement déjà sûr : recenser.
        val few = RecoQualities.of(series = false, watched = 20, reliability = 0.8)!!
        assertEquals(RecoQualityAdvice.CENSUS, few.advice)
        assertEquals(RecoQualityLevel.FAIR, few.level)
        assertEquals(80, few.reliability)

        // Beaucoup de films, classement incertain : des duels.
        val unsure = RecoQualities.of(series = false, watched = 300, reliability = 0.4)!!
        assertEquals(RecoQualityAdvice.DUELS, unsure.advice)
        assertEquals(RecoQualityLevel.GOOD, unsure.level)

        // Tout début.
        assertEquals(RecoQualityLevel.STARTING, RecoQualities.of(false, 5, 0.3)!!.level)

        // Assez de films et classement fiable : plus rien à conseiller.
        val sharp = RecoQualities.of(series = false, watched = 450, reliability = 0.93)!!
        assertEquals(RecoQualityAdvice.NONE, sharp.advice)
        assertEquals(RecoQualityLevel.SHARP, sharp.level)
    }

    @Test
    fun seriesNeedFewerTitles() {
        assertEquals(RecoQualityAdvice.DUELS, RecoQualities.of(series = true, watched = 30, reliability = 0.5)!!.advice)
        assertEquals(RecoQualityAdvice.CENSUS, RecoQualities.of(series = false, watched = 30, reliability = 0.5)!!.advice)
    }
}
