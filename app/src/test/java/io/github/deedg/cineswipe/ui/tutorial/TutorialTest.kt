package io.github.deedg.cineswipe.ui.tutorial

import io.github.deedg.cineswipe.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialTest {

    @Test
    fun hopsFollowWhatAUserWouldTouch() {
        assertNull(tutorialHop(Route.Arena, Route.Arena))
        // D'un onglet à l'autre : l'onglet.
        assertEquals(TutorialHop(TutorialTarget.TAB_ARENA, Route.Arena), tutorialHop(Route.Recos, Route.Arena))
        // D'un écran sans barre du bas : le retour d'abord.
        assertEquals(TutorialHop(TutorialTarget.BACK, null), tutorialHop(Route.Census, Route.Search))
        assertEquals(TutorialHop(TutorialTarget.BACK, null), tutorialHop(Route.MovieDetail(1), Route.Recos))
        // Le recensement s'ouvre depuis la Découverte, en passant par son onglet.
        assertEquals(TutorialHop(TutorialTarget.TAB_DISCOVER, Route.Discover), tutorialHop(Route.Profile, Route.Census))
        assertEquals(TutorialHop(TutorialTarget.DISCOVER_CENSUS, Route.Census), tutorialHop(Route.Discover, Route.Census))
        // Un écran empilé avec la barre du bas : l'onglet suffit.
        assertEquals(TutorialHop(TutorialTarget.TAB_SEARCH, Route.Search), tutorialHop(Route.Stats, Route.Search))
    }

    @Test
    fun showMePlaysTheStepGestureThenTheScreenActs() {
        val tutorial = Tutorial()
        tutorial.start()
        tutorial.onRoute(Route.Recos)
        tutorial.next()
        tutorial.next()
        val step = tutorial.step!!
        assertEquals(TutorialEvent.RECO_WATCHED, step.waitFor)

        tutorial.demonstrate()
        val play = tutorial.play!!
        assertFalse(play.travel)
        assertEquals(TutorialGesture.SWIPE_LEFT, play.gesture)
        // Rien n'est demandé avant que le doigt n'agisse ; une seule fois ensuite.
        assertNull(tutorial.request)
        tutorial.act(play)
        val request = tutorial.request!!
        assertEquals(TutorialEvent.RECO_WATCHED, request.event)
        assertTrue(tutorial.take(request))
        assertFalse(tutorial.take(request))
        tutorial.event(TutorialEvent.RECO_WATCHED)
        assertEquals(3, tutorial.stepIndex)
        tutorial.finish(play)
        assertNull(tutorial.play)
    }

    @Test
    fun aStepOnAnotherScreenIsAwayUntilTheTravelEnds() {
        val tutorial = Tutorial()
        tutorial.start()
        tutorial.onRoute(Route.Recos)
        while (tutorial.step?.route != Route.Arena) tutorial.next()
        assertTrue(tutorial.away)
        tutorial.travel(TutorialHop(TutorialTarget.TAB_ARENA, Route.Arena))
        val play = tutorial.play!!
        assertTrue(play.travel)
        tutorial.act(play)
        assertEquals(Route.Arena, tutorial.request?.hop?.route)
        tutorial.onRoute(Route.Arena)
        assertFalse(tutorial.away)
        // Fermer le tuto oublie le geste en cours : il ne reste pas bloqué à la relance.
        tutorial.stop()
        assertNull(tutorial.play)
        assertNull(tutorial.request)
    }

    @Test
    fun theAutoVisitStopsOnTheLastBubble() {
        val tutorial = Tutorial()
        tutorial.startAutoplay()
        assertTrue(tutorial.autoplay)
        assertEquals(0, tutorial.stepIndex)
        while (tutorial.stepIndex < tutorial.steps.lastIndex) tutorial.next()
        assertFalse(tutorial.autoplay)
        // Relancer le tuto repart en manuel.
        tutorial.startAutoplay()
        tutorial.start()
        assertFalse(tutorial.autoplay)
        // Le temps de lecture dépend de la bulle ; une étape à geste montre la consigne 2,5 s.
        val explain = tutorial.steps.first { !it.interactive }
        assertEquals(3_000L, autoDuration(explain, 10))
        assertEquals(9_000L, autoDuration(explain, 1_000))
        assertEquals(2_500L, autoDuration(tutorial.steps.first { it.interactive }, 1_000))
    }
}
