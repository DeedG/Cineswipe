package io.github.deedg.cineswipe.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.library.LibraryFilter
import kotlinx.serialization.Serializable

/** Destinations de l'appli (Navigation 3). */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Recos : Route
    @Serializable data object Arena : Route
    @Serializable data object Search : Route
    @Serializable data object Discover : Route
    @Serializable data object Profile : Route

    /** Bibliothèque, ouverte sur un filtre (depuis les compteurs du profil). */
    @Serializable data class Library(val filter: LibraryFilter = LibraryFilter.ALL) : Route

    /** Historique des duels de l'Arène. */
    @Serializable data object DuelHistory : Route

    /** Recensement des films vus (grille ou cartes). */
    @Serializable data object Census : Route

    /** Outils de diagnostic (cachés : sept touchers sur la version, dans les réglages). */
    @Serializable data object Diagnostics : Route

    /** Guide de la clé TMDB (créer, copier, coller). */
    @Serializable data object TmdbKey : Route

    /** Import des films vus depuis CineSwipe, Letterboxd, Trakt, IMDb… */
    @Serializable data object Import : Route
    @Serializable data object Settings : Route
    /** Fiche ; [siblings] : fiches voisines (bibliothèque), parcourues d'un swipe horizontal. */
    @Serializable data class MovieDetail(val tmdbId: Long, val siblings: List<Long> = emptyList()) : Route

    /** Fiche d'un acteur ou d'un réalisateur, avec ses films. */
    @Serializable data class Person(val id: Long, val name: String) : Route

    /** Rétro de l'année : films, top, genres, à partager en image. */
    @Serializable data object Retro : Route

    /** Une liste perso et ses films. */
    @Serializable data class UserList(val id: Long) : Route

    /** Arène des saisons (mode séries). */
    @Serializable data object SeasonArena : Route

    /** Comparer ses goûts avec un ami (QR code) ; [code] : code reçu par un partage. */
    @Serializable data class Friends(val code: String? = null) : Route

    /** Stats de goûts (genres, réalisateurs, acteurs, décennies…). */
    @Serializable data object Stats : Route

    /** « Ce soir » : les films à portée de main, triés selon les goûts. */
    @Serializable data object Tonight : Route

    /** Film partagé depuis une autre appli (lien TMDB, IMDb, Letterboxd… ou titre). */
    @Serializable data class Shared(val text: String) : Route

    /** ⚡ Éclair : recos à partir d'un film, dans l'onglet Recos. */
    @Serializable data class Lightning(val tmdbId: Long) : Route

    /** Mode homelab : services, synchronisation et file d'attente des demandes. */
    @Serializable data object Homelab : Route

    /** Réglages d'un service du homelab (adresse, clé, choix propres au service). */
    @Serializable data class HomelabServiceSettings(val service: HomelabService) : Route
}

/**
 * Onglets de la barre du bas, dans l'ordre du parcours : on recense, on note en duels, puis les
 * recos (au centre, mises en avant). La Découverte s'ouvre depuis les recos.
 */
enum class Tab(val route: Route, @param:StringRes val label: Int, val icon: () -> ImageVector) {
    CENSUS(Route.Census, R.string.tab_census, { CineIcons.Grid }),
    ARENA(Route.Arena, R.string.tab_arena, { CineIcons.Arena }),
    RECOS(Route.Recos, R.string.tab_recos, { CineIcons.Recos }),
    SEARCH(Route.Search, R.string.tab_search, { CineIcons.Search }),
    PROFILE(Route.Profile, R.string.tab_profile, { CineIcons.Profile }),
}

/** Onglet de départ : les Recos (ou le parcours qui y mène, tant qu'il n'est pas fait). */
val StartTab: Tab = Tab.RECOS
val StartRoute: Route = StartTab.route

/**
 * Écrans « plein écran », sans barre du bas : la fiche d'un film, et les réglages avec tout ce qui
 * s'ouvre depuis eux. Ils ont un bouton retour ; les autres écrans restent dans leur onglet.
 */
val Route.showsBottomBar: Boolean
    get() = when (this) {
        is Route.MovieDetail, Route.TmdbKey, Route.Settings, Route.Homelab, is Route.HomelabServiceSettings,
        Route.Diagnostics, Route.Import, is Route.Shared,
        -> false
        else -> true
    }

/**
 * Navigation par onglets, une pile par onglet : changer d'onglet garde où on en était ; toucher
 * l'onglet ouvert ramène à sa racine. La pile affichée ([backStack]) est celle de l'onglet ouvert.
 */
class Navigator(private val backStack: NavBackStack<NavKey>, initialTab: Tab) {

    /** Onglet ouvert : celui qui s'allume dans la barre du bas, d'où qu'on vienne. */
    var tab: Tab by mutableStateOf(initialTab)
        private set

    /** Piles des autres onglets, gardées pendant qu'on est ailleurs. */
    private val stacks = mutableMapOf<Tab, List<NavKey>>()

    val current: Route?
        get() = backStack.lastOrNull() as? Route

    /** À la racine d'un onglet (rien à dépiler). */
    val atRoot: Boolean
        get() = backStack.size <= 1

    /** Barre du bas : l'onglet touché, là où on l'avait laissé ; l'onglet ouvert, à sa racine. */
    fun switchTab(target: Tab) {
        if (target == tab) {
            replaceWith(listOf(target.route))
            return
        }
        stacks[tab] = backStack.toList()
        tab = target
        replaceWith(stacks.remove(target) ?: listOf(target.route))
    }

    /** Aller à un onglet depuis un bouton (« Passer aux duels »…) : toujours à sa racine. */
    fun goTo(target: Tab) {
        if (target != tab) stacks[tab] = backStack.toList()
        stacks.remove(target)
        tab = target
        replaceWith(listOf(target.route))
    }

    fun open(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /** Retour : dépile ; à la racine d'un onglet, revient aux Recos (l'onglet de départ). */
    fun back() {
        when {
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
            tab != StartTab -> switchTab(StartTab)
        }
    }

    private fun replaceWith(target: List<NavKey>) {
        if (backStack.toList() == target) return
        val old = backStack.size
        backStack.addAll(target)
        repeat(old) { backStack.removeAt(0) }
    }
}
