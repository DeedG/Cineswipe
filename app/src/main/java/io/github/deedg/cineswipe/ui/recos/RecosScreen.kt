package io.github.deedg.cineswipe.ui.recos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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

    init {
        feed.start(FeedRequest.Recos)
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
) {
    val viewModel = cineViewModel { RecosViewModel(it) }
    val state by viewModel.feed.state.collectAsStateWithLifecycle()
    val nudge by viewModel.nudge.collectAsStateWithLifecycle()
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
        onAction = viewModel.feed::act,
        onShown = viewModel.feed::onCardShown,
        onLoadMore = viewModel.feed::loadMore,
        onRetry = viewModel.feed::retry,
        onUndo = viewModel.feed::undo,
        onLightning = { onLightning(it.tmdbId) },
        onOpenMovie = onOpenMovie,
        onOpenSettings = onOpenSettings,
        onEndAction = viewModel::reload,
        topStart = {
            GlassPillButton(
                text = stringResource(R.string.tonight_open),
                icon = CineIcons.Moon,
                onClick = onOpenTonight,
                modifier = Modifier.testTag(RecosTestTags.TONIGHT),
            )
        },
        topEnd = { WorldSwitch(tabName = Tab.RECOS.name) },
        banner = {
            AnimatedVisibility(visible = nudge != null, enter = fadeIn(), exit = fadeOut()) {
                val current = nudge ?: return@AnimatedVisibility
                RecoNudgeBanner(
                    ui = current,
                    onFollow = {
                        viewModel.followNudge(current.nudge)
                        when (current.nudge) {
                            RecoNudge.CENSUS -> onOpenCensus()
                            RecoNudge.DUELS -> onOpenArena()
                        }
                    },
                    onDismiss = {
                        viewModel.dismissNudge(current.nudge)
                        toaster.show(dismissedMessage)
                    },
                )
            }
        },
    )
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
    const val NUDGE_ACTION = "recos_nudge_action"
    const val NUDGE_DISMISS = "recos_nudge_dismiss"
    fun nudge(nudge: RecoNudge) = "recos_nudge_${nudge.name.lowercase()}"
}
