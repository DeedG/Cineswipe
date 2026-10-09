package io.github.deedg.cineswipe.ui.feed

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.repository.CardAction
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.reco.ReasonKind
import io.github.deedg.cineswipe.domain.reco.RecoReason
import io.github.deedg.cineswipe.ui.components.ActorAvatar
import io.github.deedg.cineswipe.ui.components.BlurredBackdrop
import io.github.deedg.cineswipe.ui.components.DirectedBy
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.RatingPill
import io.github.deedg.cineswipe.ui.components.StatusBadge
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.DEMO_SWIPE_DISTANCE
import io.github.deedg.cineswipe.ui.tutorial.LocalTutorial
import io.github.deedg.cineswipe.ui.tutorial.TutorialEvent
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** Repères pour les tests d'interface. */
object FeedTestTags {
    fun card(tmdbId: Long): String = "feed-card-$tmdbId"

    /** Ligne « Parce que tu as aimé… » des recos. */
    const val REASON = "feed-reason"
}

/** Distance à dépasser pour valider un swipe (100 px en V4). */
private val SwipeThreshold = 100.dp

/** Un geste rapide valide aussi le swipe, à partir d'un tiers de la distance. */
private val FlingVelocity = 900.dp

private const val CARD_CAST = 5

/** Place de la carte : tout, sans les acteurs, ou l'essentiel (titre et boutons). */
internal enum class CardDensity { FULL, COMPACT, TINY }

private val FULL_CARD_HEIGHT = 700.dp
private val COMPACT_CARD_HEIGHT = 520.dp

private val SpringBack = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

/** Ce que déclenche un swipe, avec le fond révélé sous la carte. */
private enum class SwipeKind(
    val color: Color,
    val icon: () -> ImageVector,
    @param:StringRes val label: Int,
) {
    WATCHED(CineColors.SwipeLeft, { CineIcons.Eye }, R.string.swipe_watched),
    WATCHLIST(CineColors.SwipeRight, { CineIcons.Bookmark }, R.string.swipe_watchlist),
    REQUEST(CineColors.SwipeRight, { CineIcons.Download }, R.string.swipe_request),
    CONFIRM_REMOVE(CineColors.Warning, { CineIcons.EyeOff }, R.string.swipe_remove_confirm),
    REMOVE(CineColors.Red, { CineIcons.Trash }, R.string.swipe_remove),
    ALREADY_WATCHED(CineColors.SurfaceHigh, { CineIcons.Eye }, R.string.swipe_already_watched),
    ALREADY_LISTED(CineColors.SurfaceHigh, { CineIcons.Bookmark }, R.string.swipe_already_listed),
}

/**
 * Carte plein écran du feed, comme en V4 :
 * - swipe gauche = Vu, swipe droit = À voir ;
 * - avec Seerr ou Radarr ([requestService]), swipe droit = Demander : le film passe dans la liste
 *   À voir et part au téléchargement (sauf s'il est déjà sur le serveur ou déjà demandé) ;
 * - sur un film déjà vu, il faut deux swipes à gauche pour le retirer (orange, puis rouge) ;
 * - « Pas pour moi » écarte le film définitivement.
 *
 * [onAction] est appelé une fois la carte sortie de l'écran.
 */
@Composable
fun SwipeableMovieCard(
    card: FeedCardUi,
    showAvailability: Boolean,
    requestService: HomelabService?,
    onAction: (CardAction) -> Unit,
    onLightning: () -> Unit,
    onOpenMovie: () -> Unit,
    modifier: Modifier = Modifier,
    /** Carte à l'écran : le tuto de la démo peut la montrer. */
    highlight: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val toaster = LocalToaster.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { SwipeThreshold.toPx() }
    val flingPx = with(density) { FlingVelocity.toPx() }
    val swipeAgainMessage = stringResource(R.string.toast_swipe_again)
    val alreadyWatchedMessage = stringResource(R.string.toast_already_watched)
    val alreadyListedMessage = stringResource(R.string.toast_already_listed)
    val openDetailLabel = stringResource(R.string.card_open_detail, card.movie.title)
    val watchedLabel = stringResource(R.string.swipe_watched)
    val watchlistLabel = stringResource(R.string.swipe_watchlist)
    val requestLabel = stringResource(R.string.swipe_request)

    val offset = remember(card.tmdbId) { Animatable(0f) }
    val dismissProgress = remember(card.tmdbId) { Animatable(0f) }
    var pendingRemoval by remember(card.tmdbId) { mutableStateOf(false) }
    var committed by remember(card.tmdbId) { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    val leftKind = when {
        card.status == WatchStatus.WATCHED && pendingRemoval -> SwipeKind.REMOVE
        card.status == WatchStatus.WATCHED -> SwipeKind.CONFIRM_REMOVE
        else -> SwipeKind.WATCHED
    }
    val rightKind = when {
        card.status == WatchStatus.WATCHED -> SwipeKind.ALREADY_WATCHED
        card.status == WatchStatus.WATCHLIST -> SwipeKind.ALREADY_LISTED
        requestService != null && card.availability == Availability.NONE -> SwipeKind.REQUEST
        else -> SwipeKind.WATCHLIST
    }
    // Action d'accessibilité du swipe droit : « Demander » ou « À voir ».
    val rightAction = if (rightKind == SwipeKind.REQUEST) CardAction.REQUEST else CardAction.WATCHLIST
    val rightLabel = if (rightKind == SwipeKind.REQUEST) requestLabel else watchlistLabel
    val currentLeftKind by rememberUpdatedState(leftKind)
    val currentRightKind by rememberUpdatedState(rightKind)
    val currentOnAction by rememberUpdatedState(onAction)

    suspend fun commit(direction: Int, action: CardAction) {
        committed = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        offset.animateTo(direction * max(widthPx, thresholdPx) * 1.2f, tween(220, easing = FastOutLinearInEasing))
        currentOnAction(action)
    }

    suspend fun settle(velocity: Float) {
        val value = offset.value
        val direction = when {
            value <= -thresholdPx || (velocity < -flingPx && value < -thresholdPx / 3) -> -1
            value >= thresholdPx || (velocity > flingPx && value > thresholdPx / 3) -> 1
            else -> 0
        }
        if (direction == 0) {
            // Relâché trop tôt : on annule aussi l'avertissement « Retirer ? », comme en V4.
            pendingRemoval = false
            offset.animateTo(0f, SpringBack)
            return
        }
        val kind = if (direction < 0) currentLeftKind else currentRightKind
        if (direction > 0) pendingRemoval = false
        when (kind) {
            SwipeKind.WATCHED -> commit(direction, CardAction.WATCHED)
            SwipeKind.WATCHLIST -> commit(direction, CardAction.WATCHLIST)
            SwipeKind.REQUEST -> commit(direction, CardAction.REQUEST)
            SwipeKind.REMOVE -> commit(direction, CardAction.UNWATCH)
            SwipeKind.CONFIRM_REMOVE -> {
                pendingRemoval = true
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                toaster.show(swipeAgainMessage)
                offset.animateTo(0f, SpringBack)
            }
            SwipeKind.ALREADY_WATCHED, SwipeKind.ALREADY_LISTED -> {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                toaster.show(if (kind == SwipeKind.ALREADY_WATCHED) alreadyWatchedMessage else alreadyListedMessage)
                offset.animateTo(0f, SpringBack)
            }
        }
    }

    val dismiss = {
        if (!committed) {
            committed = true
            scope.launch {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                dismissProgress.animateTo(1f, tween(200))
                currentOnAction(CardAction.DISMISS)
            }
        }
    }

    // « Montre-moi » du tuto : la carte glisse sous le doigt de la main, puis part comme un vrai swipe.
    val tutorial = LocalTutorial.current
    val demoDistancePx = with(density) { DEMO_SWIPE_DISTANCE.toPx() }
    LaunchedEffect(tutorial, highlight, card.tmdbId) {
        if (tutorial == null || !highlight) return@LaunchedEffect
        tutorial.handle(TutorialEvent.RECO_WATCHED, TutorialEvent.RECO_WATCHLIST) { request ->
            if (committed) return@handle
            val direction = if (request.event == TutorialEvent.RECO_WATCHED) -1 else 1
            offset.animateTo(direction * demoDistancePx, tween(request.moveMs.coerceAtLeast(1), easing = FastOutSlowInEasing))
            settle(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(FeedTestTags.card(card.tmdbId))
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(watchedLabel) {
                        if (currentLeftKind == SwipeKind.WATCHED) currentOnAction(CardAction.WATCHED)
                        currentLeftKind == SwipeKind.WATCHED
                    },
                    CustomAccessibilityAction(rightLabel) {
                        val possible = currentRightKind == SwipeKind.WATCHLIST || currentRightKind == SwipeKind.REQUEST
                        if (possible) currentOnAction(rightAction)
                        possible
                    },
                )
            }
            .pointerInput(card.tmdbId) {
                val tracker = VelocityTracker()
                var total = 0f
                var beyond = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        total = offset.value
                        beyond = false
                    },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().x
                        scope.launch { if (!committed) settle(velocity) }
                    },
                    onDragCancel = {
                        scope.launch { if (!committed) offset.animateTo(0f, SpringBack) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (!committed) {
                            change.consume()
                            tracker.addPointerInputChange(change)
                            total += dragAmount
                            val target = total
                            scope.launch { offset.snapTo(target) }
                            val nowBeyond = abs(target) > thresholdPx
                            if (nowBeyond && !beyond) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            beyond = nowBeyond
                        }
                    },
                )
            },
    ) {
        val direction by remember(card.tmdbId) { derivedStateOf { sign(offset.value) } }
        if (direction != 0f) {
            SwipeUnderlay(
                kind = if (direction > 0f) rightKind else leftKind,
                revealStart = direction > 0f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (abs(offset.value) / thresholdPx).coerceIn(0f, 1f) },
            )
        }
        // Un toucher n'importe où sur la carte (hors boutons et synopsis) ouvre la fiche du film.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    val progress = dismissProgress.value
                    alpha = 1f - progress
                    scaleX = 1f - 0.08f * progress
                    scaleY = 1f - 0.08f * progress
                }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = openDetailLabel,
                    role = Role.Button,
                    onClick = onOpenMovie,
                ),
        ) {
            CardContent(
                movie = card.movie,
                status = card.status,
                availability = card.availability.takeIf { showAvailability } ?: Availability.NONE,
                reason = card.reason,
                pendingRemoval = pendingRemoval,
                onDismiss = dismiss,
                onLightning = onLightning,
                highlight = highlight,
            )
        }
    }
}

@Composable
private fun SwipeUnderlay(kind: SwipeKind, revealStart: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(kind.color)) {
        Column(
            modifier = Modifier
                .align(if (revealStart) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(kind.label), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CardContent(
    movie: MovieEntity,
    status: WatchStatus,
    availability: Availability,
    reason: RecoReason?,
    pendingRemoval: Boolean,
    onDismiss: () -> Unit,
    onLightning: () -> Unit,
    highlight: Boolean,
) {
    Box(Modifier.fillMaxSize().background(CineColors.Black)) {
        BlurredBackdrop(path = movie.posterPath, modifier = Modifier.fillMaxSize(), dim = 0.6f)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.1f),
                        0.4f to Color.Black.copy(alpha = 0.6f),
                        1f to Color.Black,
                    ),
                ),
        )
        // Écran court (petit téléphone, pliant ouvert en paysage, écran externe) : la carte se
        // resserre pour que l'affiche garde sa place.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = when {
                maxHeight >= FULL_CARD_HEIGHT -> CardDensity.FULL
                maxHeight >= COMPACT_CARD_HEIGHT -> CardDensity.COMPACT
                else -> CardDensity.TINY
            }
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = if (density == CardDensity.TINY) 48.dp else 56.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.7f), contentAlignment = Alignment.Center) {
                        PosterImage(
                            path = movie.posterPath,
                            imageSize = TmdbImageSize.Poster,
                            contentDescription = stringResource(R.string.poster_description, movie.title),
                            modifier = Modifier
                                .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                                .shadow(30.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .tutorialTarget(TutorialTarget.RECO_POSTER, highlight),
                        )
                    }
                }
                CardInfo(movie = movie, reason = reason, onDismiss = onDismiss, onLightning = onLightning, highlight = highlight, density = density)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 15.dp, end = 15.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status == WatchStatus.WATCHED) {
                if (pendingRemoval) {
                    StatusBadge(stringResource(R.string.card_confirm), CineColors.Warning, icon = CineIcons.Alert)
                } else {
                    StatusBadge(stringResource(R.string.badge_watched), CineColors.Red, icon = CineIcons.Eye)
                }
            }
            if (status == WatchStatus.WATCHLIST) {
                StatusBadge(stringResource(R.string.badge_watchlist), CineColors.TmdbBlue, icon = CineIcons.Bookmark)
            }
            when (availability) {
                Availability.AVAILABLE -> StatusBadge(stringResource(R.string.badge_available), CineColors.TextSecondary, icon = CineIcons.Server)
                Availability.REQUESTED -> StatusBadge(stringResource(R.string.badge_requested), CineColors.Warning)
                Availability.NONE -> Unit
            }
        }
    }
}

@Composable
private fun CardInfo(
    movie: MovieEntity,
    reason: RecoReason?,
    onDismiss: () -> Unit,
    onLightning: () -> Unit,
    highlight: Boolean,
    density: CardDensity = CardDensity.FULL,
) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable(movie.tmdbId) { mutableStateOf(false) }
    var overflowing by remember(movie.tmdbId) { mutableStateOf(false) }
    val title = buildAnnotatedString {
        append(movie.title)
        movie.year?.let { year ->
            withStyle(SpanStyle(fontSize = 20.sp, color = CineColors.TextMuted, fontWeight = FontWeight.Normal)) {
                append(" ($year)")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        reason?.let { ReasonLine(it) }
        Text(
            text = title,
            color = Color.White,
            fontSize = if (density == CardDensity.FULL) 28.sp else 23.sp,
            lineHeight = if (density == CardDensity.FULL) 31.sp else 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = if (density == CardDensity.FULL) 3 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        DirectedBy(movie, color = Color(0xFFCCCCCC), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
        movie.tmdbRating?.takeIf { density != CardDensity.TINY }?.let {
            RatingPill(
                label = stringResource(R.string.tmdb_label),
                value = String.format(Locale.FRANCE, "%.1f", it),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (movie.cast.isNotEmpty() && density == CardDensity.FULL) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                movie.cast.take(CARD_CAST).forEach { ActorAvatar(it) }
            }
        }
        val overview = movie.overview ?: stringResource(R.string.detail_no_overview)
        if (density == CardDensity.TINY) {
            // Pas de place pour le synopsis : il est sur la fiche (toucher l'affiche).
        } else if (expanded) {
            Text(
                text = overview,
                color = CineColors.TextSoft,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable { expanded = false },
            )
        } else {
            Text(
                text = overview,
                color = CineColors.TextSoft,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = if (density == CardDensity.FULL) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflowing = it.hasVisualOverflow },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable(enabled = overflowing) { expanded = true },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .tutorialTarget(TutorialTarget.RECO_ACTIONS, highlight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                if (overflowing || expanded) {
                    Text(
                        text = stringResource(if (expanded) R.string.detail_less else R.string.detail_more),
                        color = CineColors.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 5.dp),
                    )
                }
            }
            CardPill(
                text = stringResource(R.string.card_not_for_me),
                icon = CineIcons.ThumbsDown,
                onClick = onDismiss,
                background = Color(0xCC1E1E1E),
                border = CineColors.BorderSoft,
            )
            movie.trailerKey?.let { key ->
                CardPill(
                    text = stringResource(R.string.card_trailer),
                    icon = CineIcons.Play,
                    onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/watch?v=$key") } },
                    background = CineColors.Red,
                    border = CineColors.Red,
                )
            }
            LightningButton(onClick = onLightning)
        }
        if (movie.genres.isNotEmpty()) {
            Text(
                text = movie.genres.joinToString(", "),
                color = CineColors.TextMuted,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            )
        }
    }
}

/** Pourquoi le film est proposé (« Parce que tu as aimé Drive »). */
@Composable
private fun ReasonLine(reason: RecoReason) {
    val subject = reason.subject
    val text = when (reason.kind) {
        ReasonKind.LIKED -> subject?.let { stringResource(R.string.reason_liked, it) }
        ReasonKind.CLOSE_TO -> subject?.let { stringResource(R.string.reason_close_to, it) }
        ReasonKind.CLASSIC -> stringResource(R.string.reason_classic)
        ReasonKind.GEM -> stringResource(R.string.reason_gem)
        ReasonKind.TRENDING -> stringResource(R.string.reason_trending)
        ReasonKind.DIRECTOR -> subject?.let { stringResource(R.string.reason_director, it) }
        ReasonKind.ACTOR -> subject?.let { stringResource(R.string.reason_actor, it) }
        ReasonKind.THEME -> subject?.let { stringResource(R.string.reason_theme, it) }
    } ?: stringResource(R.string.reason_for_you)
    val icon = when (reason.kind) {
        ReasonKind.LIKED, ReasonKind.CLOSE_TO -> CineIcons.Heart
        ReasonKind.CLASSIC -> CineIcons.Award
        ReasonKind.GEM -> CineIcons.Gem
        ReasonKind.TRENDING -> CineIcons.TrendingUp
        ReasonKind.DIRECTOR, ReasonKind.ACTOR -> CineIcons.Profile
        ReasonKind.THEME -> CineIcons.Hash
    }
    Row(
        modifier = Modifier.padding(bottom = 6.dp).testTag(FeedTestTags.REASON),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            color = CineColors.TextSoft,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    background: Color,
    border: Color,
) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LightningButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color(0xCC1E1E1E))
            .border(1.dp, CineColors.BorderSoft, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CineIcons.Lightning,
            contentDescription = stringResource(R.string.card_lightning),
            tint = CineColors.Lightning,
            modifier = Modifier.size(18.dp),
        )
    }
}
