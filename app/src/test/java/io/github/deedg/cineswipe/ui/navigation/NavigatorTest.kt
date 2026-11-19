package io.github.deedg.cineswipe.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.deedg.cineswipe.ui.library.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val stack = NavBackStack<NavKey>(Route.Recos)
    private val navigator = Navigator(stack, Tab.RECOS)

    @Test
    fun eachTabKeepsItsOwnStack() {
        navigator.switchTab(Tab.PROFILE)
        navigator.open(Route.Library(LibraryFilter.WATCHED))
        navigator.open(Route.MovieDetail(42))

        // Un saut aux Recos, puis retour au Profil : on retrouve la fiche, et l'onglet Profil allumé.
        navigator.switchTab(Tab.RECOS)
        assertEquals(listOf<NavKey>(Route.Recos), stack.toList())
        navigator.switchTab(Tab.PROFILE)
        assertEquals(Tab.PROFILE, navigator.tab)
        assertEquals(Route.MovieDetail(42), navigator.current)

        // Toucher l'onglet ouvert ramène à sa racine.
        navigator.switchTab(Tab.PROFILE)
        assertEquals(listOf<NavKey>(Route.Profile), stack.toList())
    }

    @Test
    fun theLitTabIsWhereYouCameFrom() {
        // Une fiche ouverte depuis la Recherche laisse la Recherche allumée.
        navigator.switchTab(Tab.SEARCH)
        navigator.open(Route.MovieDetail(7))
        assertEquals(Tab.SEARCH, navigator.tab)
        assertFalse(navigator.current!!.showsBottomBar)
    }

    @Test
    fun backPopsThenReturnsToTheRecos() {
        navigator.switchTab(Tab.ARENA)
        navigator.open(Route.SeasonArena)
        assertFalse(navigator.atRoot)
        navigator.back()
        assertEquals(Route.Arena, navigator.current)
        assertTrue(navigator.atRoot)
        navigator.back()
        assertEquals(Tab.RECOS, navigator.tab)
        assertEquals(Route.Recos, navigator.current)
    }

    @Test
    fun goToAlwaysLandsOnTheRoot() {
        navigator.switchTab(Tab.CENSUS)
        navigator.open(Route.MovieDetail(3))
        navigator.switchTab(Tab.RECOS)
        // « Recenser mes films » depuis le parcours : le recensement, pas la fiche laissée ouverte.
        navigator.goTo(Tab.CENSUS)
        assertEquals(listOf<NavKey>(Route.Census), stack.toList())
    }
}
