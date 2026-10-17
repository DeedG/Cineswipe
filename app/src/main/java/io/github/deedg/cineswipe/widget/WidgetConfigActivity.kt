package io.github.deedg.cineswipe.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.ui.common.SeriesContext
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.theme.Accent
import io.github.deedg.cineswipe.ui.theme.CineAccent
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.theme.CineSwipeTheme
import io.github.deedg.cineswipe.ui.tonight.TonightLength
import kotlinx.coroutines.launch

object WidgetConfigTags {
    const val SAVE = "widget_save"
}

/** Réglages d'un widget, à la pose (ou plus tard, appui long › Réglages du widget). */
data class WidgetSettings(
    val source: String = "all",
    val maxMinutes: Int = 0,
    val stats: List<WidgetStat> = DEFAULT_STATS,
    val count: Int = TopWidget.DEFAULT_COUNT,
    val genre: String = "",
    val world: MediaKind = MediaKind.FILMS,
)

enum class WidgetKind { TONIGHT, STATS, TOP }

/**
 * Écran de configuration commun aux trois widgets : « Ce soir » (origine des films, durée),
 * « Mes stats » (4 chiffres au choix), « Mon top » (nombre de films, genre).
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Annuler (retour) ne pose pas le widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        val kind = when {
            provider.endsWith("StatsWidgetReceiver") -> WidgetKind.STATS
            provider.endsWith("TopWidgetReceiver") -> WidgetKind.TOP
            else -> WidgetKind.TONIGHT
        }
        setContent {
            CineSwipeTheme {
                WidgetConfigScreen(kind, appWidgetId, onDone = {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    finish()
                })
            }
        }
    }
}

private fun widgetOf(kind: WidgetKind): GlanceAppWidget = when (kind) {
    WidgetKind.TONIGHT -> TonightWidget()
    WidgetKind.STATS -> StatsWidget()
    WidgetKind.TOP -> TopWidget()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigScreen(kind: WidgetKind, appWidgetId: Int, onDone: () -> Unit) {
    val baseContext = LocalContext.current
    val app = baseContext.applicationContext as CineSwipeApp
    var settings by remember { mutableStateOf(WidgetSettings(world = app.kind)) }
    // La couleur et les textes suivent le monde choisi ; l'appli retrouve les siens ensuite.
    LaunchedEffect(settings.world) { CineAccent.current = Accent.of(settings.world) }
    DisposableEffect(Unit) { onDispose { CineAccent.current = Accent.of(app.kind) } }
    val seriesContext = remember(baseContext) { SeriesContext(baseContext) }
    if (settings.world.isSeries) {
        CompositionLocalProvider(LocalContext provides seriesContext, LocalResources provides seriesContext.resources) {
            WidgetConfigContent(kind, appWidgetId, settings, { settings = it }, onDone)
        }
    } else {
        WidgetConfigContent(kind, appWidgetId, settings, { settings = it }, onDone)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigContent(
    kind: WidgetKind,
    appWidgetId: Int,
    settings: WidgetSettings,
    onSettings: (WidgetSettings) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var glanceId by remember { mutableStateOf<GlanceId?>(null) }
    var genres by remember { mutableStateOf(emptyList<String>()) }
    var settings = settings
    LaunchedEffect(appWidgetId) {
        val id = runCatching { GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return@LaunchedEffect
        glanceId = id
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        // Widget déjà réglé : ses réglages ; nouveau widget : le monde ouvert dans l'appli.
        val saved = prefs[WidgetKeys.WORLD] != null
        onSettings(
            WidgetSettings(
                source = prefs[WidgetKeys.SOURCE] ?: "all",
                maxMinutes = prefs[WidgetKeys.MAX_MINUTES] ?: 0,
                stats = prefs.stats(),
                count = prefs[WidgetKeys.COUNT] ?: TopWidget.DEFAULT_COUNT,
                genre = prefs[WidgetKeys.GENRE].orEmpty(),
                world = if (saved) prefs.world() else settings.world,
            ),
        )
    }
    LaunchedEffect(settings.world) {
        if (kind == WidgetKind.TOP) genres = WidgetData.genres(context, settings.world)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(
            text = stringResource(
                when (kind) {
                    WidgetKind.TONIGHT -> R.string.widget_tonight_name
                    WidgetKind.STATS -> R.string.widget_stats_name
                    WidgetKind.TOP -> R.string.widget_top_name
                },
            ),
        )
        // Films ou séries : le genre et la durée ne valent que pour un monde.
        Label(stringResource(R.string.widget_config_world))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKind.entries.forEach { world ->
                CineChip(
                    stringResource(if (world.isSeries) R.string.world_series else R.string.world_films),
                    settings.world == world,
                    {
                        if (world != settings.world) {
                            val source = settings.source.takeUnless { it == "resume" && !world.isSeries } ?: "all"
                            onSettings(settings.copy(world = world, genre = "", maxMinutes = 0, source = source))
                        }
                    },
                )
            }
        }
        when (kind) {
            WidgetKind.TONIGHT -> {
                Label(stringResource(R.string.widget_config_source))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sources = listOf("all" to R.string.widget_source_all, "server" to R.string.tonight_source_server, "watchlist" to R.string.tonight_source_watchlist) +
                        if (settings.world.isSeries) listOf("resume" to R.string.tonight_source_resume) else emptyList()
                    sources.forEach { (value, label) -> CineChip(stringResource(label), settings.source == value, { onSettings(settings.copy(source = value)) }) }
                }
                Label(stringResource(R.string.widget_config_length))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Durée d'un film, ou d'un épisode pour les séries.
                    val lengths = TonightLength.entries.map { length ->
                        val minutes = (if (settings.world.isSeries) length.seriesMaxMinutes else length.maxMinutes) ?: 0
                        val label = when (length) {
                            TonightLength.ANY -> R.string.tonight_length_any
                            TonightLength.SHORT -> R.string.tonight_length_short
                            TonightLength.MEDIUM -> R.string.tonight_length_medium
                        }
                        minutes to label
                    }
                    lengths.forEach { (value, label) -> CineChip(stringResource(label), settings.maxMinutes == value, { onSettings(settings.copy(maxMinutes = value)) }) }
                }
                Hint(stringResource(R.string.widget_tonight_hint))
            }
            WidgetKind.STATS -> {
                Label(stringResource(R.string.widget_config_stats, StatsWidget.MAX_STATS))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WidgetStat.entries.forEach { stat ->
                        val selected = stat in settings.stats
                        CineChip(stringResource(statLabel(stat)), selected, {
                            val next = if (selected) settings.stats - stat else (settings.stats + stat).takeLast(StatsWidget.MAX_STATS)
                            if (next.isNotEmpty()) onSettings(settings.copy(stats = next))
                        })
                    }
                }
            }
            WidgetKind.TOP -> {
                Label(stringResource(R.string.widget_config_count))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopWidget.COUNTS.forEach { count ->
                        CineChip(count.toString(), settings.count == count, { onSettings(settings.copy(count = count)) })
                    }
                }
                Label(stringResource(R.string.widget_config_genre))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CineChip(stringResource(R.string.discover_all_genres), settings.genre.isEmpty(), { onSettings(settings.copy(genre = "")) })
                    genres.forEach { genre -> CineChip(genre, settings.genre == genre, { onSettings(settings.copy(genre = genre)) }) }
                }
            }
        }
        CineButton(
            text = stringResource(R.string.widget_config_save),
            onClick = {
                val id = glanceId
                if (id == null) {
                    onDone()
                } else {
                    scope.launch {
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.SOURCE] = settings.source
                            prefs[WidgetKeys.MAX_MINUTES] = settings.maxMinutes
                            prefs[WidgetKeys.OFFSET] = 0
                            prefs[WidgetKeys.STATS] = settings.stats.joinToString(",") { it.name }
                            prefs[WidgetKeys.COUNT] = settings.count
                            prefs[WidgetKeys.GENRE] = settings.genre
                            prefs[WidgetKeys.WORLD] = settings.world.name
                        }
                        widgetOf(kind).update(context, id)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(WidgetConfigTags.SAVE),
        )
    }
}

fun statLabel(stat: WidgetStat): Int = when (stat) {
    WidgetStat.WATCHED -> R.string.widget_stat_watched
    WidgetStat.HOURS -> R.string.widget_stat_hours
    WidgetStat.DUELS -> R.string.widget_stat_duels
    WidgetStat.RELIABILITY -> R.string.widget_stat_reliability
    WidgetStat.WATCHLIST -> R.string.widget_stat_watchlist
    WidgetStat.TOP_GENRE -> R.string.widget_stat_genre
    WidgetStat.TOP_DIRECTOR -> R.string.widget_stat_director
    WidgetStat.DECADE -> R.string.widget_stat_decade
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
}
