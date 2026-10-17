package io.github.deedg.cineswipe.ui.seasons

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.SeasonArenaRepository
import io.github.deedg.cineswipe.data.repository.SeasonItem
import io.github.deedg.cineswipe.data.repository.SeasonMatch
import io.github.deedg.cineswipe.domain.Matchmaker
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object SeasonArenaTestTags {
    const val A = "season_fighter_a"
    const val B = "season_fighter_b"
    const val DRAW = "season_draw"
    const val RANKING = "season_ranking"
    const val EMPTY = "season_empty"
}

data class SeasonArenaState(
    val loading: Boolean = true,
    val match: SeasonMatch? = null,
    val items: List<SeasonItem> = emptyList(),
    val showRanking: Boolean = false,
    val canUndo: Boolean = false,
)

class SeasonArenaViewModel(private val repository: SeasonArenaRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SeasonArenaState())
    val state: StateFlow<SeasonArenaState> = mutableState.asStateFlow()
    private val recent = ArrayDeque<Long>()
    private val recentPairs = HashSet<Pair<Long, Long>>()
    private var lastDuel: Long? = null
    private var undoJob: Job? = null

    init {
        next()
    }

    private fun next() {
        viewModelScope.launch {
            val match = repository.pickMatch(recent.toSet(), recentPairs)
            val items = repository.items()
            mutableState.update { it.copy(loading = false, match = match, items = items) }
        }
    }

    fun vote(winner: SeasonItem, loser: SeasonItem, draw: Boolean = false) {
        viewModelScope.launch {
            lastDuel = repository.vote(winner.key, loser.key, draw)
            listOf(winner.key, loser.key).forEach { key ->
                recent.addLast(key)
                while (recent.size > COOLDOWN) recent.removeFirst()
            }
            recentPairs += Matchmaker.pairKey(winner.key, loser.key)
            mutableState.update { it.copy(canUndo = true) }
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(UNDO_MS)
                mutableState.update { it.copy(canUndo = false) }
            }
            next()
        }
    }

    fun skip() {
        mutableState.value.match?.let { match -> recentPairs += Matchmaker.pairKey(match.a.key, match.b.key) }
        next()
    }

    fun undo() {
        val id = lastDuel ?: return
        lastDuel = null
        viewModelScope.launch {
            repository.undo(id)
            mutableState.update { it.copy(canUndo = false) }
            next()
        }
    }

    fun toggleRanking() = mutableState.update { it.copy(showRanking = !it.showRanking) }

    private companion object {
        const val COOLDOWN = 4
        const val UNDO_MS = 3_500L
    }
}

/**
 * Arène des saisons (mode séries) : deux saisons vues s'affrontent, touche ta préférée. Leur
 * classement est à part de celui des séries.
 */
@Composable
fun SeasonArenaRoute(onBack: () -> Unit) {
    val viewModel = cineViewModel { SeasonArenaViewModel(it.seasonArena) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(CineColors.Black).statusBarsPadding()) {
        ScreenTitle(
            text = stringResource(R.string.seasons_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
            trailing = {
                AnimatedVisibility(visible = state.canUndo, enter = fadeIn(), exit = fadeOut()) {
                    GlassPillButton(text = stringResource(R.string.arena_undo), icon = CineIcons.Undo, onClick = viewModel::undo)
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        val match = state.match
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            match == null -> EmptyState(
                icon = CineIcons.Layers,
                title = stringResource(R.string.seasons_empty_title),
                message = stringResource(R.string.seasons_empty_message),
                modifier = Modifier.testTag(SeasonArenaTestTags.EMPTY),
            )
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val reliability = (SeasonArenaRepository.reliability(state.items) * 100).roundToInt()
                Text(
                    stringResource(R.string.seasons_intro, state.items.size, reliability),
                    color = CineColors.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fighter(match.a, Modifier.weight(1f).testTag(SeasonArenaTestTags.A)) { viewModel.vote(match.a, match.b) }
                    Fighter(match.b, Modifier.weight(1f).testTag(SeasonArenaTestTags.B)) { viewModel.vote(match.b, match.a) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CineSecondaryButton(
                        text = stringResource(R.string.arena_draw),
                        onClick = { viewModel.vote(match.a, match.b, draw = true) },
                        modifier = Modifier.weight(1f).testTag(SeasonArenaTestTags.DRAW),
                    )
                    CineSecondaryButton(
                        text = stringResource(R.string.arena_skip),
                        icon = CineIcons.Skip,
                        onClick = viewModel::skip,
                        modifier = Modifier.weight(1f),
                    )
                }
                CineChip(
                    stringResource(R.string.seasons_ranking),
                    state.showRanking,
                    viewModel::toggleRanking,
                    Modifier.testTag(SeasonArenaTestTags.RANKING),
                )
                if (state.showRanking) Ranking(state.items)
            }
        }
    }
}

@Composable
private fun Fighter(item: SeasonItem, modifier: Modifier, onVote: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable(role = Role.Button, onClick = onVote)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            PosterImage(
                path = item.poster,
                imageSize = TmdbImageSize.Poster,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            )
            Text(
                stringResource(R.string.seasons_badge, item.season),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CineColors.Red)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            item.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        val meta = listOfNotNull(
            item.year?.toString(),
            item.episodes?.let { pluralStringResource(R.plurals.seasons_episodes, it, it) },
        )
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = CineColors.TextMuted, fontSize = 12.sp)
        if (item.duels > 0) StarRow(item.stars, provisional = StarScale.isProvisional(item.confidence))
    }
}

@Composable
private fun Ranking(items: List<SeasonItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.filter { it.duels > 0 }.sortedByDescending { it.rating }.take(MAX_RANKED).forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${index + 1}", color = CineColors.Red, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.width(26.dp))
                PosterImage(
                    path = item.poster,
                    imageSize = TmdbImageSize.Thumb,
                    contentDescription = null,
                    modifier = Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.seasons_badge, item.season), color = CineColors.TextMuted, fontSize = 12.sp)
                }
                StarRow(item.stars, provisional = StarScale.isProvisional(item.confidence))
            }
        }
    }
}

private const val MAX_RANKED = 30

