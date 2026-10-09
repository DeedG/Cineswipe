package io.github.deedg.cineswipe.ui.arena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.Fighter
import io.github.deedg.cineswipe.data.model.RankingReliability
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.EloBadge
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.GrayscaleFilter
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.LocalTutorial
import io.github.deedg.cineswipe.ui.tutorial.TutorialEvent
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import kotlin.math.roundToInt

/** Repères pour les tests d'interface. */
object ArenaTestTags {
    const val FIGHTER_A = "arena-fighter-a"
    const val FIGHTER_B = "arena-fighter-b"
    const val DRAW = "arena-draw"
    const val RELIABILITY = "arena-reliability"
    const val FOCUS = "arena-focus"
}

@Composable
fun ArenaRoute(onOpenCensus: () -> Unit) {
    val viewModel = cineViewModel { ArenaViewModel(it.arena, it.library) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val reliability by viewModel.reliability.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    var showEloInfo by rememberSaveable { mutableStateOf(false) }

    val toaster = LocalToaster.current
    val undoneMessage = stringResource(R.string.arena_undone)
    val placedFormat = stringResource(R.string.arena_focus_done)
    val focusBanner by viewModel.focusBanner.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshIfNeeded() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ArenaEvent.Undone -> toaster.show(undoneMessage)
                is ArenaEvent.Placed -> toaster.show(placedFormat.format(event.title))
            }
        }
    }

    val tutorial = LocalTutorial.current
    // « Montre-moi » du tuto : la main touche le film du haut, qui gagne son duel.
    LaunchedEffect(tutorial) {
        tutorial?.handle(TutorialEvent.ARENA_VOTED) {
            if (viewModel.state.value is ArenaUiState.Ready) {
                tutorial.event(TutorialEvent.ARENA_VOTED)
                viewModel.vote(Side.A)
            }
        }
    }
    ArenaScreen(
        state = state,
        canUndo = canUndo,
        reliability = reliability,
        onVote = { side ->
            tutorial?.event(TutorialEvent.ARENA_VOTED)
            viewModel.vote(side)
        },
        onDraw = {
            tutorial?.event(TutorialEvent.ARENA_VOTED)
            viewModel.draw()
        },
        onSkip = viewModel::skip,
        onUndo = viewModel::undo,
        onInfo = viewModel::showInfo,
        onEloInfo = { showEloInfo = true },
        onOpenCensus = onOpenCensus,
        focusBanner = focusBanner,
        onStopFocus = viewModel::stopFocus,
    )

    info?.let { MovieInfoSheet(details = it, onDismiss = viewModel::hideInfo) }
    if (showEloInfo) EloInfoDialog(onDismiss = { showEloInfo = false })
}

@Composable
fun ArenaScreen(
    state: ArenaUiState,
    canUndo: Boolean,
    reliability: RankingReliability?,
    onVote: (Side) -> Unit,
    onDraw: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    onInfo: (Long) -> Unit,
    onEloInfo: () -> Unit,
    onOpenCensus: () -> Unit,
    focusBanner: FocusBanner? = null,
    onStopFocus: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(CineColors.RedDeep, CineColors.Black),
                        center = center,
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
    ) {
        when (state) {
            ArenaUiState.Loading -> ArenaLoading()
            is ArenaUiState.NotEnough -> EmptyState(
                icon = CineIcons.Arena,
                title = stringResource(R.string.arena_not_enough_title),
                message = stringResource(R.string.arena_not_enough_message),
                actionLabel = stringResource(R.string.census_cta),
                onAction = onOpenCensus,
                modifier = Modifier.statusBarsPadding(),
            )
            is ArenaUiState.Ready -> Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 15.dp),
            ) {
                ArenaHeader(
                    canUndo = canUndo,
                    reliability = reliability,
                    onUndo = onUndo,
                    onSkip = onSkip,
                    onEloInfo = onEloInfo,
                )
                if (focusBanner != null) FocusBannerRow(focusBanner, onStop = onStopFocus)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 15.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize().tutorialTarget(TutorialTarget.ARENA_DUEL),
                        verticalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        FighterCard(
                            fighter = state.match.a,
                            round = state.round,
                            isWinner = state.winner == Side.A,
                            isLoser = state.winner == Side.B,
                            isDraw = state.draw,
                            enterFromTop = true,
                            onVote = { onVote(Side.A) },
                            onInfo = { onInfo(state.match.a.tmdbId) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(ArenaTestTags.FIGHTER_A)
                                .tutorialTarget(TutorialTarget.ARENA_FIGHTER),
                        )
                        FighterCard(
                            fighter = state.match.b,
                            round = state.round,
                            isWinner = state.winner == Side.B,
                            isLoser = state.winner == Side.A,
                            isDraw = state.draw,
                            enterFromTop = false,
                            onVote = { onVote(Side.B) },
                            onInfo = { onInfo(state.match.b.tmdbId) },
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag(ArenaTestTags.FIGHTER_B),
                        )
                    }
                    VsBadge(Modifier.align(Alignment.Center))
                    GlassPillButton(
                        text = stringResource(R.string.arena_draw),
                        icon = CineIcons.Equal,
                        onClick = onDraw,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .testTag(ArenaTestTags.DRAW)
                            .tutorialTarget(TutorialTarget.ARENA_DRAW),
                    )
                }
            }
        }
    }
}

/** Placement d'un film tout juste vu : « Placement de Dune · duel 2 sur 3 ». */
@Composable
private fun FocusBannerRow(banner: FocusBanner, onStop: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(CineColors.Red.copy(alpha = 0.15f))
            .border(1.dp, CineColors.Red.copy(alpha = 0.5f), shape)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
            .testTag(ArenaTestTags.FOCUS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CineIcons.Bell, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.arena_focus, banner.title, banner.duel, banner.total),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
            Icon(CineIcons.Close, contentDescription = stringResource(R.string.arena_focus_stop), tint = CineColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ArenaLoading() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = CineColors.Red)
        Text(stringResource(R.string.arena_loading), color = CineColors.TextMuted)
    }
}

@Composable
private fun ArenaHeader(
    canUndo: Boolean,
    reliability: RankingReliability?,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    onEloInfo: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        AnimatedVisibility(
            visible = canUndo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            GlassPillButton(text = stringResource(R.string.arena_undo), icon = CineIcons.Undo, onClick = onUndo)
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.arena_title),
                color = CineColors.Red,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            if (reliability != null && reliability.films >= 2) {
                // Confiance moyenne dans les étoiles : elle monte à chaque duel.
                Text(
                    text = stringResource(R.string.arena_reliability, (reliability.value * 100).roundToInt()),
                    color = CineColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClickLabel = stringResource(R.string.arena_elo_info), onClick = onEloInfo)
                        .testTag(ArenaTestTags.RELIABILITY)
                        .tutorialTarget(TutorialTarget.ARENA_RELIABILITY),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(icon = CineIcons.Skip, contentDescription = stringResource(R.string.arena_skip), onClick = onSkip)
            GlassIconButton(icon = CineIcons.Info, contentDescription = stringResource(R.string.arena_elo_info), onClick = onEloInfo)
        }
    }
}

private val FighterShape = RoundedCornerShape(20.dp)
private val EntranceEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

@Composable
private fun FighterCard(
    fighter: Fighter,
    round: Int,
    isWinner: Boolean,
    isLoser: Boolean,
    isDraw: Boolean,
    enterFromTop: Boolean,
    onVote: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entrance = remember(round) { Animatable(0f) }
    LaunchedEffect(round) { entrance.animateTo(1f, tween(durationMillis = 500, easing = EntranceEasing)) }
    val scale by animateFloatAsState(
        targetValue = when {
            isWinner -> 1.05f
            isLoser -> 0.95f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "fighterScale",
    )
    val dim by animateFloatAsState(targetValue = if (isLoser) 0.6f else 0f, label = "fighterDim")

    Box(
        modifier = modifier
            .graphicsLayer {
                val offset = 50.dp.toPx() * (1f - entrance.value)
                translationY = if (enterFromTop) -offset else offset
                alpha = entrance.value
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isWinner) 24.dp else 10.dp,
                shape = FighterShape,
                ambientColor = if (isWinner) CineColors.Red else Color.Black,
                spotColor = if (isWinner) CineColors.Red else Color.Black,
            )
            .clip(FighterShape)
            .border(
                width = if (isWinner || isDraw) 2.dp else 1.dp,
                color = when {
                    isWinner -> CineColors.Red
                    isDraw -> Color.White.copy(alpha = 0.85f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                shape = FighterShape,
            )
            .background(CineColors.Card)
            .clickable(role = Role.Button, onClick = onVote),
    ) {
        PosterImage(
            path = fighter.posterPath,
            imageSize = TmdbImageSize.Poster,
            contentDescription = stringResource(R.string.poster_description, fighter.title),
            modifier = Modifier.fillMaxSize(),
            alpha = 0.85f,
            colorFilter = if (isLoser) GrayscaleFilter else null,
        )
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .padding(start = 20.dp, end = 16.dp, top = 40.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = fighter.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 25.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfoButton(title = fighter.title, onClick = onInfo)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EloBadge(rating = fighter.rating, large = true)
                StarRow(stars = fighter.stars, provisional = fighter.provisional, starSize = 16.dp)
            }
        }
    }
}

@Composable
private fun InfoButton(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xD90A0A0A))
            .border(1.5.dp, CineColors.Red, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CineIcons.InfoSmall,
            contentDescription = stringResource(R.string.arena_movie_info, title),
            tint = CineColors.Red,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun VsBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vs")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "vsPulse",
    )
    Box(
        modifier = modifier
            .size(60.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .shadow(20.dp, CircleShape, ambientColor = CineColors.Red, spotColor = CineColors.Red)
            .background(CineColors.Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.arena_vs),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )
    }
}
