package io.github.deedg.cineswipe.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.TmdbGenre
import io.github.deedg.cineswipe.data.repository.DiscoverFilters
import io.github.deedg.cineswipe.data.repository.DiscoverLength
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSwitchRow
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.components.WorldSwitch
import io.github.deedg.cineswipe.ui.feed.CardFeed
import io.github.deedg.cineswipe.ui.feed.CardFeedScreen
import io.github.deedg.cineswipe.ui.feed.FeedTexts
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Year
import java.util.Locale
import kotlin.math.roundToInt

/** Première année proposée par les filtres (comme en V4). */
const val DISCOVER_FIRST_YEAR = 1920

sealed interface GenresState {
    data object Loading : GenresState
    data object NeedsKey : GenresState
    data object Failed : GenresState
    data class Loaded(val genres: List<TmdbGenre>) : GenresState
}

class DiscoverViewModel(
    private val tmdb: TmdbRepository,
    feedRepository: FeedRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val feed = CardFeed(viewModelScope, feedRepository, settings, rememberShown = true)

    private val _filters = MutableStateFlow(DiscoverFilters())
    val filters: StateFlow<DiscoverFilters> = _filters.asStateFlow()

    private val _genres = MutableStateFlow<GenresState>(GenresState.Loading)
    val genres: StateFlow<GenresState> = _genres.asStateFlow()

    private val _showResults = MutableStateFlow(false)
    val showResults: StateFlow<Boolean> = _showResults.asStateFlow()

    /** Le filtre « dispo sur mon serveur » n'apparaît qu'avec un homelab. */
    val homelabConnected: StateFlow<Boolean> =
        settings.homelabConnected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var genresJob: Job? = null

    init {
        viewModelScope.launch {
            settings.tmdbApiKey.map { it != null }.distinctUntilChanged().collect { present ->
                if (present) loadGenres() else _genres.value = GenresState.NeedsKey
            }
        }
    }

    fun loadGenres() {
        genresJob?.cancel()
        genresJob = viewModelScope.launch {
            _genres.value = GenresState.Loading
            _genres.value = try {
                GenresState.Loaded(tmdb.genres())
            } catch (error: TmdbException.MissingKey) {
                GenresState.NeedsKey
            } catch (error: TmdbException) {
                GenresState.Failed
            }
        }
    }

    fun setGenre(genreId: Int?) {
        _filters.update { it.copy(genreId = genreId) }
    }

    /** Une borne au bout de la plage (1920 ou l'année en cours) revient à ne pas filtrer. */
    fun setYears(min: Int, max: Int, lastYear: Int) {
        _filters.update {
            it.copy(
                yearMin = min.takeIf { year -> year > DISCOVER_FIRST_YEAR },
                yearMax = max.takeIf { year -> year < lastYear },
            )
        }
    }

    fun setMinRating(value: Float) {
        _filters.update { it.copy(minRating = value) }
    }

    fun setLength(value: DiscoverLength) {
        _filters.update { it.copy(length = value) }
    }

    fun setOnlyAvailable(value: Boolean) {
        _filters.update { it.copy(onlyAvailable = value) }
    }

    fun generate() {
        _showResults.value = true
        feed.start(FeedRequest.Discover(_filters.value))
    }

    fun closeResults() {
        _showResults.value = false
        feed.reset()
    }
}

@Composable
fun DiscoverRoute(
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onLightning: (Long) -> Unit,
    onOpenCensus: () -> Unit,
    onOpenTonight: () -> Unit = {},
) {
    val viewModel = cineViewModel { DiscoverViewModel(it.tmdb, it.feed, it.settings) }
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val showResults by viewModel.showResults.collectAsStateWithLifecycle()
    val homelabConnected by viewModel.homelabConnected.collectAsStateWithLifecycle()
    val feedState by viewModel.feed.state.collectAsStateWithLifecycle()
    val onlyAvailable = filters.onlyAvailable && homelabConnected
    DisposableEffect(viewModel) { onDispose { viewModel.feed.clearUndo() } }
    BackHandler(enabled = showResults) { viewModel.closeResults() }

    when {
        showResults -> CardFeedScreen(
            state = feedState,
            events = viewModel.feed.events,
            texts = FeedTexts(
                loading = stringResource(R.string.discover_loading),
                emptyTitle = stringResource(R.string.discover_empty_title),
                emptyMessage = stringResource(if (onlyAvailable) R.string.discover_empty_available else R.string.discover_empty_message),
                endTitle = stringResource(R.string.discover_end),
                endActionLabel = stringResource(R.string.discover_change_filters),
            ),
            onAction = viewModel.feed::act,
            onShown = viewModel.feed::onCardShown,
            onLoadMore = viewModel.feed::loadMore,
            onRetry = viewModel.feed::retry,
            onUndo = viewModel.feed::undo,
            onLightning = { onLightning(it.tmdbId) },
            onOpenMovie = onOpenMovie,
            onOpenSettings = onOpenSettings,
            onEndAction = viewModel::closeResults,
            topStart = {
                GlassIconButton(
                    icon = CineIcons.Back,
                    contentDescription = stringResource(R.string.discover_close),
                    onClick = viewModel::closeResults,
                )
            },
        )
        genres == GenresState.NeedsKey -> EmptyState(
            icon = CineIcons.Discover,
            title = stringResource(R.string.feed_needs_key_title),
            message = stringResource(R.string.feed_needs_key_message),
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = onOpenSettings,
            modifier = Modifier.statusBarsPadding(),
        )
        else -> DiscoverFiltersScreen(
            filters = filters,
            genres = genres,
            homelabConnected = homelabConnected,
            onGenreChange = viewModel::setGenre,
            onYearsChange = viewModel::setYears,
            onMinRatingChange = viewModel::setMinRating,
            onLengthChange = viewModel::setLength,
            onOnlyAvailableChange = viewModel::setOnlyAvailable,
            onRetryGenres = viewModel::loadGenres,
            onGenerate = viewModel::generate,
            onOpenCensus = onOpenCensus,
            onOpenTonight = onOpenTonight,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverFiltersScreen(
    filters: DiscoverFilters,
    genres: GenresState,
    homelabConnected: Boolean,
    onGenreChange: (Int?) -> Unit,
    onYearsChange: (min: Int, max: Int, lastYear: Int) -> Unit,
    onMinRatingChange: (Float) -> Unit,
    onLengthChange: (DiscoverLength) -> Unit,
    onOnlyAvailableChange: (Boolean) -> Unit,
    onRetryGenres: () -> Unit,
    onGenerate: () -> Unit,
    onOpenCensus: () -> Unit,
    onOpenTonight: () -> Unit,
) {
    val lastYear = remember { Year.now().value }
    val sliderColors = SliderDefaults.colors(
        thumbColor = CineColors.Red,
        activeTrackColor = CineColors.Red,
        inactiveTrackColor = CineColors.BorderStrong,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    val yearMin = filters.yearMin ?: DISCOVER_FIRST_YEAR
    val yearMax = filters.yearMax ?: lastYear

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.discover_title),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            WorldSwitch(tabName = Tab.DISCOVER.name)
        }

        ShortcutCard(
            icon = CineIcons.Moon,
            title = stringResource(R.string.discover_tonight_title),
            message = stringResource(R.string.discover_tonight_message),
            onClick = onOpenTonight,
            modifier = Modifier.testTag(DiscoverTestTags.TONIGHT),
        )
        CensusCard(onClick = onOpenCensus)

        FilterLabel(stringResource(R.string.discover_genre))
        when (genres) {
            GenresState.Loading, GenresState.NeedsKey -> CircularProgressIndicator(
                color = CineColors.Red,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
            GenresState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.discover_genres_error),
                    color = CineColors.TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryGenres) {
                    Text(stringResource(R.string.feed_retry), color = CineColors.Red, fontWeight = FontWeight.Bold)
                }
            }
            is GenresState.Loaded -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CineChip(
                    label = stringResource(R.string.discover_all_genres),
                    selected = filters.genreId == null,
                    onClick = { onGenreChange(null) },
                )
                genres.genres.forEach { genre ->
                    CineChip(
                        label = genre.name,
                        selected = filters.genreId == genre.id,
                        onClick = { onGenreChange(genre.id) },
                    )
                }
            }
        }

        FilterLabel(
            label = stringResource(R.string.discover_period),
            value = stringResource(R.string.discover_period_value, yearMin, yearMax),
        )
        RangeSlider(
            value = yearMin.toFloat()..yearMax.toFloat(),
            onValueChange = { range -> onYearsChange(range.start.roundToInt(), range.endInclusive.roundToInt(), lastYear) },
            valueRange = DISCOVER_FIRST_YEAR.toFloat()..lastYear.toFloat(),
            colors = sliderColors,
        )

        FilterLabel(
            label = stringResource(R.string.discover_rating),
            value = if (filters.minRating > 0f) {
                stringResource(R.string.discover_rating_value, String.format(Locale.FRANCE, "%.1f", filters.minRating))
            } else {
                stringResource(R.string.discover_rating_any)
            },
        )
        Slider(
            value = filters.minRating,
            onValueChange = { value -> onMinRatingChange((value * 2).roundToInt() / 2f) },
            valueRange = 0f..9f,
            colors = sliderColors,
        )

        FilterLabel(stringResource(R.string.discover_length))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                DiscoverLength.ANY to R.string.tonight_length_any,
                DiscoverLength.SHORT to R.string.tonight_length_short,
                DiscoverLength.MEDIUM to R.string.tonight_length_medium,
                DiscoverLength.LONG to R.string.discover_length_long,
            ).forEach { (length, label) ->
                CineChip(label = stringResource(label), selected = filters.length == length, onClick = { onLengthChange(length) })
            }
        }

        if (homelabConnected) {
            CineSwitchRow(
                title = stringResource(R.string.discover_only_available),
                subtitle = stringResource(R.string.discover_only_available_hint),
                checked = filters.onlyAvailable,
                onCheckedChange = onOnlyAvailableChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        CineButton(
            text = stringResource(R.string.discover_generate),
            onClick = onGenerate,
            enabled = genres !is GenresState.NeedsKey,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

/** Entrée du recensement, en tête de la Découverte. */
@Composable
private fun CensusCard(onClick: () -> Unit) {
    SectionCard(
        onClick = onClick,
        modifier = Modifier
            .testTag(DiscoverTestTags.CENSUS)
            .tutorialTarget(TutorialTarget.DISCOVER_CENSUS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(CineIcons.Grid, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.discover_census_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.discover_census_message), color = CineColors.TextSecondary, fontSize = 14.sp)
            }
            Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Raccourci vers un autre écran, en tête de la Découverte. */
@Composable
private fun ShortcutCard(icon: ImageVector, title: String, message: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(message, color = CineColors.TextSecondary, fontSize = 14.sp)
            }
            Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Repères pour les tests d'interface. */
object DiscoverTestTags {
    const val CENSUS = "discover-census"
    const val TONIGHT = "discover-tonight"
}

/** Libellé de filtre en petites capitales grises (`.discover-label` en V4), avec sa valeur en rouge. */
@Composable
private fun FilterLabel(label: String, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(Locale.FRENCH),
            color = CineColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(value, color = CineColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
