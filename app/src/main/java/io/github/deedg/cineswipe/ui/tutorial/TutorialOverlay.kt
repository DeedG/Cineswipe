package io.github.deedg.cineswipe.ui.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

object TutorialTestTags {
    const val BUBBLE = "tuto_bubble"
    const val NEXT = "tuto_next"
    const val SHOW = "tuto_show"
    const val AUTO = "tuto_auto"
    const val MANUAL = "tuto_manual"
    const val SKIP = "tuto_skip"
    const val EXPLORE = "tuto_explore"
    const val EXIT_DEMO = "tuto_exit_demo"

    fun step(index: Int) = "tuto_step_$index"
}

/**
 * Voile du tuto au-dessus de l'appli : l'élément de l'étape reste éclairé (avec un halo qui
 * pulse), une main animée montre le geste à faire, et une bulle explique. Quand l'étape attend un
 * geste, les touchers passent sous le voile (et sous le texte de la bulle) ; sinon, seul « Suivant »
 * répond. « Montre-moi » fait jouer le geste par la main, et l'écran réagit pour de vrai ; pour
 * changer d'écran, la main touche l'onglet (ou le retour) au lieu de sauter d'un écran à l'autre.
 */
@Composable
fun TutorialOverlay(tutorial: Tutorial, onExitDemo: () -> Unit, modifier: Modifier = Modifier) {
    val step = tutorial.step ?: return
    val index = tutorial.stepIndex
    val play = tutorial.play
    val away = tutorial.away
    var origin by remember { mutableStateOf(Offset.Zero) }
    var lastHole by remember { mutableStateOf<Rect?>(null) }

    fun boundsOf(target: TutorialTarget?): Rect? = target?.let { tutorial.bounds[it] }?.translate(-origin.x, -origin.y)

    val target = when {
        play != null -> boundsOf(play.target)
        away -> null
        else -> boundsOf(step.target)
    }
    if (target != null) lastHole = target
    val holeVisible by animateFloatAsState(if (target != null) 1f else 0f, tween(250), label = "trou")
    val hole by animateRectAsState(target ?: lastHole ?: Rect.Zero, tween(380, easing = FastOutSlowInEasing), label = "cible")
    val scrim by animateFloatAsState(
        when {
            play != null -> 0.55f
            step.interactive && !away -> 0.5f
            else -> 0.72f
        },
        tween(250),
        label = "voile",
    )

    val transition = rememberInfiniteTransition(label = "tuto")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "halo")
    val gestureTime by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "geste")

    // Geste joué par le tuto : la main part du point de départ, qui ne bouge plus pendant le geste
    // (l'élément, lui, peut partir avec : une carte glissée, un écran qui change).
    val played = remember { Animatable(0f) }
    var playArea by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(play?.id) {
        val current = play ?: return@LaunchedEffect
        val area = boundsOf(current.gestureAt) ?: boundsOf(current.target)
        played.snapTo(0f)
        playArea = area
        val gesture = current.gesture
        if (area != null) {
            // La lumière arrive d'abord sur l'élément, puis le doigt fait le geste.
            delay(if (current.travel) TRAVEL_SETTLE_MS else 0L)
            played.animateTo(gesture.actAt, tween((gesture.durationMs * gesture.actAt).toInt(), easing = LinearEasing))
        }
        tutorial.act(current)
        if (area != null) {
            played.animateTo(1f, tween((gesture.durationMs * (1f - gesture.actAt)).toInt(), easing = LinearEasing))
        }
        if (!current.travel && tutorial.stepIndex == current.stepIndex) {
            // L'écran n'a pas pu faire le geste (rien à l'écran) : le tuto ne reste pas bloqué.
            val advanced = withTimeoutOrNull(DEMO_TIMEOUT_MS) { snapshotFlow { tutorial.stepIndex }.first { it != current.stepIndex } }
            if (advanced == null && tutorial.stepIndex == current.stepIndex) tutorial.next()
        }
        playArea = null
        tutorial.finish(current)
    }

    // Visite auto : chaque étape se lit le temps qu'il faut, puis la main fait le geste ou passe à
    // la suivante (pour filmer l'écran sans rien toucher).
    val autoplay = tutorial.autoplay
    val readingMs = autoDuration(step, stringResource(step.title).length + stringResource(step.body).length)
    LaunchedEffect(autoplay, index, play == null, away) {
        if (!autoplay || play != null || away) return@LaunchedEffect
        delay(readingMs)
        if (!tutorial.autoplay || tutorial.stepIndex != index) return@LaunchedEffect
        if (step.interactive) tutorial.demonstrate() else tutorial.next()
    }

    val playing = play != null
    val gestureArea = if (playing) playArea else hole.takeIf { holeVisible > 0f && it.width > 0f }?.inflate(8f * LocalDensity.current.density)
    val swipeReach = with(LocalDensity.current) { DEMO_SWIPE_DISTANCE.toPx() }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(Color.Black.copy(alpha = scrim))
            if (holeVisible > 0f && hole.width > 0f) {
                val padding = 8.dp.toPx()
                val area = hole.inflate(padding)
                val corner = CornerRadius(16.dp.toPx())
                drawRoundRect(
                    color = Color.Black.copy(alpha = holeVisible),
                    topLeft = area.topLeft,
                    size = area.size,
                    cornerRadius = corner,
                    blendMode = BlendMode.Clear,
                )
                val grow = 10.dp.toPx() * pulse
                val ring = area.inflate(grow)
                drawRoundRect(
                    color = CineColors.Red.copy(alpha = (1f - pulse) * holeVisible),
                    topLeft = ring.topLeft,
                    size = ring.size,
                    cornerRadius = CornerRadius(corner.x + grow),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawRoundRect(
                    color = CineColors.Red.copy(alpha = 0.9f * holeVisible),
                    topLeft = area.topLeft,
                    size = area.size,
                    cornerRadius = corner,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            if (play != null) {
                gestureArea?.let { drawPlayedGesture(play.gesture, it, played.value, swipeReach) }
            } else if (!away && holeVisible > 0f) {
                val area = gestureArea
                val at = boundsOf(step.gestureTarget)?.inflate(8.dp.toPx()) ?: area
                if (at != null) step.gesture?.let { drawGesture(it, at, gestureTime, holeVisible) }
            }
        }
        if (!step.interactive || playing || away) {
            // Étape d'explication, geste joué ou trajet : l'appli derrière ne réagit pas.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { consumeAll() },
            )
        }
        BubbleLayout(hole = boundsOf(step.target), placement = step.bubble) {
            AnimatedVisibility(
                visible = !playing && !away,
                enter = fadeIn(tween(220, delayMillis = 120)) + scaleIn(tween(260, delayMillis = 120), initialScale = 0.96f),
                exit = fadeOut(tween(140)),
            ) {
                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 6 } + scaleIn(tween(300), initialScale = 0.96f))
                            .togetherWith(fadeOut(tween(150)))
                    },
                    label = "bulle",
                ) { shownIndex ->
                    val shown = tutorial.steps.getOrNull(shownIndex) ?: return@AnimatedContent
                    Bubble(
                        step = shown,
                        index = shownIndex,
                        count = tutorial.steps.size,
                        last = shownIndex == tutorial.steps.lastIndex,
                        onNext = tutorial::next,
                        onShow = tutorial::demonstrate,
                        onSkip = tutorial::stop,
                        autoplay = autoplay,
                        autoMs = readingMs,
                        onAutoplay = tutorial::startAutoplay,
                        onManual = tutorial::stopAutoplay,
                        onExitDemo = {
                            tutorial.stop()
                            onExitDemo()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Durée d'une étape en visite auto : le temps de lire la bulle (environ 17 caractères par seconde,
 * entre 3 et 9 s) ; une étape à geste montre d'abord la consigne 2,5 s.
 */
fun autoDuration(step: TutorialStep, characters: Int): Long =
    if (step.interactive) AUTO_SHOW_DELAY_MS else (1_200L + characters * 55L).coerceIn(3_000L, 9_000L)

private const val AUTO_SHOW_DELAY_MS = 2_500L

/** Pause avant le toucher d'un trajet : la lumière a le temps d'arriver sur l'onglet. */
private const val TRAVEL_SETTLE_MS = 380L

/** Au-delà, une démonstration sans effet passe quand même à l'étape suivante. */
private const val DEMO_TIMEOUT_MS = 1_800L

/** Distance d'un swipe joué par le tuto ; la carte la parcourt avec le doigt (seuil du swipe : 100 dp). */
val DEMO_SWIPE_DISTANCE = 140.dp

/**
 * La bulle se place au-dessus ou en dessous de l'élément éclairé, là où il y a le plus de place, ou
 * en haut de l'écran ([BubblePlacement.TOP]) quand l'élément le remplit presque.
 */
@Composable
private fun BubbleLayout(hole: Rect?, placement: BubblePlacement, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 18.dp.roundToPx()
        val width = min(constraints.maxWidth - 2 * margin, 480.dp.roundToPx()).coerceAtLeast(0)
        val placeable = measurables.firstOrNull()?.measure(
            constraints.copy(minWidth = width, maxWidth = width, minHeight = 0),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (placeable == null) return@layout
            val height = constraints.maxHeight
            val bubble = placeable.height
            val y = when {
                placement == BubblePlacement.TOP -> margin
                hole == null -> (height - bubble) / 2
                else -> {
                    val below = hole.bottom.toInt() + gap
                    val above = hole.top.toInt() - gap - bubble
                    val roomBelow = height - margin - below
                    when {
                        roomBelow >= bubble && (height - hole.bottom) >= hole.top -> below
                        above >= margin -> above
                        roomBelow >= bubble -> below
                        // Élément très grand : la bulle se pose en bas, par-dessus.
                        else -> height - margin - bubble
                    }
                }
            }
            placeable.place((constraints.maxWidth - width) / 2, y.coerceIn(margin, (height - margin - bubble).coerceAtLeast(margin)))
        }
    }
}

@Composable
private fun Bubble(
    step: TutorialStep,
    index: Int,
    count: Int,
    last: Boolean,
    onNext: () -> Unit,
    onShow: () -> Unit,
    onSkip: () -> Unit,
    onExitDemo: () -> Unit,
    autoplay: Boolean = false,
    autoMs: Long = 0,
    onAutoplay: () -> Unit = {},
    onManual: () -> Unit = {},
) {
    val shape = RoundedCornerShape(18.dp)
    // Étape à geste : le texte de la bulle laisse passer les touchers vers l'écran (un swipe ou un
    // toucher commencé dessus marche quand même) ; seuls ses boutons répondent.
    val keepTouches = if (step.interactive) Modifier else Modifier.pointerInput(Unit) { consumeAll() }
    Column(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .shadow(24.dp, shape)
            .clip(shape)
            .background(CineColors.Toast)
            .border(1.dp, CineColors.Red.copy(alpha = 0.6f), shape)
            .then(keepTouches)
            .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
            .testTag(TutorialTestTags.BUBBLE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().testTag(TutorialTestTags.step(index))) {
            Progress(index, count, Modifier.align(Alignment.CenterVertically).weight(1f))
            if (!last) {
                TextButton(onClick = onSkip, modifier = Modifier.testTag(TutorialTestTags.SKIP)) {
                    Text(stringResource(R.string.tuto_skip), color = CineColors.TextMuted, fontSize = 13.sp)
                }
            }
        }
        Text(stringResource(step.title), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 8.dp))
        Text(
            stringResource(step.body),
            color = CineColors.TextSoft,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        when {
            autoplay && !last -> AutoplayRow(index = index, durationMs = autoMs, onManual = onManual)
            last -> Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CineSecondaryButton(
                    text = stringResource(R.string.tuto_explore),
                    onClick = onSkip,
                    modifier = Modifier.weight(1f).testTag(TutorialTestTags.EXPLORE),
                )
                CineButton(
                    text = stringResource(R.string.tuto_exit_demo),
                    onClick = onExitDemo,
                    modifier = Modifier.weight(1f).testTag(TutorialTestTags.EXIT_DEMO),
                )
            }
            step.interactive -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YourTurn(Modifier.weight(1f))
                TextButton(onClick = onShow, modifier = Modifier.testTag(TutorialTestTags.SHOW)) {
                    Icon(CineIcons.Play, contentDescription = null, tint = CineColors.TextSecondary, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.tuto_show_me),
                        color = CineColors.TextSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            else -> Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index == 0) {
                    TextButton(onClick = onAutoplay, modifier = Modifier.testTag(TutorialTestTags.AUTO)) {
                        Icon(CineIcons.Play, contentDescription = null, tint = CineColors.TextSecondary, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.tuto_autoplay),
                            color = CineColors.TextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                CineButton(
                    text = stringResource(if (index == 0) R.string.tuto_start else R.string.tuto_next),
                    onClick = onNext,
                    modifier = Modifier.testTag(TutorialTestTags.NEXT),
                )
            }
        }
    }
}

/** Visite auto : une barre qui se remplit jusqu'à l'étape suivante, et « Reprendre la main ». */
@Composable
private fun AutoplayRow(index: Int, durationMs: Long, onManual: () -> Unit) {
    val progress = remember(index) { Animatable(0f) }
    LaunchedEffect(index, durationMs) { progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(CircleShape)
                .background(CineColors.BorderStrong),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(CineColors.Red),
            )
        }
        TextButton(onClick = onManual, modifier = Modifier.testTag(TutorialTestTags.MANUAL)) {
            Text(stringResource(R.string.tuto_manual), color = CineColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** Avancement : une pastille par étape, celle en cours allongée. */
@Composable
private fun Progress(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { i ->
            val color = when {
                i == index -> CineColors.Red
                i < index -> CineColors.Red.copy(alpha = 0.45f)
                else -> CineColors.BorderStrong
            }
            Box(
                Modifier
                    .size(width = if (i == index) 14.dp else 5.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** « À toi de jouer » avec un point qui bat. */
@Composable
private fun YourTurn(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "à toi")
    val beat by transition.animateFloat(0.7f, 1.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "point")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .scale(beat)
                .clip(CircleShape)
                .background(CineColors.Red),
        )
        Text(stringResource(R.string.tuto_your_turn), color = CineColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Main animée : un doigt (cercle blanc) qui glisse dans le sens du geste en laissant une traînée,
 * ou qui appuie avec une onde pour un toucher.
 */
private fun DrawScope.drawGesture(gesture: TutorialGesture, area: Rect, time: Float, visible: Float) {
    val finger = 17.dp.toPx()
    val center = area.center
    fun fade(t: Float): Float = when {
        t < 0.12f -> t / 0.12f
        t > 0.8f -> ((1f - t) / 0.2f).coerceAtLeast(0f)
        else -> 1f
    }
    val alpha = fade(time) * visible
    when (gesture) {
        TutorialGesture.TAP -> {
            val press = if (time < 0.25f) 1f - 0.25f * (time / 0.25f) else 0.75f + 0.25f * ((time - 0.25f) / 0.75f).coerceAtMost(1f)
            if (time in 0.25f..0.85f) {
                val wave = (time - 0.25f) / 0.6f
                drawCircle(Color.White.copy(alpha = (1f - wave) * 0.6f * visible), radius = finger * (1f + 1.6f * wave), center = center, style = Stroke(3.dp.toPx()))
            }
            drawFinger(center, finger * press, alpha)
        }
        else -> {
            val reach = when (gesture) {
                TutorialGesture.SWIPE_UP -> min(area.height * 0.3f, 120.dp.toPx())
                else -> min(area.width * 0.35f, 110.dp.toPx())
            }
            val direction = when (gesture) {
                TutorialGesture.SWIPE_LEFT -> Offset(-1f, 0f)
                TutorialGesture.SWIPE_RIGHT -> Offset(1f, 0f)
                else -> Offset(0f, -1f)
            }
            val start = center - direction * reach
            val progress = ((time - 0.12f) / 0.6f).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(progress)
            val position = start + direction * (2f * reach * eased)
            drawLine(
                color = Color.White.copy(alpha = 0.35f * alpha),
                start = start,
                end = position,
                strokeWidth = finger * 1.1f,
                cap = StrokeCap.Round,
            )
            drawFinger(position, finger, alpha)
        }
    }
}

/**
 * Geste joué une fois par le tuto ([time] de 0 à 1) : le doigt arrive et appuie (avec une onde), ou
 * se pose et glisse de [reach] en emmenant l'élément, puis s'efface.
 */
private fun DrawScope.drawPlayedGesture(gesture: TutorialGesture, area: Rect, time: Float, reach: Float) {
    val finger = 17.dp.toPx()
    val center = area.center
    val fadeOut = if (time > 0.82f) ((1f - time) / 0.18f).coerceIn(0f, 1f) else 1f
    when (gesture) {
        TutorialGesture.TAP -> {
            val press = gesture.actAt
            val approach = press - 0.1f
            val arrive = (time / approach).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(arrive)
            val alpha = (arrive * 2f).coerceAtMost(1f) * fadeOut
            // Le doigt arrive d'en bas à droite, un peu plus gros (il « descend » vers l'écran), puis appuie.
            val from = center + Offset(28.dp.toPx(), 44.dp.toPx())
            val position = from + (center - from) * eased
            val radius = when {
                time < approach -> finger * (1.25f - 0.25f * eased)
                time < press -> finger * (1f - 0.2f * ((time - approach) / 0.1f))
                else -> finger * (0.8f + 0.2f * ((time - press) / 0.15f).coerceAtMost(1f))
            }
            if (time >= press) {
                val wave = ((time - press) / (1f - press)).coerceIn(0f, 1f)
                val waveRadius = finger * (1f + 2.2f * wave)
                drawCircle(Color.White.copy(alpha = (1f - wave) * 0.18f), radius = waveRadius, center = center)
                drawCircle(CineColors.Red.copy(alpha = (1f - wave) * 0.75f), radius = waveRadius, center = center, style = Stroke(3.dp.toPx()))
            }
            drawFinger(position, radius, alpha)
        }
        else -> {
            val direction = when (gesture) {
                TutorialGesture.SWIPE_LEFT -> Offset(-1f, 0f)
                TutorialGesture.SWIPE_RIGHT -> Offset(1f, 0f)
                else -> Offset(0f, -1f)
            }
            val up = gesture == TutorialGesture.SWIPE_UP
            val distance = if (up) min(area.height * 0.55f, 260.dp.toPx()) else reach
            val start = if (up) center - direction * (distance / 2f) else center
            val moveEnd = gesture.actAt + gesture.moveMs.toFloat() / gesture.durationMs
            val progress = ((time - gesture.actAt) / (moveEnd - gesture.actAt)).coerceIn(0f, 1f)
            val position = start + direction * (distance * FastOutSlowInEasing.transform(progress))
            val alpha = (time / gesture.actAt).coerceAtMost(1f) * fadeOut
            if (progress > 0f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.35f * alpha),
                    start = start,
                    end = position,
                    strokeWidth = finger * 1.1f,
                    cap = StrokeCap.Round,
                )
            }
            // Le doigt se pose (il rétrécit un peu) avant de glisser.
            val landing = if (time < gesture.actAt) 1.2f - 0.2f * (time / gesture.actAt) else 1f
            drawFinger(position, finger * landing, alpha)
        }
    }
}

private fun DrawScope.drawFinger(center: Offset, radius: Float, alpha: Float) {
    drawCircle(Color.Black.copy(alpha = 0.25f * alpha), radius = radius * 1.15f, center = center + Offset(0f, radius * 0.15f))
    drawCircle(Color.White.copy(alpha = 0.92f * alpha), radius = radius, center = center)
    drawCircle(CineColors.Red.copy(alpha = alpha), radius = radius, center = center, style = Stroke(2.5f * density))
}

/** Absorbe tous les touchers (les boutons à l'intérieur les reçoivent d'abord). */
private suspend fun PointerInputScope.consumeAll() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
