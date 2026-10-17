package io.github.deedg.cineswipe.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.CardAction
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.SeasonRequestSheet
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.LocalTutorial
import io.github.deedg.cineswipe.ui.tutorial.TutorialEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Textes propres à chaque feed (recos, Éclair, recherche, découverte). */
data class FeedTexts(
    val loading: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val endTitle: String,
    val endActionLabel: String? = null,
)

/** La suite est chargée quand il reste moins de cinq cartes. */
private const val PREFETCH_DISTANCE = 5
private const val END_PAGE_KEY = "end"

/** Feed vertical plein écran, une carte par page (le `scroll-snap` de la V4). */
@Composable
fun CardFeedScreen(
    state: CardFeedState,
    events: Flow<FeedEvent>,
    texts: FeedTexts,
    onAction: (FeedCardUi, CardAction) -> Unit,
    onShown: (FeedCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onLightning: (FeedCardUi) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onEndAction: (() -> Unit)? = null,
    topStart: (@Composable () -> Unit)? = null,
    topEnd: (@Composable () -> Unit)? = null,
    /** Bandeau sous la barre du haut (invitation des recos), sur les cartes. */
    banner: (@Composable () -> Unit)? = null,
) {
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val actionMessages = mapOf(
        CardAction.WATCHED to stringResource(R.string.toast_marked_watched),
        CardAction.WATCHLIST to stringResource(R.string.toast_added_watchlist),
        CardAction.UNWATCH to stringResource(R.string.toast_unwatched),
        CardAction.DISMISS to stringResource(R.string.toast_dismissed),
    )
    val restoredMessage = stringResource(R.string.toast_restored)
    val currentCards by rememberUpdatedState(state.cards)
    val pagerState = key(state.session) { rememberPagerState(pageCount = { currentCards.size + 1 }) }
    // Série envoyée à droite : choix des saisons à demander.
    var askSeasons by remember { mutableStateOf<FeedEvent.AskSeasons?>(null) }
    askSeasons?.let { ask ->
        SeasonRequestSheet(
            request = ask.request,
            service = ask.service,
            onRequest = { seasons ->
                askSeasons = null
                ask.confirm(seasons)
            },
            onLater = {
                askSeasons = null
                actionMessages[CardAction.WATCHLIST]?.let(toaster::show)
            },
        )
    }

    // Série envoyée à gauche : où en est-on ?
    var askProgress by remember { mutableStateOf<FeedEvent.AskProgress?>(null) }
    askProgress?.let { ask ->
        SeriesProgressSheet(request = ask.request, onChange = ask.save, onDone = { askProgress = null })
    }

    LaunchedEffect(events, pagerState) {
        events.collect { event ->
            when (event) {
                is FeedEvent.Acted -> {
                    val service = event.service
                    val message = if (event.action == CardAction.REQUEST && service != null) {
                        resources.getString(R.string.toast_requested, service.displayName)
                    } else {
                        actionMessages[event.action]
                    }
                    message?.let(toaster::show)
                }
                is FeedEvent.AskSeasons -> askSeasons = event
                is FeedEvent.AskProgress -> askProgress = event
                is FeedEvent.Restored -> {
                    toaster.show(restoredMessage)
                    val index = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                        snapshotFlow { currentCards.indexOfFirst { it.tmdbId == event.tmdbId } }.first { it >= 0 }
                    }
                    if (index != null) pagerState.animateScrollToPage(index)
                }
            }
        }
    }

    Box(modifier.fillMaxSize().background(CineColors.Black)) {
        when (state.phase) {
            FeedPhase.IDLE, FeedPhase.LOADING -> FeedLoading(texts.loading)
            FeedPhase.NEEDS_KEY -> EmptyState(
                icon = CineIcons.Film,
                title = stringResource(R.string.feed_needs_key_title),
                message = stringResource(R.string.feed_needs_key_message),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenSettings,
                modifier = Modifier.statusBarsPadding(),
            )
            FeedPhase.ERROR -> FeedErrorState(
                error = state.error ?: FeedError.OTHER,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
            )
            FeedPhase.EMPTY -> EmptyState(
                icon = CineIcons.Film,
                title = texts.emptyTitle,
                message = texts.emptyMessage,
                actionLabel = texts.endActionLabel,
                onAction = onEndAction,
                modifier = Modifier.statusBarsPadding(),
            )
            FeedPhase.READY -> FeedPager(
                pagerState = pagerState,
                state = state,
                texts = texts,
                onAction = onAction,
                onShown = onShown,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onLightning = onLightning,
                onOpenMovie = onOpenMovie,
                onOpenSettings = onOpenSettings,
                onEndAction = onEndAction,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 15.dp, top = 15.dp, end = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topStart?.invoke()
            AnimatedVisibility(
                visible = state.canUndo && state.phase == FeedPhase.READY,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                GlassPillButton(text = stringResource(R.string.action_cancel), icon = CineIcons.Undo, onClick = onUndo)
            }
        }
        if (banner != null && (state.phase == FeedPhase.READY || state.phase == FeedPhase.EMPTY)) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 15.dp, end = 15.dp, top = 68.dp),
            ) { banner() }
        }
        if (topEnd != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 19.dp, end = 15.dp),
            ) { topEnd() }
        }
    }
}

@Composable
private fun FeedPager(
    pagerState: PagerState,
    state: CardFeedState,
    texts: FeedTexts,
    onAction: (FeedCardUi, CardAction) -> Unit,
    onShown: (FeedCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLightning: (FeedCardUi) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onEndAction: (() -> Unit)?,
) {
    val cards = state.cards
    val tutorial = LocalTutorial.current
    val currentCards by rememberUpdatedState(cards)
    val currentOnShown by rememberUpdatedState(onShown)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    // Mémoire du feed : la carte sur laquelle le défilement s'arrête est « montrée ».
    LaunchedEffect(pagerState) {
        snapshotFlow { currentCards.getOrNull(pagerState.settledPage) }
            .distinctUntilChangedBy { it?.tmdbId }
            .collect { card -> if (card != null) currentOnShown(card) }
    }
    // Tuto de la démo : faire défiler vers le haut passe le film.
    LaunchedEffect(pagerState, tutorial) {
        if (tutorial == null) return@LaunchedEffect
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page > previous) tutorial.event(TutorialEvent.RECO_SKIPPED)
            previous = page
        }
    }
    // « Montre-moi » du tuto : la carte suivante monte, comme après un défilement.
    LaunchedEffect(pagerState, tutorial) {
        tutorial?.handle(TutorialEvent.RECO_SKIPPED) { request ->
            val next = pagerState.settledPage + 1
            if (next < pagerState.pageCount) {
                pagerState.animateScrollToPage(next, animationSpec = tween(request.moveMs.coerceAtLeast(1), easing = FastOutSlowInEasing))
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to currentCards.size }
            .collect { (page, size) -> if (page >= size - PREFETCH_DISTANCE) currentOnLoadMore() }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { page -> currentCards.getOrNull(page)?.tmdbId ?: END_PAGE_KEY },
    ) { page ->
        val card = cards.getOrNull(page)
        if (card != null) {
            SwipeableMovieCard(
                card = card,
                showAvailability = state.showAvailability,
                requestService = state.requestService,
                onAction = { action ->
                    when (action) {
                        CardAction.WATCHED -> tutorial?.event(TutorialEvent.RECO_WATCHED)
                        CardAction.WATCHLIST, CardAction.REQUEST -> tutorial?.event(TutorialEvent.RECO_WATCHLIST)
                        else -> Unit
                    }
                    onAction(card, action)
                },
                onLightning = { onLightning(card) },
                onOpenMovie = { onOpenMovie(card.tmdbId) },
                highlight = page == pagerState.settledPage,
            )
        } else {
            FeedEndPage(
                state = state,
                texts = texts,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onEndAction = onEndAction,
            )
        }
    }
}

@Composable
private fun FeedLoading(text: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = CineColors.Red)
        Text(text, color = CineColors.TextMuted, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeedErrorState(error: FeedError, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val (title, message) = when (error) {
        FeedError.NETWORK -> R.string.feed_error_network_title to R.string.feed_error_network_message
        FeedError.INVALID_KEY -> R.string.feed_error_key_title to R.string.feed_error_key_message
        FeedError.OTHER -> R.string.feed_error_other_title to R.string.feed_error_other_message
    }
    EmptyState(
        icon = if (error == FeedError.INVALID_KEY) CineIcons.Settings else CineIcons.Refresh,
        title = stringResource(title),
        message = stringResource(message),
        actionLabel = stringResource(if (error == FeedError.INVALID_KEY) R.string.action_open_settings else R.string.feed_retry),
        onAction = if (error == FeedError.INVALID_KEY) onOpenSettings else onRetry,
        modifier = Modifier.statusBarsPadding(),
    )
}

/** Dernière page du feed : chargement de la suite, erreur ou fin (`.end-of-feed` en V4). */
@Composable
private fun FeedEndPage(
    state: CardFeedState,
    texts: FeedTexts,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onEndAction: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val error = state.error
            when {
                error != null -> {
                    Icon(CineIcons.Refresh, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(40.dp))
                    Text(
                        text = stringResource(
                            when (error) {
                                FeedError.NETWORK -> R.string.feed_error_network_message
                                FeedError.INVALID_KEY -> R.string.feed_error_key_message
                                FeedError.OTHER -> R.string.feed_error_other_message
                            },
                        ),
                        color = CineColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    if (error == FeedError.INVALID_KEY) {
                        CineButton(text = stringResource(R.string.action_open_settings), onClick = onOpenSettings)
                    } else {
                        CineButton(text = stringResource(R.string.feed_retry), onClick = onRetry)
                    }
                }
                state.endReached -> {
                    Icon(CineIcons.Film, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(40.dp))
                    Text(
                        text = texts.endTitle,
                        color = CineColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                    val label = texts.endActionLabel
                    if (label != null && onEndAction != null) {
                        CineSecondaryButton(text = label, onClick = onEndAction, modifier = Modifier.fillMaxWidth(0.8f))
                    }
                }
                else -> {
                    CircularProgressIndicator(color = CineColors.Red)
                    Text(stringResource(R.string.feed_loading_more), color = CineColors.TextMuted, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private const val RESTORE_TIMEOUT_MS = 2_000L
