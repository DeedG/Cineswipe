package io.github.deedg.cineswipe.ui.profile

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.DuelHistoryRow
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

data class DuelHistoryUiState(
    val loading: Boolean = true,
    val duels: List<DuelHistoryRow> = emptyList(),
    /** Duels comptés sans historique détaillé (importés de l'ancienne version). */
    val untracked: Int = 0,
)

class DuelHistoryViewModel(library: LibraryRepository) : ViewModel() {
    val state: StateFlow<DuelHistoryUiState> = combine(
        library.observeDuelHistory(),
        library.observeLoggedDuels(),
        library.observeStats(),
    ) { duels, logged, stats ->
        DuelHistoryUiState(loading = false, duels = duels, untracked = (stats.duels - logged).coerceAtLeast(0))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DuelHistoryUiState())
}

@Composable
fun DuelHistoryRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { DuelHistoryViewModel(it.library) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DuelHistoryScreen(state = state, onBack = onBack, onOpenMovie = onOpenMovie)
}

@Composable
fun DuelHistoryScreen(state: DuelHistoryUiState, onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle(
            text = stringResource(R.string.duels_title),
            leading = {
                GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red)
            }
            state.duels.isEmpty() -> EmptyState(
                icon = CineIcons.Arena,
                title = stringResource(R.string.duels_empty_title),
                message = if (state.untracked > 0) {
                    stringResource(R.string.duels_untracked, state.untracked)
                } else {
                    stringResource(R.string.duels_empty_message)
                },
            )
            else -> {
                val now = remember(state.duels) { System.currentTimeMillis() }
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.untracked > 0) {
                        item(key = "untracked") {
                            Text(
                                stringResource(R.string.duels_untracked, state.untracked),
                                color = CineColors.TextMuted,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                    items(state.duels, key = { it.id }) { duel -> DuelRow(duel, now, onOpenMovie) }
                }
            }
        }
    }
}

@Composable
private fun DuelRow(duel: DuelHistoryRow, now: Long, onOpenMovie: (Long) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val gain = (duel.winnerRatingAfter - duel.winnerRatingBefore).roundToInt()
    val ago = DateUtils.getRelativeTimeSpanString(duel.createdAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    val unknown = stringResource(R.string.duels_unknown_film)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosterImage(
            path = duel.winnerPoster,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = stringResource(R.string.poster_description, duel.winnerTitle ?: unknown),
            modifier = Modifier
                .width(44.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenMovie(duel.winnerId) },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = duel.winnerTitle ?: unknown,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(if (duel.draw) R.string.duels_draw else R.string.duels_beat, duel.loserTitle ?: unknown),
                color = CineColors.TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.duels_meta, ago, if (gain >= 0) "+$gain" else gain.toString()),
                color = CineColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        PosterImage(
            path = duel.loserPoster,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = stringResource(R.string.poster_description, duel.loserTitle ?: unknown),
            modifier = Modifier
                .width(44.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(6.dp))
                .alpha(if (duel.draw) 1f else 0.55f)
                .clickable { onOpenMovie(duel.loserId) },
        )
    }
}
