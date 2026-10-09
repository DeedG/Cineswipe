package io.github.deedg.cineswipe.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.CensusDiagnostics
import io.github.deedg.cineswipe.data.repository.Diagnostics
import io.github.deedg.cineswipe.data.repository.DiagnosticsRepository
import io.github.deedg.cineswipe.data.repository.FilmDiagnostic
import io.github.deedg.cineswipe.data.repository.RankingDiagnostics
import io.github.deedg.cineswipe.data.repository.RecoDiagnostics
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.SourceDiagnostic
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.ui.common.ExportEffect
import io.github.deedg.cineswipe.ui.common.ExportEvent
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

object DiagnosticsTestTags {
    const val CONTENT = "diagnostics_content"
    const val EXPORT = "diagnostics_export"
    const val COPY = "diagnostics_copy"
    const val HIDE = "diagnostics_hide"

    fun film(tmdbId: Long) = "diagnostics_film_$tmdbId"
}

data class DiagnosticsUiState(
    val loading: Boolean = true,
    val data: Diagnostics? = null,
    val failed: Boolean = false,
    val exporting: Boolean = false,
)

class DiagnosticsViewModel(
    private val diagnostics: DiagnosticsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()
    private val exportEvents = Channel<ExportEvent>(Channel.BUFFERED)

    /** Exports prêts à partager. */
    val exports: Flow<ExportEvent> = exportEvents.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val data = try {
                diagnostics.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            mutableState.update { it.copy(loading = false, data = data ?: it.data, failed = data == null) }
        }
    }

    fun export() {
        if (mutableState.value.exporting) return
        mutableState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val event = try {
                ExportEvent.Ready(diagnostics.export())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ExportEvent.Failed
            } finally {
                mutableState.update { it.copy(exporting = false) }
            }
            exportEvents.send(event)
        }
    }

    /** Résumé en texte, à coller dans un message. */
    fun summary(): String? = mutableState.value.data?.let(diagnostics::summary)

    /** L'écran se ferme aussitôt : l'écriture du réglage doit survivre à la fin du ViewModel. */
    fun hideTools() {
        viewModelScope.launch(NonCancellable) { settings.setDevToolsEnabled(false) }
    }
}

@Composable
fun DiagnosticsRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { DiagnosticsViewModel(it.diagnostics, it.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.diagnostics_copied)
    ExportEffect(viewModel.exports)
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        onOpenMovie = onOpenMovie,
        onRefresh = viewModel::refresh,
        onExport = viewModel::export,
        onCopy = {
            viewModel.summary()?.let { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CineSwipe", text)))
                    toaster.show(copied)
                }
            }
        },
        onHide = {
            viewModel.hideTools()
            onBack()
        },
    )
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onHide: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding()
            .testTag(DiagnosticsTestTags.CONTENT),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.diagnostics_title),
            leading = {
                GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
            },
            trailing = {
                GlassIconButton(icon = CineIcons.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh), onClick = onRefresh)
            },
        )
        Body(stringResource(R.string.diagnostics_intro))
        CineButton(
            text = stringResource(if (state.exporting) R.string.diagnostics_exporting else R.string.diagnostics_export),
            icon = CineIcons.Share,
            onClick = onExport,
            enabled = !state.exporting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DiagnosticsTestTags.EXPORT),
        )
        CineSecondaryButton(
            text = stringResource(R.string.diagnostics_copy),
            icon = CineIcons.Link,
            onClick = onCopy,
            enabled = state.data != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DiagnosticsTestTags.COPY),
        )
        val data = state.data
        when {
            data != null -> {
                RankingCard(data.ranking, onOpenMovie)
                RecosCard(data.recos)
                CensusCard(data.census)
            }
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red)
            }
            else -> SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Body(stringResource(R.string.diagnostics_failed))
                    CineSecondaryButton(text = stringResource(R.string.diagnostics_retry), icon = CineIcons.Refresh, onClick = onRefresh)
                }
            }
        }
        TextButton(
            onClick = onHide,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag(DiagnosticsTestTags.HIDE),
        ) {
            Text(stringResource(R.string.diagnostics_hide), color = CineColors.TextMuted)
        }
    }
}

@Composable
private fun RankingCard(ranking: RankingDiagnostics, onOpenMovie: (Long) -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle(CineIcons.Arena, stringResource(R.string.diagnostics_arena_title))
            StatGrid(
                listOf(
                    stringResource(R.string.diagnostics_reliability) to percent(ranking.reliability),
                    stringResource(R.string.diagnostics_consistency) to (ranking.consistency?.let(::percent) ?: "–"),
                    stringResource(R.string.diagnostics_films) to ranking.films.toString(),
                    stringResource(R.string.diagnostics_duels) to ranking.loggedDuels.toString(),
                    stringResource(R.string.diagnostics_inherited) to ranking.inheritedDuels.toString(),
                    stringResource(R.string.diagnostics_draws) to ranking.draws.toString(),
                    stringResource(R.string.diagnostics_median_deviation) to ranking.medianDeviation.roundToInt().toString(),
                    stringResource(R.string.diagnostics_to_90) to stringResource(R.string.diagnostics_to_90_value, ranking.duelsTo90),
                ),
            )
            Hint(stringResource(R.string.diagnostics_consistency_hint))
            ConfidenceBar(ranking.confidenceBuckets)
            if (ranking.uncertain.isNotEmpty()) {
                Label(stringResource(R.string.diagnostics_uncertain))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ranking.uncertain.forEach { film -> UncertainRow(film, onClick = { onOpenMovie(film.tmdbId) }) }
                }
            }
        }
    }
}

/** Répartition des films par confiance de leur note, en barre et en légende. */
@Composable
private fun ConfidenceBar(buckets: List<Int>) {
    val colors = listOf(CineColors.Red, CineColors.Warning, CineColors.Lightning, CineColors.Success)
    val labels = listOf(
        stringResource(R.string.diagnostics_bucket_low),
        stringResource(R.string.diagnostics_bucket_medium),
        stringResource(R.string.diagnostics_bucket_high),
        stringResource(R.string.diagnostics_bucket_top),
    )
    val total = buckets.sum()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(stringResource(R.string.diagnostics_confidence))
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(CineColors.Border),
        ) {
            if (total > 0) {
                buckets.forEachIndexed { index, count ->
                    if (count > 0) {
                        Box(
                            Modifier
                                .weight(count.toFloat())
                                .height(10.dp)
                                .background(colors[index]),
                        )
                    }
                }
            }
        }
        LegendRows(labels.indices.map { Triple(colors[it], labels[it], buckets.getOrElse(it) { 0 }) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendRows(entries: List<Triple<Color, String, Int>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (color, label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text("$label · $count", color = CineColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun UncertainRow(film: FilmDiagnostic, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.SurfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(DiagnosticsTestTags.film(film.tmdbId)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            film.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.diagnostics_uncertain_row, film.rating.roundToInt(), film.deviation.roundToInt(), film.duels),
            color = CineColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RecosCard(recos: RecoDiagnostics) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle(CineIcons.Recos, stringResource(R.string.diagnostics_recos_title))
            StatGrid(
                listOf(
                    stringResource(R.string.diagnostics_seeds) to recos.seeds.toString(),
                    stringResource(R.string.diagnostics_links) to recos.links.toString(),
                    stringResource(R.string.diagnostics_cached) to recos.cachedMovies.toString(),
                    stringResource(R.string.diagnostics_enrich) to recos.tasteToEnrich.toString(),
                ),
            )
            Label(stringResource(R.string.diagnostics_sources))
            SourcesTable(recos.sources)
            Affinities(stringResource(R.string.diagnostics_genres), recos.topGenres)
            Affinities(stringResource(R.string.diagnostics_directors), recos.topDirectors)
            Affinities(stringResource(R.string.diagnostics_actors), recos.topActors)
            Affinities(stringResource(R.string.diagnostics_themes), recos.topThemes)
        }
    }
}

/** Bilan du bandit : cartes montrées, aimées, rejetées et chance estimée de plaire. */
@Composable
private fun SourcesTable(sources: List<SourceDiagnostic>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SourceRow(
            name = stringResource(R.string.diagnostics_source_header),
            shown = stringResource(R.string.diagnostics_source_shown),
            liked = "♥",
            disliked = "✕",
            rate = stringResource(R.string.diagnostics_source_rate),
            header = true,
        )
        sources.forEach { source ->
            SourceRow(
                name = sourceLabel(source.source),
                shown = source.shown.roundToInt().toString(),
                liked = decimal(source.liked),
                disliked = decimal(source.disliked),
                rate = percent(source.rate),
                header = false,
            )
        }
    }
}

@Composable
private fun SourceRow(name: String, shown: String, liked: String, disliked: String, rate: String, header: Boolean) {
    val color = if (header) CineColors.TextMuted else CineColors.TextSoft
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = color, fontWeight = weight, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        listOf(shown, liked, disliked).forEach { value ->
            Text(value, color = color, fontWeight = weight, fontSize = 13.sp, modifier = Modifier.width(46.dp))
        }
        Text(rate, color = if (header) color else CineColors.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun sourceLabel(source: RecoSource): String = stringResource(
    when (source) {
        RecoSource.FOR_YOU -> R.string.diagnostics_source_for_you
        RecoSource.CLASSICS -> R.string.diagnostics_source_classics
        RecoSource.GEMS -> R.string.diagnostics_source_gems
        RecoSource.TRENDING -> R.string.diagnostics_source_trending
        RecoSource.PEOPLE -> R.string.diagnostics_source_people
        RecoSource.THEMES -> R.string.diagnostics_source_themes
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Affinities(title: String, values: List<Pair<String, Double>>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (name, value) -> ValueChip(name, value) }
        }
    }
}

@Composable
private fun ValueChip(name: String, value: Double) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .clip(shape)
            .background(CineColors.SurfaceHigh)
            .border(1.dp, CineColors.BorderStrong, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, color = CineColors.TextSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(signed(value), color = if (value >= 0) CineColors.Success else CineColors.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CensusCard(census: CensusDiagnostics) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle(CineIcons.Grid, stringResource(R.string.diagnostics_census_title))
            StatGrid(
                listOf(
                    stringResource(R.string.diagnostics_answered) to census.answered.toString(),
                    stringResource(R.string.diagnostics_universe) to (census.universe?.toString() ?: "–"),
                    stringResource(R.string.diagnostics_pool) to census.pool.toString(),
                    stringResource(R.string.diagnostics_sagas) to census.sagas.toString(),
                    stringResource(R.string.diagnostics_updates) to census.modelUpdates.toString(),
                ),
            )
            Affinities(stringResource(R.string.diagnostics_weights), census.topWeights)
        }
    }
}

/** Chiffres clés, deux par ligne. */
@Composable
private fun StatGrid(stats: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CineColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = CineColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun CardTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Body(text: String) {
    Text(text, color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = CineColors.TextFaint, fontSize = 12.sp, lineHeight = 16.sp)
}

private fun percent(value: Double) = "${(value * 100).roundToInt()} %"

private fun decimal(value: Double) = String.format(Locale.FRANCE, "%.1f", value)

private fun signed(value: Double) = String.format(Locale.FRANCE, "%+.2f", value)
