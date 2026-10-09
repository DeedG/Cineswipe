package io.github.deedg.cineswipe.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.share.SharedRepository
import io.github.deedg.cineswipe.data.share.SharedResult
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SharedTestTags {
    const val LOADING = "shared_loading"
    fun choice(tmdbId: Long) = "shared_choice_$tmdbId"
}

class SharedViewModel(private val shared: SharedRepository, private val text: String) : ViewModel() {
    private val mutableResult = MutableStateFlow<SharedResult?>(null)
    val result: StateFlow<SharedResult?> = mutableResult.asStateFlow()

    init {
        retry()
    }

    fun retry() {
        mutableResult.value = null
        viewModelScope.launch { mutableResult.value = shared.resolve(text) }
    }
}

/**
 * Film partagé depuis une autre appli (Letterboxd, IMDb, TMDB, SensCritique… ou un simple
 * titre) : sa fiche s'ouvre directement, ou on choisit parmi les films possibles.
 */
@Composable
fun SharedRoute(
    text: String,
    onBack: () -> Unit,
    onFound: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenKeyGuide: () -> Unit,
) {
    val viewModel = cineViewModel(key = "shared-$text") { SharedViewModel(it.shared, text) }
    val result by viewModel.result.collectAsStateWithLifecycle()
    LaunchedEffect(result) {
        (result as? SharedResult.Found)?.let { onFound(it.tmdbId) }
    }
    Column(Modifier.fillMaxSize().background(CineColors.Black).statusBarsPadding()) {
        ScreenTitle(
            text = stringResource(R.string.shared_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        when (val current = result) {
            null, is SharedResult.Found -> Box(Modifier.fillMaxSize().testTag(SharedTestTags.LOADING), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            is SharedResult.Choices -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.shared_choose, current.query),
                        color = CineColors.TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
                items(current.movies, key = { it.id }) { movie ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(CineColors.Surface)
                            .border(1.dp, CineColors.Border, shape)
                            .clickable { onOpenMovie(movie.id) }
                            .padding(10.dp)
                            .testTag(SharedTestTags.choice(movie.id)),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosterImage(
                            path = movie.posterPath,
                            imageSize = TmdbImageSize.Thumb,
                            contentDescription = null,
                            modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                TmdbMapper.title(movie.title, movie.originalTitle),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val details = listOfNotNull(
                                TmdbMapper.year(movie.releaseDate)?.toString(),
                                movie.originalTitle?.takeIf { it != movie.title },
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) Text(details, color = CineColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            is SharedResult.NotFound -> EmptyState(
                icon = CineIcons.Search,
                title = stringResource(R.string.shared_not_found_title),
                message = stringResource(R.string.shared_not_found_message, current.query),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
            )
            SharedResult.NeedsKey -> EmptyState(
                icon = CineIcons.Link,
                title = stringResource(R.string.feed_needs_key_title),
                message = stringResource(R.string.shared_needs_key),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenKeyGuide,
            )
            SharedResult.Offline -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.shared_offline), color = CineColors.TextSoft, fontSize = 15.sp)
                CineButton(text = stringResource(R.string.feed_retry), onClick = viewModel::retry)
            }
        }
    }
}
