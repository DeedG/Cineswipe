package io.github.deedg.cineswipe.ui.recos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.feed.CardFeed
import io.github.deedg.cineswipe.ui.feed.CardFeedScreen
import io.github.deedg.cineswipe.ui.feed.FeedTexts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.icons.CineIcons

/** Recos personnalisées : le feed de swipe, écran d'accueil de l'appli. */
class RecosViewModel(feedRepository: FeedRepository, settings: SettingsRepository) : ViewModel() {
    val feed = CardFeed(viewModelScope, feedRepository, settings, rememberShown = true)

    init {
        feed.start(FeedRequest.Recos)
    }

    fun reload() {
        feed.start(FeedRequest.Recos)
    }
}

@Composable
fun RecosRoute(
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onLightning: (Long) -> Unit,
    onOpenTonight: () -> Unit = {},
) {
    val viewModel = cineViewModel { RecosViewModel(it.feed, it.settings) }
    val state by viewModel.feed.state.collectAsStateWithLifecycle()
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
    )
}

object RecosTestTags {
    const val TONIGHT = "recos_tonight"
}
