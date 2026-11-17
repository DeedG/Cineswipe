package io.github.deedg.cineswipe.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.domain.journey.JourneyState
import io.github.deedg.cineswipe.domain.journey.JourneyStep
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

object JourneyTestTags {
    const val SCREEN = "journey_screen"
    const val CONTINUE = "journey_continue"
    const val SKIP = "journey_skip"
    const val BANNER = "journey_banner"
    const val BANNER_ACTION = "journey_banner_action"

    fun step(step: JourneyStep) = "journey_step_${step.name.lowercase()}"
}

/** Où en est une étape dans la liste : faite, en cours, ou à venir. */
enum class StepStatus { DONE, CURRENT, TODO }

/**
 * Une étape du parcours : son numéro (ou une coche une fois faite), ce qu'on y fait et, pour
 * l'étape en cours, l'avancement vers l'objectif.
 */
@Composable
fun JourneyStepRow(
    number: Int,
    title: String,
    body: String,
    status: StepStatus,
    modifier: Modifier = Modifier,
    count: String? = null,
    progress: Float? = null,
) {
    val active = status != StepStatus.TODO
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (status == StepStatus.TODO) CineColors.SurfaceHigh else CineColors.Red),
            contentAlignment = Alignment.Center,
        ) {
            if (status == StepStatus.DONE) {
                Icon(CineIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Text(number.toString(), color = if (active) Color.White else CineColors.TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = if (active) Color.White else CineColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(body, color = if (active) CineColors.TextSecondary else CineColors.TextFaint, fontSize = 14.sp, lineHeight = 20.sp)
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                JourneyProgressBar(progress)
            }
            if (count != null) {
                Text(count, color = if (status == StepStatus.DONE) CineColors.TextMuted else CineColors.RedLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun JourneyProgressBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        color = CineColors.Red,
        trackColor = CineColors.BorderStrong,
        drawStopIndicator = {},
    )
}

private fun statusOf(step: JourneyStep, state: JourneyState): StepStatus = when {
    step == state.step -> StepStatus.CURRENT
    step.ordinal < state.step.ordinal -> StepStatus.DONE
    else -> StepStatus.TODO
}

/**
 * Écran des recos tant que le parcours n'est pas fait : les trois étapes, l'avancement, un bouton
 * vers l'étape en cours, et de quoi voir les recos quand même.
 */
@Composable
fun JourneyScreen(
    state: JourneyState,
    onCensus: () -> Unit,
    onArena: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    topEnd: @Composable () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .testTag(JourneyTestTags.SCREEN),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { topEnd() }
            Text(stringResource(R.string.journey_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.journey_intro), color = CineColors.TextSoft, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(2.dp))
            val census = statusOf(JourneyStep.CENSUS, state)
            JourneyStepRow(
                number = 1,
                title = stringResource(R.string.journey_census_title),
                body = stringResource(R.string.journey_census_body),
                status = census,
                count = stringResource(R.string.journey_census_count, state.watched, state.watchedGoal),
                progress = state.censusProgress.takeIf { census == StepStatus.CURRENT },
                modifier = Modifier.testTag(JourneyTestTags.step(JourneyStep.CENSUS)),
            )
            val arena = statusOf(JourneyStep.ARENA, state)
            JourneyStepRow(
                number = 2,
                title = stringResource(R.string.journey_arena_title),
                body = stringResource(R.string.journey_arena_body),
                status = arena,
                count = stringResource(R.string.journey_arena_count, state.duels, state.duelsGoal).takeIf { arena != StepStatus.TODO },
                progress = state.arenaProgress.takeIf { arena == StepStatus.CURRENT },
                modifier = Modifier.testTag(JourneyTestTags.step(JourneyStep.ARENA)),
            )
            JourneyStepRow(
                number = 3,
                title = stringResource(R.string.journey_recos_title),
                body = stringResource(R.string.journey_recos_body),
                status = StepStatus.TODO,
                modifier = Modifier.testTag(JourneyTestTags.step(JourneyStep.RECOS)),
            )
            Spacer(Modifier.height(6.dp))
            val toCensus = state.step == JourneyStep.CENSUS
            val label = when {
                toCensus && state.watched == 0 -> R.string.journey_cta_census
                toCensus -> R.string.journey_cta_census_continue
                state.duels == 0 -> R.string.journey_cta_arena
                else -> R.string.journey_cta_arena_continue
            }
            CineButton(
                text = stringResource(label),
                icon = if (toCensus) CineIcons.Grid else CineIcons.Arena,
                onClick = if (toCensus) onCensus else onArena,
                modifier = Modifier.fillMaxWidth().testTag(JourneyTestTags.CONTINUE),
            )
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(JourneyTestTags.SKIP)) {
                Text(stringResource(R.string.journey_skip), color = CineColors.TextMuted, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Bandeau du parcours en haut du recensement et de l'Arène ([screen]) : l'étape en cours, son
 * avancement, et la consigne de l'écran ([hint]) quand c'est ici que ça se passe. Si l'étape en
 * cours se joue ailleurs, un bouton y mène ([onGo]). Rien une fois le parcours terminé, sauf
 * [justFinished] : l'Arène annonce que les recos sont prêtes.
 */
@Composable
fun JourneyBanner(
    state: JourneyState,
    screen: JourneyStep,
    hint: String,
    onGo: (JourneyStep) -> Unit,
    modifier: Modifier = Modifier,
    justFinished: Boolean = false,
) {
    val step = state.step
    if (step == JourneyStep.RECOS && !(justFinished && screen == JourneyStep.ARENA)) return
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE6101010))
            .border(1.dp, CineColors.Red.copy(alpha = 0.55f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag(JourneyTestTags.BANNER),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when {
            // C'est ici que ça se passe : l'étape, la consigne, l'avancement.
            step == screen -> {
                val census = screen == JourneyStep.CENSUS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.journey_step, if (census) 1 else 2),
                        color = CineColors.RedLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (census) {
                            stringResource(R.string.journey_census_count, state.watched, state.watchedGoal)
                        } else {
                            stringResource(R.string.journey_arena_count, state.duels, state.duelsGoal)
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                JourneyProgressBar(if (census) state.censusProgress else state.arenaProgress)
                Text(hint, color = CineColors.TextSoft, fontSize = 13.sp, lineHeight = 18.sp)
            }
            // L'étape de cet écran est faite : la suite est ailleurs.
            else -> {
                val (title, body, action) = when {
                    step == JourneyStep.RECOS -> Triple(R.string.journey_arena_done, R.string.journey_arena_done_body, R.string.journey_arena_done_action)
                    step == JourneyStep.ARENA -> Triple(R.string.journey_census_done, R.string.journey_census_done_body, R.string.journey_census_done_action)
                    // Arène ouverte avant la fin du recensement.
                    else -> Triple(R.string.journey_census_title, null, R.string.journey_back_to_census)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(title), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            if (body != null) {
                                stringResource(body)
                            } else {
                                stringResource(R.string.journey_step, 1) + " · " + stringResource(R.string.journey_census_count, state.watched, state.watchedGoal)
                            },
                            color = CineColors.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Text(
                        stringResource(action),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(CineColors.Red)
                            .clickable(role = Role.Button) { onGo(step) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag(JourneyTestTags.BANNER_ACTION),
                    )
                }
            }
        }
    }
}

/** Parcours en cours, pour les écrans qui affichent son bandeau. */
class JourneyViewModel(journey: Flow<JourneyState>) : ViewModel() {
    val state: StateFlow<JourneyState?> = journey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** État du parcours (null le temps de le lire). */
@Composable
fun journeyState(): JourneyState? {
    val viewModel = cineViewModel(key = "journey") { JourneyViewModel(it.journey.state) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    return state
}
