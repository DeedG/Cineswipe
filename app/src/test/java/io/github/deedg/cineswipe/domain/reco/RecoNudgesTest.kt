package io.github.deedg.cineswipe.domain.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoNudgesTest {
    private val now = 1_000_000L

    @Test
    fun aNewProfileIsInvitedToTheCensusFirst() {
        assertEquals(RecoNudge.CENSUS, RecoNudges.pick(false, RecoStrength(12, 0, 0.1), emptyMap(), now))
        // Recensement fait : place aux duels.
        assertEquals(RecoNudge.DUELS, RecoNudges.pick(false, RecoStrength(80, 300, 0.2), emptyMap(), now))
        // Beaucoup de films vus et un classement fiable : rien à dire.
        assertNull(RecoNudges.pick(false, RecoStrength(452, 500, 0.63), emptyMap(), now))
    }

    @Test
    fun seriesNeedFewerTitles() {
        assertEquals(RecoNudge.CENSUS, RecoNudges.pick(true, RecoStrength(10, 0, 0.0), emptyMap(), now))
        assertNull(RecoNudges.pick(true, RecoStrength(40, 30, 0.7), emptyMap(), now))
        assertEquals(RecoNudge.DUELS, RecoNudges.pick(true, RecoStrength(40, 30, 0.3), emptyMap(), now))
    }

    @Test
    fun aRefusedInvitationNeverComesBack() {
        val refused = mapOf(RecoNudge.CENSUS to RecoNudges.NEVER)
        // Trop peu de films pour des duels : plus rien.
        assertNull(RecoNudges.pick(false, RecoStrength(3, 0, 0.0), refused, now))
        // Assez pour des duels : l'autre invitation reste possible.
        assertEquals(RecoNudge.DUELS, RecoNudges.pick(false, RecoStrength(20, 0, 0.1), refused, now))
        // Suivie il y a peu : en pause, puis de retour.
        val snoozed = mapOf(RecoNudge.CENSUS to now + RecoNudges.SNOOZE_MS)
        assertNull(RecoNudges.pick(false, RecoStrength(3, 5, 0.0), snoozed, now))
        assertEquals(RecoNudge.CENSUS, RecoNudges.pick(false, RecoStrength(3, 5, 0.0), snoozed, now + RecoNudges.SNOOZE_MS + 1))
    }

    @Test
    fun hiddenInvitationsSurviveASave() {
        val hidden = mapOf(RecoNudge.CENSUS to RecoNudges.NEVER, RecoNudge.DUELS to 42L)
        assertEquals(hidden, RecoNudges.decode(RecoNudges.encode(hidden)))
        assertEquals(emptyMap<RecoNudge, Long>(), RecoNudges.decode(null))
        assertEquals(mapOf(RecoNudge.DUELS to 7L), RecoNudges.decode("BOGUS=1;DUELS=7;x"))
    }
}
