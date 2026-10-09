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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.theme.CineSwipeTheme
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var glanceId by remember { mutableStateOf<GlanceId?>(null) }
    var settings by remember { mutableStateOf(WidgetSettings()) }
    var genres by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(appWidgetId) {
        val id = runCatching { GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return@LaunchedEffect
        glanceId = id
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        settings = WidgetSettings(
            source = prefs[WidgetKeys.SOURCE] ?: "all",
            maxMinutes = prefs[WidgetKeys.MAX_MINUTES] ?: 0,
            stats = prefs.stats(),
            count = prefs[WidgetKeys.COUNT] ?: TopWidget.DEFAULT_COUNT,
            genre = prefs[WidgetKeys.GENRE].orEmpty(),
        )
        if (kind == WidgetKind.TOP) genres = WidgetData.genres(context)
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
        when (kind) {
            WidgetKind.TONIGHT -> {
                Label(stringResource(R.string.widget_config_source))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to R.string.widget_source_all, "server" to R.string.tonight_source_server, "watchlist" to R.string.tonight_source_watchlist)
                        .forEach { (value, label) -> CineChip(stringResource(label), settings.source == value, { settings = settings.copy(source = value) }) }
                }
                Label(stringResource(R.string.widget_config_length))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to R.string.tonight_length_any, 100 to R.string.tonight_length_short, 130 to R.string.tonight_length_medium)
                        .forEach { (value, label) -> CineChip(stringResource(label), settings.maxMinutes == value, { settings = settings.copy(maxMinutes = value) }) }
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
                            if (next.isNotEmpty()) settings = settings.copy(stats = next)
                        })
                    }
                }
            }
            WidgetKind.TOP -> {
                Label(stringResource(R.string.widget_config_count))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopWidget.COUNTS.forEach { count ->
                        CineChip(count.toString(), settings.count == count, { settings = settings.copy(count = count) })
                    }
                }
                Label(stringResource(R.string.widget_config_genre))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CineChip(stringResource(R.string.discover_all_genres), settings.genre.isEmpty(), { settings = settings.copy(genre = "") })
                    genres.forEach { genre -> CineChip(genre, settings.genre == genre, { settings = settings.copy(genre = genre) }) }
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
