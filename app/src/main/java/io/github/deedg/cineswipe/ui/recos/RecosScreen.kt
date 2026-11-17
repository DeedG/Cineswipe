package io.github.deedg.cineswipe.ui.recos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.domain.journey.JourneyState
import io.github.deedg.cineswipe.domain.journey.JourneyStep
import io.github.deedg.cineswipe.domain.reco.RecoNudge
import io.github.deedg.cineswipe.domain.reco.RecoNudges
import io.github.deedg.cineswipe.domain.reco.RecoStrength
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.WorldSwitch
import io.github.deedg.cineswipe.ui.feed.CardFeed
import io.github.deedg.cineswipe.ui.feed.CardFeedScreen
import io.github.deedg.cineswipe.ui.feed.FeedTexts
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.journey.JourneyScreen
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Invitation à montrer sur les recos, avec de quoi la formuler. */
data class RecoNudgeUi(val nudge: RecoNudge, val strength: RecoStrength)

/** Recos personnalisées : le feed de swipe, écran d'accueil de l'appli. */
class RecosViewModel(private val container: AppContainer) : ViewModel() {
    val feed = CardFeed(viewModelScope, container.feed, container.settings, rememberShown = true)

    /**
     * Recos encore faibles (peu de vus, pas de recensement, peu de duels) : on invite à recenser
     * ou à jouer des duels, une fois. Rien pendant la démo (le tuto s'en charge).
     */
    val nudge: StateFlow<RecoNudgeUi?> = if (container.isDemo) {
        flowOf<RecoNudgeUi?>(null).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    } else {
        val census = container.database.censusDao()
        combine(
            census.observeWatched(),
            census.observeCensusAnswered(),
            container.arena.observeReliability(),
            container.settings.recoNudgesHidden,
        ) { watched, answered, reliability, hidden ->
            val strength = RecoStrength(watched, answered, reliability.value)
            RecoNudges.pick(container.kind.isSeries, strength, hidden, System.currentTimeMillis())?.let { RecoNudgeUi(it, strength) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** Parcours Recenser → Arène → Recos ; null le temps de le lire. Tant qu'il n'est pas fait, les recos attendent. */
    val journey: StateFlow<JourneyState?> =
        container.journey.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Rappel des gestes, à la première ouverture des recos. */
    val showHint: StateFlow<Boolean> = if (container.isDemo) {
        flowOf(false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    } else {
        container.settings.recosHintSeen.map { !it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    init {
        // Les recos ne se chargent qu'une fois ouvertes (parcours fait, ou passé).
        viewModelScope.launch {
            container.journey.state.first { it.recosOpen }
            feed.start(FeedRequest.Recos)
        }
    }

    fun skipJourney() {
        viewModelScope.launch { container.journey.skip() }
    }

    fun hintSeen() {
        viewModelScope.launch { container.settings.setRecosHintSeen() }
    }

    fun reload() {
        feed.start(FeedRequest.Recos)
    }

    /** Invitation suivie : elle ne revient pas avant une semaine. */
    fun followNudge(nudge: RecoNudge) {
        viewModelScope.launch { container.settings.hideRecoNudge(nudge, System.currentTimeMillis() + RecoNudges.SNOOZE_MS) }
    }

    /** Invitation refusée : plus jamais. */
    fun dismissNudge(nudge: RecoNudge) {
        viewModelScope.launch { container.settings.hideRecoNudge(nudge, RecoNudges.NEVER) }
    }
}

@Composable
fun RecosRoute(
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onLightning: (Long) -> Unit,
    onOpenTonight: () -> Unit = {},
    onOpenCensus: () -> Unit = {},
    onOpenArena: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
) {
    val viewModel = cineViewModel { RecosViewModel(it) }
    val journey by viewModel.journey.collectAsStateWithLifecycle()
    val current = journey
    if (current == null) {
        Box(Modifier.fillMaxSize().background(CineColors.Black))
        return
    }
    if (!current.recosOpen) {
        JourneyScreen(
            state = current,
            onCensus = onOpenCensus,
            onArena = onOpenArena,
            onSkip = viewModel::skipJourney,
            topEnd = { WorldSwitch(tabName = Tab.RECOS.name) },
        )
        return
    }
    val state by viewModel.feed.state.collectAsStateWithLifecycle()
    val shownNudge by viewModel.nudge.collectAsStateWithLifecycle()
    val showHint by viewModel.showHint.collectAsStateWithLifecycle()
    // Parcours tout juste terminé : pas d'invitation à recenser ou à noter par-dessus (elle reste
    // pour qui a passé le parcours, ou n'en a pas eu).
    val journeyDone = current.step == JourneyStep.RECOS && current.watchedGoal > 0
    val nudge = shownNudge.takeUnless { journeyDone }
    val toaster = LocalToaster.current
    val dismissedMessage = stringResource(R.string.reco_nudge_dismissed)
    DisposableEffect(viewModel) { onDispose { viewModel.feed.clearUndo() } }
    CardFeedScreen(
        state = state,
        events = viewModel.feed.events,
        texts = FeedTexts(
            loading = stringResource(R.string.recos_loading),
            emptyTitle = stringResource(R.string.recos_empty_title),
            emptyMessage = stringResource(R.string.recos_empty_message),
            endTitle = stringResource(R.string.recos_end),
            endActionLabel = stringResource(R.string.recos_reload),
        ),
        onAction = { card, action ->
            // Premier geste fait : le rappel des gestes a servi.
            if (showHint) viewModel.hintSeen()
            viewModel.feed.act(card, action)
        },
        onShown = viewModel.feed::onCardShown,
        onLoadMore = viewModel.feed::loadMore,
        onRetry = viewModel.feed::retry,
        onUndo = viewModel.feed::undo,
        onLightning = { onLightning(it.tmdbId) },
        onOpenMovie = onOpenMovie,
        onOpenSettings = onOpenSettings,
        onEndAction = viewModel::reload,
        topStart = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassPillButton(
                    text = stringResource(R.string.tonight_open),
                    icon = CineIcons.Moon,
                    onClick = onOpenTonight,
                    modifier = Modifier.testTag(RecosTestTags.TONIGHT),
                )
                // La Découverte (recos par genre, années, note) n'a plus d'onglet : elle s'ouvre d'ici.
                GlassIconButton(
                    icon = CineIcons.Discover,
                    contentDescription = stringResource(R.string.recos_discover),
                    onClick = onOpenDiscover,
                    modifier = Modifier.testTag(RecosTestTags.DISCOVER),
                )
            }
        },
        topEnd = { WorldSwitch(tabName = Tab.RECOS.name) },
        banner = {
            // Le rappel des gestes passe après une invitation à recenser ou à noter.
            AnimatedVisibility(visible = showHint && nudge == null, enter = fadeIn(), exit = fadeOut()) {
                RecosHint(onClose = viewModel::hintSeen)
            }
            AnimatedVisibility(visible = nudge != null, enter = fadeIn(), exit = fadeOut()) {
                val shown = nudge ?: return@AnimatedVisibility
                RecoNudgeBanner(
                    ui = shown,
                    onFollow = {
                        viewModel.followNudge(shown.nudge)
                        when (shown.nudge) {
                            RecoNudge.CENSUS -> onOpenCensus()
                            RecoNudge.DUELS -> onOpenArena()
                        }
                    },
                    onDismiss = {
                        viewModel.dismissNudge(shown.nudge)
                        toaster.show(dismissedMessage)
                    },
                )
            }
        },
    )
}

/** Rappel des gestes sur la première reco : il part au premier geste, ou sur « Compris ». */
@Composable
private fun RecosHint(onClose: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE6101010))
            .border(1.dp, CineColors.Red.copy(alpha = 0.6f), shape)
            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp)
            .testTag(RecosTestTags.HINT),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.recos_hint_title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.recos_hint_body), color = CineColors.TextSoft, fontSize = 13.sp, lineHeight = 18.sp)
        Text(
            stringResource(R.string.recos_hint_ok),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CineColors.Red)
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag(RecosTestTags.HINT_OK),
        )
    }
}

/** Bandeau des recos faibles : pourquoi, et un bouton pour y remédier. */
@Composable
private fun RecoNudgeBanner(ui: RecoNudgeUi, onFollow: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val (title, body, action) = when (ui.nudge) {
        RecoNudge.CENSUS -> Triple(
            stringResource(R.string.reco_nudge_census_title),
            pluralStringResource(R.plurals.reco_nudge_census_body, ui.strength.watched, ui.strength.watched),
            stringResource(R.string.reco_nudge_census_action),
        )
        RecoNudge.DUELS -> Triple(
            stringResource(R.string.reco_nudge_duels_title),
            stringResource(R.string.reco_nudge_duels_body, (ui.strength.reliability * 100).roundToInt()),
            stringResource(R.string.reco_nudge_duels_action),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE6101010))
            .border(1.dp, CineColors.Red.copy(alpha = 0.6f), shape)
            .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 12.dp)
            .testTag(RecosTestTags.nudge(ui.nudge)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ui.nudge == RecoNudge.CENSUS) CineIcons.Grid else CineIcons.Arena,
                contentDescription = null,
                tint = CineColors.RedLight,
                modifier = Modifier.size(18.dp),
            )
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            GlassIconButton(
                icon = CineIcons.Close,
                contentDescription = stringResource(R.string.reco_nudge_dismiss),
                onClick = onDismiss,
                buttonSize = 32.dp,
                modifier = Modifier.testTag(RecosTestTags.NUDGE_DISMISS),
            )
        }
        Text(body, color = CineColors.TextSoft, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(end = 6.dp))
        Text(
            action,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CineColors.Red)
                .clickable(role = Role.Button, onClick = onFollow)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag(RecosTestTags.NUDGE_ACTION),
        )
    }
}

object RecosTestTags {
    const val TONIGHT = "recos_tonight"
    const val DISCOVER = "recos_discover"
    const val HINT = "recos_hint"
    const val HINT_OK = "recos_hint_ok"
    const val NUDGE_ACTION = "recos_nudge_action"
    const val NUDGE_DISMISS = "recos_nudge_dismiss"
    fun nudge(nudge: RecoNudge) = "recos_nudge_${nudge.name.lowercase()}"
}
