package io.github.deedg.cineswipe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SeasonAlertsTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun check(known: Int?, last: Int?, next: Int? = null, episode: Int? = null, date: LocalDate? = null, sent: Set<String> = emptySet()) =
        SeasonAlerts.check(1, "Show", known, last, next, episode, date, today, sent)

    @Test
    fun theFirstPassOnlyTakesTheReference() {
        assertNull(check(known = null, last = 5))
        assertEquals(5, SeasonAlerts.nextKnown(null, 5, 3))
        assertEquals(3, SeasonAlerts.nextKnown(null, null, 3))
    }

    @Test
    fun aSeasonOutOrAnnouncedSoonIsNotified() {
        assertEquals(SeasonAlert.Kind.OUT, check(known = 3, last = 4)?.kind)
        val soon = check(known = 4, last = 4, next = 5, episode = 1, date = today.plusDays(10))
        assertEquals(SeasonAlert.Kind.ANNOUNCED, soon?.kind)
        assertEquals(5, soon?.season)
        // Trop loin, ou en cours de saison : rien.
        assertNull(check(known = 4, last = 4, next = 5, episode = 1, date = today.plusDays(90)))
        assertNull(check(known = 4, last = 4, next = 4, episode = 6, date = today.plusDays(3)))
        // Déjà envoyée : rien.
        assertNull(check(known = 3, last = 4, sent = setOf("1:4:OUT")))
        // La référence ne recule jamais.
        assertEquals(4, SeasonAlerts.nextKnown(4, 3, 3))
    }
}
