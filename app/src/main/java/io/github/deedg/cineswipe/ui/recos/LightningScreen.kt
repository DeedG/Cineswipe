package io.github.deedg.cineswipe.ui.recos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.feed.CardFeed
import io.github.deedg.cineswipe.ui.feed.CardFeedScreen
import io.github.deedg.cineswipe.ui.feed.FeedTexts
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ⚡ Éclair : feed des recos TMDB à partir d'un film. */
class LightningViewModel(
    private val seedId: Long,
    feedRepository: FeedRepository,
    settings: SettingsRepository,
    library: LibraryRepository,
) : ViewModel() {
    val feed = CardFeed(viewModelScope, feedRepository, settings, rememberShown = true)

    private val _seedTitle = MutableStateFlow<String?>(null)
    val seedTitle: StateFlow<String?> = _seedTitle.asStateFlow()

    init {
        feed.start(FeedRequest.Lightning(seedId))
        viewModelScope.launch { _seedTitle.value = library.details(seedId)?.movie?.title }
    }

    fun reload() {
        feed.start(FeedRequest.Lightning(seedId))
    }
}

@Composable
fun LightningRoute(
    seedId: Long,
    onClose: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onLightning: (Long) -> Unit,
) {
    val viewModel = cineViewModel(key = "lightning-$seedId") {
        LightningViewModel(seedId, it.feed, it.settings, it.library)
    }
    val state by viewModel.feed.state.collectAsStateWithLifecycle()
    val seedTitle by viewModel.seedTitle.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.feed.clearUndo() } }
    CardFeedScreen(
        state = state,
        events = viewModel.feed.events,
        texts = FeedTexts(
            loading = stringResource(R.string.lightning_loading),
            emptyTitle = stringResource(R.string.recos_empty_title),
            emptyMessage = stringResource(R.string.lightning_empty_message),
            endTitle = stringResource(R.string.lightning_end),
            endActionLabel = stringResource(R.string.lightning_close),
        ),
        onAction = viewModel.feed::act,
        onShown = viewModel.feed::onCardShown,
        onLoadMore = viewModel.feed::loadMore,
        onRetry = viewModel.feed::retry,
        onUndo = viewModel.feed::undo,
        onLightning = { onLightning(it.tmdbId) },
        onOpenMovie = onOpenMovie,
        onOpenSettings = onOpenSettings,
        onEndAction = onClose,
        topStart = { LightningChip(title = seedTitle, onClose = onClose) },
    )
}

/** Rappel du film de départ, avec de quoi revenir aux recos habituelles. */
@Composable
private fun LightningChip(title: String?, onClose: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .widthIn(max = 250.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(CineColors.Glass)
            .border(1.dp, CineColors.BorderSoft, shape)
            .clickable(role = Role.Button, onClick = onClose)
            .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CineIcons.Lightning, contentDescription = null, tint = CineColors.Lightning, modifier = Modifier.size(16.dp))
        Text(
            text = title?.let { stringResource(R.string.lightning_title, it) } ?: stringResource(R.string.lightning_title_generic),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            CineIcons.Close,
            contentDescription = stringResource(R.string.lightning_close),
            tint = CineColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}
