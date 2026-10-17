package io.github.deedg.cineswipe.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.common.isSeriesWorld
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.components.StatusBadge
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.launch

@Composable
fun LibraryRoute(
    initialFilter: LibraryFilter,
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenCensus: () -> Unit,
    /** Fiche ouverte avec ses voisines (ordre de la liste) : un swipe passe à la précédente ou à la suivante. */
    onOpenMovieIn: ((Long, List<Long>) -> Unit)? = null,
) {
    val viewModel = cineViewModel { LibraryViewModel(it.library, it.settings, initialFilter) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    LibraryScreen(
        state = state,
        query = query,
        onQueryChange = viewModel::setQuery,
        onSortChange = viewModel::setSort,
        onFilterChange = viewModel::setFilter,
        onBack = onBack,
        onOpenMovie = onOpenMovie,
        onOpenCensus = onOpenCensus,
        onOpenMovieIn = onOpenMovieIn,
    )
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenCensus: () -> Unit,
    onOpenMovieIn: ((Long, List<Long>) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(
                text = stringResource(R.string.library_title),
                leading = {
                    GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
                },
                trailing = {
                    if (!state.loading) {
                        Text(
                            pluralStringResource(R.plurals.library_count, state.visibleCount, state.visibleCount),
                            color = CineColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                },
            )
            SearchField(query = query, onQueryChange = onQueryChange)
            Controls(
                sort = state.sort,
                filter = state.filter,
                showAvailability = state.showAvailability,
                onSortChange = onSortChange,
                onFilterChange = onFilterChange,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CineColors.Red)
                }
                state.libraryEmpty -> EmptyState(
                    icon = CineIcons.Film,
                    title = stringResource(R.string.library_empty_title),
                    message = stringResource(R.string.library_empty_message),
                    actionLabel = stringResource(R.string.census_cta),
                    onAction = onOpenCensus,
                )
                state.sections.isEmpty() -> Text(
                    stringResource(R.string.library_no_result),
                    color = CineColors.TextMuted,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                )
                else -> LibraryList(
                    sections = state.sections,
                    onOpenMovie = { id ->
                        val ids = state.sections.flatMap { section -> section.rows.map { it.tmdbId } }
                        if (onOpenMovieIn != null && ids.size > 1) onOpenMovieIn(id, ids) else onOpenMovie(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.library_search_hint), color = CineColors.TextFaint) },
        leadingIcon = { Icon(CineIcons.Search, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(CineIcons.Close, contentDescription = stringResource(R.string.library_clear_search), tint = CineColors.TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        },
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CineColors.SurfaceHigh,
            unfocusedContainerColor = CineColors.SurfaceHigh,
            focusedBorderColor = CineColors.Red,
            unfocusedBorderColor = CineColors.BorderStrong,
            cursorColor = CineColors.Red,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
    )
}

@Composable
private fun Controls(
    sort: LibrarySort,
    filter: LibraryFilter,
    showAvailability: Boolean,
    onSortChange: (LibrarySort) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    val series = isSeriesWorld()
    val filters = LibraryFilter.entries.filter { (showAvailability || it != LibraryFilter.AVAILABLE) && (series || it != LibraryFilter.IN_PROGRESS) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            CineChip(
                label = stringResource(sort.label),
                selected = true,
                onClick = { onSortChange(sort.next()) },
            )
        }
        item { Spacer(Modifier.width(4.dp)) }
        items(filters) { option ->
            CineChip(
                label = stringResource(option.label),
                selected = option == filter,
                onClick = { onFilterChange(option) },
            )
        }
    }
}

private val LibrarySort.label: Int
    get() = when (this) {
        LibrarySort.AZ -> R.string.library_sort_az
        LibrarySort.ELO -> R.string.library_sort_elo
        LibrarySort.YEAR -> R.string.library_sort_year
    }

private val LibraryFilter.label: Int
    get() = when (this) {
        LibraryFilter.ALL -> R.string.library_filter_all
        LibraryFilter.WATCHED -> R.string.library_filter_watched
        LibraryFilter.IN_PROGRESS -> R.string.series_progress_in_progress
        LibraryFilter.AVAILABLE -> R.string.library_filter_available
        LibraryFilter.WATCHLIST -> R.string.library_filter_watchlist
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryList(sections: List<LibrarySection>, onOpenMovie: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeIndex by remember { mutableStateOf<Int?>(null) }

    // Position de l'en-tête de chaque section dans la liste à plat (en-tête + lignes).
    val headerPositions = remember(sections) {
        var position = 0
        sections.map { section -> position.also { position += 1 + section.rows.size } }
    }
    val indexLabels = remember(sections) { sections.map { it.indexLabel } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEach { section ->
                stickyHeader(key = "header-${section.key}") { SectionHeader(section) }
                items(section.rows, key = { it.tmdbId }) { row ->
                    LibraryRow(row = row, onClick = { onOpenMovie(row.tmdbId) })
                }
            }
        }
        FastScroller(
            labels = indexLabels,
            onSelect = { index -> scope.launch { listState.scrollToItem(headerPositions[index]) } },
            onActiveChange = { activeIndex = it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 16.dp)
                .fillMaxHeight(),
        )
        activeIndex?.let { index ->
            ScrollBubble(text = sections.getOrNull(index)?.label.orEmpty(), modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun SectionHeader(section: LibrarySection) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CineColors.Black)
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            section.eloFloor != null -> {
                Icon(CineIcons.Trophy, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.elo_value, section.eloFloor), color = CineColors.TextFaint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            section.decade != null -> {
                Text(stringResource(R.string.library_section_decade, section.decade), color = CineColors.TextFaint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            section.key == LibrarySections.UNRANKED_KEY -> {
                Text(stringResource(R.string.library_section_unranked), color = CineColors.TextFaint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            section.key == LibrarySections.UNKNOWN_YEAR_KEY -> {
                Text(stringResource(R.string.library_section_unknown_year), color = CineColors.TextFaint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            else -> Text(section.label, color = CineColors.TextFaint, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun LibraryRow(row: LibraryRowUi, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        PosterImage(
            path = row.posterPath,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = null,
            modifier = Modifier
                .width(50.dp)
                .height(75.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = row.year?.let { "${row.title} ($it)" } ?: row.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (row.watched) {
                    val label = when (row.progress) {
                        SeriesProgress.IN_PROGRESS -> row.currentSeason?.let { stringResource(R.string.series_progress_in_season, it) }
                            ?: stringResource(R.string.series_progress_in_progress)
                        SeriesProgress.ABANDONED -> stringResource(R.string.series_progress_abandoned)
                        SeriesProgress.EPISODES -> stringResource(R.string.series_progress_episodes)
                        SeriesProgress.FINISHED, null -> stringResource(R.string.badge_watched)
                    }
                    StatusBadge(label, CineColors.Red, icon = CineIcons.Eye, compact = true)
                }
                if (row.available) StatusBadge(stringResource(R.string.badge_available), CineColors.TextSecondary, icon = CineIcons.Server, compact = true)
                if (row.requested) StatusBadge(stringResource(R.string.badge_requested), CineColors.Warning, compact = true)
                if (row.watchlist) StatusBadge(stringResource(R.string.badge_watchlist), CineColors.TmdbBlue, icon = CineIcons.Bookmark, compact = true)
            }
            if (row.rating != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CineIcons.Trophy, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(14.dp))
                    Text(row.rating.toString(), color = CineColors.TextSecondary, fontSize = 13.sp)
                    row.stars?.let { StarRow(stars = it, provisional = row.provisional) }
                }
            }
        }
    }
}

@Composable
private fun ScrollBubble(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .size(120.dp)
            .shadow(20.dp, shape)
            .clip(shape)
            .background(Color(0xD90A0A0A))
            .border(2.dp, CineColors.Red.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = CineColors.Red, fontSize = 48.sp, fontWeight = FontWeight.Black)
    }
}
