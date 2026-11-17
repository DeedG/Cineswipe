package io.github.deedg.cineswipe.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.WatchedNotifier
import io.github.deedg.cineswipe.domain.taste.TasteCode
import io.github.deedg.cineswipe.ui.arena.ArenaRoute
import io.github.deedg.cineswipe.ui.census.CensusRoute
import io.github.deedg.cineswipe.ui.common.SeriesContext
import io.github.deedg.cineswipe.ui.components.CineToast
import io.github.deedg.cineswipe.ui.components.LocalOpenPerson
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PersonRef
import io.github.deedg.cineswipe.ui.components.Toaster
import io.github.deedg.cineswipe.ui.demo.DemoRestartEffect
import io.github.deedg.cineswipe.ui.demo.DemoViewModel
import io.github.deedg.cineswipe.ui.demo.demoViewModel
import io.github.deedg.cineswipe.ui.detail.MovieDetailPager
import io.github.deedg.cineswipe.ui.detail.MovieDetailRoute
import io.github.deedg.cineswipe.ui.discover.DiscoverRoute
import io.github.deedg.cineswipe.ui.friends.FriendsRoute
import io.github.deedg.cineswipe.ui.homelab.HomelabRoute
import io.github.deedg.cineswipe.ui.homelab.ServiceSettingsRoute
import io.github.deedg.cineswipe.ui.homelab.eventMessage
import io.github.deedg.cineswipe.ui.importer.ImportRoute
import io.github.deedg.cineswipe.ui.library.LibraryFilter
import io.github.deedg.cineswipe.ui.library.LibraryRoute
import io.github.deedg.cineswipe.ui.lists.UserListRoute
import io.github.deedg.cineswipe.ui.person.PersonRoute
import io.github.deedg.cineswipe.ui.profile.DuelHistoryRoute
import io.github.deedg.cineswipe.ui.profile.ProfileRoute
import io.github.deedg.cineswipe.ui.recos.LightningRoute
import io.github.deedg.cineswipe.ui.recos.RecosRoute
import io.github.deedg.cineswipe.ui.retro.RetroRoute
import io.github.deedg.cineswipe.ui.search.SearchRoute
import io.github.deedg.cineswipe.ui.seasons.SeasonArenaRoute
import io.github.deedg.cineswipe.ui.settings.DiagnosticsRoute
import io.github.deedg.cineswipe.ui.settings.SettingsRoute
import io.github.deedg.cineswipe.ui.settings.TmdbKeyRoute
import io.github.deedg.cineswipe.ui.share.SharedRoute
import io.github.deedg.cineswipe.ui.stats.StatsRoute
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tonight.TonightRoute
import io.github.deedg.cineswipe.ui.welcome.WelcomeScreen
import io.github.deedg.cineswipe.widget.WidgetLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Racine de l'appli : l'accueil au tout premier lancement (les trois étapes du parcours), sinon la
 * navigation. Pendant la démo (ouverte depuis les réglages), un bandeau rappelle qu'on est dans un
 * bac à sable.
 */
@Composable
fun CineSwipeRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as CineSwipeApp
    // Monde des séries : les textes parlent de séries (variantes « _series » des chaînes).
    if (app.container.kind.isSeries) {
        val seriesContext = remember(context) { SeriesContext(context) }
        CompositionLocalProvider(LocalContext provides seriesContext, LocalResources provides seriesContext.resources) {
            RootScreen(app)
        }
    } else {
        RootScreen(app)
    }
}

@Composable
private fun RootScreen(app: CineSwipeApp) {
    val container = app.container
    val demo = demoViewModel()
    DemoRestartEffect(demo)
    val scope = rememberCoroutineScope()
    var welcome by remember(container) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(container) { welcome = shouldWelcome(container) }
    // Écrans larges (pliant ouvert, tablette, fenêtre en multi-fenêtre) : l'appli garde sa mise en
    // page de téléphone, centrée, au lieu de s'étirer (Android 16 ignore le blocage en portrait
    // sur les grands écrans).
    Box(Modifier.fillMaxSize().background(CineColors.Black), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = MAX_APP_WIDTH).fillMaxWidth()) {
            RootContent(welcome, container, app, demo, onStart = { import ->
                scope.launch {
                    container.settings.setOnboardingDone(true)
                    app.keyGuidePending = true
                    // Le parcours commence par le recensement ; avec une liste déjà prête, par l'import.
                    app.pendingTab = Tab.CENSUS.name
                    app.importPending = import
                    welcome = false
                }
            })
        }
    }
}

/** Largeur maximale de l'appli : au-delà, elle est centrée. */
private val MAX_APP_WIDTH = 600.dp

@Composable
private fun RootContent(
    welcome: Boolean?,
    container: AppContainer,
    app: CineSwipeApp,
    demo: DemoViewModel,
    onStart: (import: Boolean) -> Unit,
) {
    when (welcome) {
        null -> Box(Modifier.fillMaxSize().background(CineColors.Black))
        true -> WelcomeScreen(onStart = { onStart(false) }, onImport = { onStart(true) })
        false -> MainContent(
            container = container,
            onExitDemo = demo::stop,
            keyGuidePending = { app.keyGuidePending },
            onKeyGuideDone = { app.keyGuidePending = false },
            takeImportPending = { app.importPending.also { app.importPending = false } },
            sharedInbox = app.sharedInbox,
            openInbox = app.openInbox,
        )
    }
}

/**
 * Accueil au premier lancement seulement : pas pendant la démo, ni pour qui a déjà une clé TMDB
 * ou des films (mise à jour depuis une version sans accueil). Le mode séries a le sien, à la
 * première arrivée, même avec des séries déjà notées (il présente la démo des séries).
 */
private suspend fun shouldWelcome(container: AppContainer): Boolean {
    if (container.isDemo) return false
    val settings = container.settings
    if (settings.onboardingDone.first()) return false
    if (container.kind.isSeries) return true
    val existing = settings.userTmdbApiKey.first() != null || container.database.userMovieDao().watchedCount() > 0
    if (existing) settings.setOnboardingDone(true)
    return !existing
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainContent(
    container: AppContainer,
    onExitDemo: () -> Unit,
    keyGuidePending: () -> Boolean,
    onKeyGuideDone: () -> Unit,
    takeImportPending: () -> Boolean,
    sharedInbox: MutableStateFlow<String?>,
    openInbox: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    // Après le switch Films | Séries, l'écran repart sur l'onglet d'où on l'a touché.
    val application = app()
    val startTab = remember {
        val name = application.takePendingTab()
        Tab.entries.firstOrNull { it.name == name }
    }
    val backStack = if (startTab == null || startTab.route == StartRoute) rememberNavBackStack(StartRoute) else rememberNavBackStack(StartRoute, startTab.route)
    val navigator = remember(backStack) { Navigator(backStack) }
    val current = navigator.current

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toaster = remember(snackbarHostState, scope) { Toaster(snackbarHostState, scope) }

    val openSettings = { navigator.open(Route.Settings) }
    val openMovie = { tmdbId: Long -> navigator.open(Route.MovieDetail(tmdbId)) }
    val openLightning = { tmdbId: Long -> navigator.open(Route.Lightning(tmdbId)) }
    val openCensus = { navigator.switchTab(Tab.CENSUS) }
    val openArena = { navigator.switchTab(Tab.ARENA) }
    val openRecos = { navigator.switchTab(Tab.RECOS) }
    val openKeyGuide = { navigator.open(Route.TmdbKey) }
    val openTonight = {
        navigator.switchTab(Tab.RECOS)
        navigator.open(Route.Tonight)
    }

    // Sans clé TMDB (la seule chose indispensable), le guide s'ouvre d'emblée après l'accueil
    // ou en quittant la démo ; ensuite, chaque écran qui en a besoin y mène.
    // Le guide reste dû tant qu'il n'a pas été fermé (l'écran peut être relancé entre-temps).
    LaunchedEffect(container) {
        if (container.isDemo || !keyGuidePending()) return@LaunchedEffect
        if (container.settings.tmdbApiKey.first() != null) onKeyGuideDone() else if (navigator.current != Route.TmdbKey) navigator.open(Route.TmdbKey)
    }

    // « J'ai déjà une liste », à l'accueil : l'import s'ouvre d'emblée (le retour mène au recensement).
    LaunchedEffect(container) {
        if (!container.isDemo && takeImportPending()) navigator.open(Route.Import)
    }

    // Film partagé depuis une autre appli : sa fiche (ou le choix entre plusieurs films).
    // Seul l'écran visible lit ces demandes : une ancienne instance en arrière-plan ne doit pas les prendre.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sharedInbox, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sharedInbox.filterNotNull().collect { text ->
                sharedInbox.value = null
                // Code de goûts d'un ami : la comparaison ; sinon, un film partagé.
                navigator.open(if (text.contains(TasteCode.PREFIX)) Route.Friends(text) else Route.Shared(text))
            }
        }
    }

    // Écran demandé par un widget.
    LaunchedEffect(openInbox, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        openInbox.filterNotNull().collect { target ->
            openInbox.value = null
            when {
                target == WidgetLinks.TONIGHT -> openTonight()
                target == WidgetLinks.STATS -> {
                    navigator.switchTab(Tab.PROFILE)
                    navigator.open(Route.Stats)
                }
                target == WidgetLinks.TOP -> navigator.switchTab(Tab.PROFILE)
                target.startsWith("movie:") -> target.removePrefix("movie:").toLongOrNull()?.let(openMovie)
                // Notification après un film vu sur le serveur : l'Arène le place en priorité.
                WatchedNotifier.parseRankLink(target) != null -> {
                    container.arena.focusOn(WatchedNotifier.parseRankLink(target).orEmpty())
                    navigator.switchTab(Tab.ARENA)
                }
            }
        }
        }
    }

    // La barre du bas s'efface quand le clavier est ouvert (saisie de la recherche).
    val imeVisible = WindowInsets.isImeVisible

    // Suites des demandes au homelab (déjà demandé, service injoignable, refus), où qu'on soit.
    val homelab = container.homelab
    val resources = LocalResources.current
    LaunchedEffect(homelab, toaster) {
        homelab.events.collect { event -> toaster.show(eventMessage(resources, event)) }
    }


    val openPerson = remember(navigator) { { person: PersonRef -> navigator.open(Route.Person(person.id, person.name)) } }
    CompositionLocalProvider(LocalToaster provides toaster, LocalOpenPerson provides openPerson) {
        Column(Modifier.fillMaxSize().background(CineColors.Black)) {
            if (container.isDemo) DemoStrip(onExit = onExitDemo)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Le bandeau de la démo occupe déjà la place de la barre d'état.
                    .then(if (container.isDemo) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                Scaffold(
                    containerColor = CineColors.Black,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (current?.showsBottomBar != false && !imeVisible) {
                            CineBottomBar(selected = current?.tab ?: StartRoute.tab, onSelect = navigator::switchTab)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                        NavDisplay(
                            backStack = backStack,
                            modifier = Modifier.fillMaxSize(),
                            onBack = { navigator.back() },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                                rememberViewModelStoreNavEntryDecorator<NavKey>(),
                            ),
                            entryProvider = entryProvider<NavKey> {
                                entry<Route.Recos> {
                                    RecosRoute(
                                        onOpenMovie = openMovie,
                                        onOpenSettings = openKeyGuide,
                                        onLightning = openLightning,
                                        onOpenTonight = openTonight,
                                        onOpenCensus = openCensus,
                                        onOpenArena = openArena,
                                        onOpenDiscover = { navigator.open(Route.Discover) },
                                    )
                                }
                                entry<Route.Shared> { key ->
                                    SharedRoute(
                                        text = key.text,
                                        onBack = { navigator.back() },
                                        onFound = { tmdbId ->
                                            navigator.back()
                                            openMovie(tmdbId)
                                        },
                                        onOpenMovie = openMovie,
                                        onOpenKeyGuide = openKeyGuide,
                                    )
                                }
                                entry<Route.Person> { key ->
                                    PersonRoute(
                                        personId = key.id,
                                        name = key.name,
                                        onBack = { navigator.back() },
                                        onOpenMovie = openMovie,
                                        onOpenKeyGuide = openKeyGuide,
                                    )
                                }
                                entry<Route.Retro> {
                                    RetroRoute(onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.UserList> { key ->
                                    UserListRoute(listId = key.id, onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Friends> { key ->
                                    FriendsRoute(incoming = key.code, onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Stats> {
                                    StatsRoute(onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Tonight> {
                                    TonightRoute(onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Lightning> { key ->
                                    LightningRoute(
                                        seedId = key.tmdbId,
                                        onClose = { navigator.back() },
                                        onOpenMovie = openMovie,
                                        onOpenSettings = openKeyGuide,
                                        onLightning = openLightning,
                                    )
                                }
                                entry<Route.Arena> {
                                    ArenaRoute(
                                        onOpenCensus = openCensus,
                                        onOpenRecos = openRecos,
                                        // Séries : l'Arène des saisons, à côté.
                                        onOpenSeasons = if (container.kind.isSeries) ({ navigator.open(Route.SeasonArena) }) else null,
                                    )
                                }
                                entry<Route.SeasonArena> {
                                    SeasonArenaRoute(onBack = { navigator.back() })
                                }
                                entry<Route.Search> {
                                    SearchRoute(onOpenMovie = openMovie, onOpenSettings = openKeyGuide, onLightning = openLightning)
                                }
                                entry<Route.Discover> {
                                    DiscoverRoute(
                                        onOpenMovie = openMovie,
                                        onOpenSettings = openKeyGuide,
                                        onLightning = openLightning,
                                        onBack = { navigator.back() },
                                        onOpenTonight = openTonight,
                                    )
                                }
                                entry<Route.Profile> {
                                    ProfileRoute(
                                        onOpenLibrary = { filter -> navigator.open(Route.Library(filter)) },
                                        onOpenDuels = { navigator.open(Route.DuelHistory) },
                                        onOpenCensus = openCensus,
                                        onOpenSettings = openSettings,
                                        onOpenMovie = openMovie,
                                        onOpenStats = { navigator.open(Route.Stats) },
                                        onOpenList = { id -> navigator.open(Route.UserList(id)) },
                                        onOpenRetro = { navigator.open(Route.Retro) },
                                        onOpenFriends = { navigator.open(Route.Friends()) },
                                    )
                                }
                                entry<Route.Library> { key ->
                                    LibraryRoute(
                                        initialFilter = key.filter,
                                        onBack = { navigator.back() },
                                        onOpenMovie = openMovie,
                                        onOpenCensus = openCensus,
                                        onOpenMovieIn = { id, ids -> navigator.open(Route.MovieDetail(id, ids)) },
                                    )
                                }
                                entry<Route.DuelHistory> {
                                    DuelHistoryRoute(onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Census> {
                                    CensusRoute(onOpenMovie = openMovie, onOpenSettings = openKeyGuide, onOpenArena = openArena)
                                }
                                entry<Route.Settings> {
                                    SettingsRoute(
                                        onBack = { navigator.back() },
                                        onOpenHomelab = { navigator.open(Route.Homelab) },
                                        onOpenDiagnostics = { navigator.open(Route.Diagnostics) },
                                        onOpenKeyGuide = openKeyGuide,
                                        onOpenImport = { navigator.open(Route.Import) },
                                    )
                                }
                                entry<Route.TmdbKey> {
                                    TmdbKeyRoute(onDone = {
                                        onKeyGuideDone()
                                        navigator.back()
                                    })
                                }
                                entry<Route.Import> {
                                    ImportRoute(onBack = { navigator.back() }, onOpenKeyGuide = openKeyGuide, onOpenLibrary = {
                                        navigator.back()
                                        navigator.open(Route.Library(LibraryFilter.WATCHED))
                                    })
                                }
                                entry<Route.Diagnostics> {
                                    DiagnosticsRoute(onBack = { navigator.back() }, onOpenMovie = openMovie)
                                }
                                entry<Route.Homelab> {
                                    HomelabRoute(
                                        onBack = { navigator.back() },
                                        onOpenService = { service -> navigator.open(Route.HomelabServiceSettings(service)) },
                                    )
                                }
                                entry<Route.HomelabServiceSettings> { key ->
                                    ServiceSettingsRoute(service = key.service, onBack = { navigator.back() })
                                }
                                entry<Route.MovieDetail> { key ->
                                    if (key.siblings.size > 1 && key.tmdbId in key.siblings) {
                                        MovieDetailPager(
                                            ids = key.siblings,
                                            startId = key.tmdbId,
                                            onBack = { navigator.back() },
                                            onLightning = openLightning,
                                        )
                                    } else {
                                        MovieDetailRoute(
                                            tmdbId = key.tmdbId,
                                            onBack = { navigator.back() },
                                            onLightning = openLightning,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp),
                ) { data ->
                    CineToast(data.visuals.message)
                }
            }
        }
    }
}


object DemoTestTags {
    const val STRIP = "demo_strip"
    const val EXIT = "demo_exit"
}

@Composable
private fun app(): CineSwipeApp = LocalContext.current.applicationContext as CineSwipeApp

/** Bandeau de la démo, en haut de tous les écrans. */
@Composable
private fun DemoStrip(onExit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CineColors.RedDeep)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 40.dp)
            .testTag(DemoTestTags.STRIP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(CineColors.Red),
        )
        Text(stringResource(R.string.demo_strip_title), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(
            stringResource(R.string.demo_strip_body),
            color = CineColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExit, modifier = Modifier.testTag(DemoTestTags.EXIT)) {
            Text(stringResource(R.string.demo_exit), color = CineColors.Red, fontWeight = FontWeight.Bold)
        }
    }
}

