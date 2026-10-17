package io.github.deedg.cineswipe.ui.navigation

import androidx.annotation.StringRes
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

/** Onglets de la barre du bas, dans l'ordre de la V4. */
enum class Tab(val route: Route, @param:StringRes val label: Int, val icon: () -> ImageVector) {
    RECOS(Route.Recos, R.string.tab_recos, { CineIcons.Recos }),
    ARENA(Route.Arena, R.string.tab_arena, { CineIcons.Arena }),
    SEARCH(Route.Search, R.string.tab_search, { CineIcons.Search }),
    DISCOVER(Route.Discover, R.string.tab_discover, { CineIcons.Discover }),
    PROFILE(Route.Profile, R.string.tab_profile, { CineIcons.Profile }),
}

/** Onglet de départ : les Recos, comme en V4. */
val StartRoute: Route = Route.Recos

val Route.tab: Tab
    get() = when (this) {
        Route.Recos, is Route.Lightning, Route.Tonight -> Tab.RECOS
        Route.Arena, Route.SeasonArena -> Tab.ARENA
        Route.Search, is Route.Shared -> Tab.SEARCH
        Route.Discover, Route.Census -> Tab.DISCOVER
        Route.Profile, is Route.Library, Route.DuelHistory, Route.Settings, Route.Homelab, is Route.HomelabServiceSettings,
        is Route.MovieDetail, Route.Diagnostics, Route.TmdbKey, Route.Import, is Route.Person, Route.Stats,
        is Route.UserList, Route.Retro, is Route.Friends,
        -> Tab.PROFILE
    }

val Route.showsBottomBar: Boolean
    get() = this !is Route.MovieDetail && this != Route.Census && this != Route.TmdbKey

/** Manipulations de la pile de navigation. Elle n'est jamais vidée complètement. */
class Navigator(private val backStack: NavBackStack<NavKey>) {

    val current: Route?
        get() = backStack.lastOrNull() as? Route

    /** Onglet : la pile devient [départ, onglet], pour que « retour » ramène au départ. */
    fun switchTab(tab: Tab) {
        val target: List<NavKey> = if (tab.route == StartRoute) listOf(StartRoute) else listOf(StartRoute, tab.route)
        if (backStack.toList() == target) return
        backStack.addAll(target)
        repeat(backStack.size - target.size) { backStack.removeAt(0) }
    }

    fun open(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
