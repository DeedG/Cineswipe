package io.github.deedg.cineswipe.ui.tutorial

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.navigation.Route
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.navigation.showsBottomBar
import io.github.deedg.cineswipe.ui.navigation.tab

/** Élément de l'écran que le tuto met en lumière. */
enum class TutorialTarget {
    RECO_POSTER,
    RECO_ACTIONS,
    ARENA_FIGHTER,
    ARENA_DUEL,
    ARENA_RELIABILITY,
    ARENA_DRAW,
    DISCOVER_CENSUS,
    CENSUS_GRID,
    CENSUS_NEXT,
    CENSUS_MODE,
    SEARCH_FIELD,
    PROFILE_STATS,
    PROFILE_SETTINGS,
    TAB_RECOS,
    TAB_ARENA,
    TAB_SEARCH,
    TAB_DISCOVER,
    TAB_PROFILE,

    /** Bouton « retour » de l'écran affiché. */
    BACK,
}

/** Onglet de la barre du bas, tel que le tuto le montre. */
val Tab.tutorialTarget: TutorialTarget
    get() = when (this) {
        Tab.RECOS -> TutorialTarget.TAB_RECOS
        Tab.ARENA -> TutorialTarget.TAB_ARENA
        Tab.SEARCH -> TutorialTarget.TAB_SEARCH
        Tab.DISCOVER -> TutorialTarget.TAB_DISCOVER
        Tab.PROFILE -> TutorialTarget.TAB_PROFILE
    }

/** Ce que fait l'utilisateur, et qu'une étape du tuto peut attendre. */
enum class TutorialEvent { RECO_WATCHED, RECO_WATCHLIST, RECO_SKIPPED, ARENA_VOTED, CENSUS_TAPPED, CENSUS_NEXT }

/**
 * Geste montré par la main animée. Quand le tuto le joue lui-même ([durationMs]), l'action part à
 * [actAt] (l'appui pour un toucher, le début du glissé pour un swipe) et dure [moveMs].
 */
enum class TutorialGesture(val durationMs: Int, val actAt: Float, val moveMs: Int) {
    TAP(950, 0.42f, 0),
    SWIPE_LEFT(1100, 0.15f, 640),
    SWIPE_RIGHT(1100, 0.15f, 640),
    SWIPE_UP(1100, 0.15f, 640),
}

/** Où se pose la bulle : à côté de l'élément si la place le permet, ou en haut de l'écran. */
enum class BubblePlacement { AUTO, TOP }

/** Un pas du trajet vers l'écran d'une étape : l'élément à toucher, puis l'écran ouvert ([route] nul : retour). */
data class TutorialHop(val target: TutorialTarget?, val route: Route?)

/** Ce que le tuto demande à l'écran de faire, une seule fois ([id]). */
data class TutorialRequest(val id: Int, val event: TutorialEvent? = null, val hop: TutorialHop? = null, val moveMs: Int = 0)

/**
 * Geste joué par le tuto : la main le fait sur [target] (au centre de [gestureAt]), puis l'écran
 * agit ([effect]). [travel] : trajet vers l'écran de l'étape plutôt que démonstration.
 */
data class TutorialPlay(
    val id: Int,
    val stepIndex: Int,
    val target: TutorialTarget,
    val gestureAt: TutorialTarget,
    val gesture: TutorialGesture,
    val effect: TutorialRequest,
    val travel: Boolean,
)

/**
 * Une étape : l'écran où elle se passe, l'élément mis en lumière, le geste à faire et ce qu'elle
 * attend pour passer à la suivante (sans attente, « Suivant » suffit ; avec, « Suivant » reste
 * possible pour ne jamais bloquer).
 */
data class TutorialStep(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val route: Route? = null,
    val target: TutorialTarget? = null,
    val gesture: TutorialGesture? = null,
    val waitFor: TutorialEvent? = null,
    val waitForRoute: Route? = null,
    /** Point où la main fait le geste, s'il diffère de l'élément éclairé. */
    val gestureTarget: TutorialTarget? = null,
    val bubble: BubblePlacement = BubblePlacement.AUTO,
) {
    /** L'utilisateur agit sur l'écran (les touchers passent sous le voile). */
    val interactive: Boolean get() = waitFor != null || waitForRoute != null
}

/**
 * Tuto pas à pas de la démo : chaque étape montre un élément de l'écran, explique à quoi il sert
 * et, souvent, fait faire le geste. Les écrans déclarent leurs éléments ([tutorialTarget]) et
 * signalent les gestes ([event]).
 */
class Tutorial(val steps: List<TutorialStep> = STEPS) {
    /** Étape en cours, -1 si le tuto est fermé. */
    var stepIndex by mutableIntStateOf(-1)
        private set

    val active: Boolean get() = stepIndex >= 0
    val step: TutorialStep? get() = steps.getOrNull(stepIndex)
    val isLast: Boolean get() = stepIndex == steps.lastIndex

    /** Position des éléments à l'écran (coordonnées de la racine). */
    val bounds = mutableStateMapOf<TutorialTarget, Rect>()

    /** Écran affiché. */
    var route by mutableStateOf<Route?>(null)
        private set

    /** Geste que la main joue en ce moment (démonstration ou trajet), sinon nul. */
    var play by mutableStateOf<TutorialPlay?>(null)
        private set

    /** Action demandée aux écrans, en attente d'être prise. */
    var request by mutableStateOf<TutorialRequest?>(null)
        private set

    private var lastId = 0

    /** Visite auto : le tuto se joue tout seul (pour filmer l'écran), étape après étape. */
    var autoplay by mutableStateOf(false)
        private set

    /** L'étape se passe sur un autre écran : le tuto est en route vers lui. */
    val away: Boolean
        get() {
            val wanted = step?.route ?: return false
            return route != wanted && route != step?.waitForRoute
        }

    fun start() {
        clearPlay()
        autoplay = false
        stepIndex = 0
    }

    /** Lance (ou reprend) la visite auto depuis l'étape en cours. */
    fun startAutoplay() {
        if (!active) start()
        autoplay = true
    }

    fun stopAutoplay() {
        autoplay = false
    }

    fun next() {
        if (!active) return
        if (stepIndex < steps.lastIndex) stepIndex++ else stop()
        // La visite auto s'arrête sur la dernière bulle (explorer ou quitter la démo).
        if (stepIndex == steps.lastIndex) autoplay = false
    }

    fun stop() {
        clearPlay()
        autoplay = false
        stepIndex = -1
    }

    /** « Montre-moi » : la main fait le geste de l'étape, et l'écran agit comme si on l'avait fait. */
    fun demonstrate() {
        val step = step ?: return
        if (!step.interactive || play != null) return
        val target = step.target
        if (target == null) {
            next()
            return
        }
        val gesture = step.gesture ?: TutorialGesture.TAP
        val id = ++lastId
        val hop = step.waitForRoute?.let { TutorialHop(target, it) }
        play = TutorialPlay(
            id = id,
            stepIndex = stepIndex,
            target = target,
            gestureAt = step.gestureTarget ?: target,
            gesture = gesture,
            effect = TutorialRequest(id, event = step.waitFor, hop = hop, moveMs = gesture.moveMs),
            travel = false,
        )
    }

    /** Trajet vers l'écran de l'étape : la main touche [TutorialHop.target], puis l'écran change. */
    fun travel(hop: TutorialHop) {
        val target = hop.target ?: return
        if (play != null) return
        val id = ++lastId
        play = TutorialPlay(id, stepIndex, target, target, TutorialGesture.TAP, TutorialRequest(id, hop = hop), travel = true)
    }

    /** Le doigt appuie (ou commence à glisser) : l'écran doit agir maintenant. */
    fun act(play: TutorialPlay) {
        if (this.play?.id == play.id) request = play.effect
    }

    /** Fin du geste joué ; une demande que personne n'a prise est oubliée. */
    fun finish(play: TutorialPlay) {
        if (this.play?.id != play.id) return
        this.play = null
        if (request?.id == play.id) request = null
    }

    /** Prend la demande [request] : elle ne sera plus proposée. */
    fun take(request: TutorialRequest): Boolean {
        if (this.request?.id != request.id) return false
        this.request = null
        return true
    }

    /** Les demandes du tuto qui concernent ces gestes, chacune reçue une fois, l'une après l'autre. */
    suspend fun handle(vararg events: TutorialEvent, block: suspend (TutorialRequest) -> Unit) {
        snapshotFlow { request }.collect { request ->
            if (request != null && request.event in events && take(request)) block(request)
        }
    }

    /** Les trajets demandés (onglet, retour, écran ouvert depuis un élément). */
    suspend fun handleHops(block: (TutorialHop) -> Unit) {
        snapshotFlow { request }.collect { request ->
            val hop = request?.hop
            if (hop != null && take(request)) block(hop)
        }
    }

    private fun clearPlay() {
        play = null
        request = null
    }

    /** Un geste de l'utilisateur : l'étape qui l'attendait est réussie. */
    fun event(event: TutorialEvent) {
        if (step?.waitFor == event) next()
    }

    /** L'écran affiché a changé. */
    fun onRoute(route: Route?) {
        this.route = route
        val wanted = step?.waitForRoute ?: return
        if (route == wanted) next()
    }

    internal fun report(target: TutorialTarget, rect: Rect) {
        if (rect.width <= 0f || rect.height <= 0f) {
            bounds.remove(target)
        } else if (bounds[target] != rect) {
            bounds[target] = rect
        }
    }

    internal fun remove(target: TutorialTarget) {
        bounds.remove(target)
    }

    companion object {
        val STEPS: List<TutorialStep> = listOf(
            TutorialStep(R.string.tuto_intro_title, R.string.tuto_intro_body, route = Route.Recos),
            TutorialStep(R.string.tuto_reco_title, R.string.tuto_reco_body, Route.Recos, TutorialTarget.RECO_POSTER),
            TutorialStep(
                R.string.tuto_watched_title,
                R.string.tuto_watched_body,
                Route.Recos,
                TutorialTarget.RECO_POSTER,
                TutorialGesture.SWIPE_LEFT,
                waitFor = TutorialEvent.RECO_WATCHED,
            ),
            TutorialStep(
                R.string.tuto_watchlist_title,
                R.string.tuto_watchlist_body,
                Route.Recos,
                TutorialTarget.RECO_POSTER,
                TutorialGesture.SWIPE_RIGHT,
                waitFor = TutorialEvent.RECO_WATCHLIST,
            ),
            TutorialStep(
                R.string.tuto_skip_title,
                R.string.tuto_skip_body,
                Route.Recos,
                TutorialTarget.RECO_POSTER,
                TutorialGesture.SWIPE_UP,
                waitFor = TutorialEvent.RECO_SKIPPED,
            ),
            TutorialStep(R.string.tuto_actions_title, R.string.tuto_actions_body, Route.Recos, TutorialTarget.RECO_ACTIONS),
            TutorialStep(
                R.string.tuto_arena_title,
                R.string.tuto_arena_body,
                Route.Arena,
                TutorialTarget.ARENA_DUEL,
                TutorialGesture.TAP,
                waitFor = TutorialEvent.ARENA_VOTED,
                gestureTarget = TutorialTarget.ARENA_FIGHTER,
                bubble = BubblePlacement.TOP,
            ),
            TutorialStep(R.string.tuto_reliability_title, R.string.tuto_reliability_body, Route.Arena, TutorialTarget.ARENA_RELIABILITY),
            TutorialStep(R.string.tuto_draw_title, R.string.tuto_draw_body, Route.Arena, TutorialTarget.ARENA_DRAW),
            TutorialStep(
                R.string.tuto_census_open_title,
                R.string.tuto_census_open_body,
                Route.Discover,
                TutorialTarget.DISCOVER_CENSUS,
                TutorialGesture.TAP,
                waitForRoute = Route.Census,
            ),
            TutorialStep(
                R.string.tuto_census_tap_title,
                R.string.tuto_census_tap_body,
                Route.Census,
                TutorialTarget.CENSUS_GRID,
                TutorialGesture.TAP,
                waitFor = TutorialEvent.CENSUS_TAPPED,
                bubble = BubblePlacement.TOP,
            ),
            TutorialStep(
                R.string.tuto_census_next_title,
                R.string.tuto_census_next_body,
                Route.Census,
                TutorialTarget.CENSUS_NEXT,
                TutorialGesture.TAP,
                waitFor = TutorialEvent.CENSUS_NEXT,
            ),
            TutorialStep(R.string.tuto_census_mode_title, R.string.tuto_census_mode_body, Route.Census, TutorialTarget.CENSUS_MODE),
            TutorialStep(R.string.tuto_search_title, R.string.tuto_search_body, Route.Search, TutorialTarget.SEARCH_FIELD),
            TutorialStep(R.string.tuto_profile_title, R.string.tuto_profile_body, Route.Profile, TutorialTarget.PROFILE_STATS),
            TutorialStep(R.string.tuto_settings_title, R.string.tuto_settings_body, Route.Profile, TutorialTarget.PROFILE_SETTINGS),
            TutorialStep(R.string.tuto_end_title, R.string.tuto_end_body),
        )
    }
}

/**
 * Prochain geste pour aller de [from] à [to], comme le ferait l'utilisateur : depuis un écran sans
 * barre du bas, le retour ; puis l'onglet ; puis l'élément qui ouvre l'écran voulu (le recensement
 * depuis la Découverte). Nul quand on y est déjà.
 */
fun tutorialHop(from: Route?, to: Route): TutorialHop? {
    if (from == to) return null
    val tabRoot = Tab.entries.firstOrNull { it.route == to }
    return when {
        from != null && !from.showsBottomBar -> TutorialHop(TutorialTarget.BACK, null)
        tabRoot != null -> TutorialHop(tabRoot.tutorialTarget, to)
        to == Route.Census && from == Route.Discover -> TutorialHop(TutorialTarget.DISCOVER_CENSUS, to)
        from != to.tab.route -> TutorialHop(to.tab.tutorialTarget, to.tab.route)
        else -> TutorialHop(null, to)
    }
}

/** Tuto en cours (seulement pendant la démo). */
val LocalTutorial = staticCompositionLocalOf<Tutorial?> { null }

/**
 * Déclare un élément que le tuto peut montrer ([enabled] faux : pas cet exemplaire, par exemple
 * une carte qui n'est pas à l'écran).
 */
@Composable
fun Modifier.tutorialTarget(target: TutorialTarget, enabled: Boolean = true): Modifier {
    val tutorial = LocalTutorial.current
    if (tutorial == null || !enabled) return this
    DisposableEffect(tutorial, target) { onDispose { tutorial.remove(target) } }
    return onGloballyPositioned { tutorial.report(target, it.boundsInRoot()) }
}
