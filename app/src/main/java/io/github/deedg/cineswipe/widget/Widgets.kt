package io.github.deedg.cineswipe.widget

import android.content.Context
import io.github.deedg.cineswipe.data.repository.TonightPick
import androidx.glance.currentState
import androidx.datastore.preferences.core.Preferences
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.tonight.formatRuntime
import java.text.NumberFormat
import java.util.Locale

private val Background = Color(0xFF111111)
private val Red = Color(0xFFED1C24)
private val Muted = Color(0xFF999999)
private val Soft = Color(0xFFDDDDDD)

private fun text(color: Color, size: Int, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun WidgetFrame(modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(Background).cornerRadius(20.dp),
        contentAlignment = Alignment.TopStart,
    ) { content() }
}

@Composable
private fun Header(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(8.dp).background(Red).cornerRadius(4.dp)) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(label, style = text(Muted, 12, bold = true))
    }
}

// --- Ce soir ---

/** Ce que montre le widget « Ce soir ». */
private class TonightModel(val pick: TonightPick?, val poster: Bitmap?) {
    companion object {
        suspend fun load(context: Context, prefs: Preferences): TonightModel {
            val picks = WidgetData.tonight(context, prefs[WidgetKeys.SOURCE], prefs[WidgetKeys.MAX_MINUTES] ?: 0)
            val pick = picks.takeIf { it.isNotEmpty() }?.let { it[(prefs[WidgetKeys.OFFSET] ?: 0).mod(it.size)] }
            return TonightModel(pick, WidgetData.poster(context, pick?.movie?.posterPath, TmdbImageSize.Poster, width = 300))
        }
    }
}

/**
 * Les données sont rechargées à chaque changement de l'état du widget (réglages, « Suivant »,
 * actualisation demandée par l'appli) : un widget déjà affiché garde sa session Glance un
 * moment, et seul ce qui est lu dans l'état y est recalculé.
 */
@Composable
private fun <T> rememberWidgetModel(context: Context, initialPrefs: Preferences, initial: T, load: suspend (Context, Preferences) -> T): T {
    val prefs = currentState<Preferences>()
    val model by produceState(initial, prefs) {
        if (prefs != initialPrefs) value = load(context, prefs)
    }
    return model
}

/** Widget « Ce soir » : l'affiche du film à regarder ce soir, avec « Suivant ». */
class TonightWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initial = TonightModel.load(context, initialPrefs)
        val title = context.getString(R.string.widget_tonight_label)
        val empty = context.getString(R.string.widget_tonight_empty)
        val next = context.getString(R.string.widget_next)
        provideContent {
            val model = rememberWidgetModel(context, initialPrefs, initial) { c, p -> TonightModel.load(c, p) }
            val pick = model.pick
            val poster = model.poster
            val size = LocalSize.current
            val open = actionStartActivity(WidgetLinks.intent(context, pick?.let { WidgetLinks.movie(it.movie.tmdbId) } ?: WidgetLinks.TONIGHT))
            WidgetFrame(GlanceModifier.clickable(open)) {
                if (pick == null) {
                    Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                        Header(title)
                        Spacer(GlanceModifier.height(10.dp))
                        Text(empty, style = text(Soft, 13))
                    }
                } else {
                    val wide = size.width >= 220.dp
                    Row(GlanceModifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (poster != null) {
                            val posterWidth = if (wide) (size.height - 20.dp) * 2 / 3 else size.width - 20.dp
                            Image(
                                provider = ImageProvider(poster),
                                contentDescription = pick.movie.title,
                                contentScale = ContentScale.Crop,
                                modifier = GlanceModifier.width(posterWidth).fillMaxHeight().cornerRadius(12.dp),
                            )
                        }
                        if (wide || poster == null) {
                            Column(GlanceModifier.fillMaxHeight().defaultWeight().padding(start = 12.dp)) {
                                Header(title)
                                Spacer(GlanceModifier.height(6.dp))
                                Text(pick.movie.title, style = text(Color.White, 17, bold = true), maxLines = 3)
                                val meta = listOfNotNull(pick.movie.year?.toString(), pick.movie.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime))
                                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = text(Muted, 12))
                                pick.reason?.subject?.let { Text("♥ $it", style = text(Soft, 12), maxLines = 2) }
                                Spacer(GlanceModifier.defaultWeight())
                                Text(
                                    "$next ›",
                                    style = text(Red, 13, bold = true),
                                    modifier = GlanceModifier.padding(vertical = 4.dp).clickable(actionRunCallback<NextTonightAction>()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** « Suivant » : le film d'après dans la liste de ce soir. */
class NextTonightAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs -> prefs[WidgetKeys.OFFSET] = (prefs[WidgetKeys.OFFSET] ?: 0) + 1 }
        TonightWidget().update(context, glanceId)
    }
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget()
}

// --- Stats ---

/** Libellé d'une décennie : « années 90 », « années 2000 ». */
private fun decadeLabel(context: Context, decade: String): String =
    context.getString(R.string.widget_decade, if (decade.startsWith("19")) decade.takeLast(2) else decade)

private suspend fun statsCells(context: Context, prefs: Preferences): List<Pair<String, String>> {
    val values = WidgetData.stats(context) ?: return emptyList()
    val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
    return prefs.stats().take(StatsWidget.MAX_STATS).map { stat ->
        when (stat) {
            WidgetStat.WATCHED -> format.format(values.watched) to context.getString(R.string.widget_stat_watched)
            WidgetStat.HOURS -> format.format(values.hours) to context.getString(R.string.widget_stat_hours)
            WidgetStat.DUELS -> format.format(values.duels) to context.getString(R.string.widget_stat_duels)
            WidgetStat.RELIABILITY -> "${values.reliability} %" to context.getString(R.string.widget_stat_reliability)
            WidgetStat.WATCHLIST -> format.format(values.watchlist) to context.getString(R.string.widget_stat_watchlist)
            WidgetStat.TOP_GENRE -> (values.topGenre ?: "–") to context.getString(R.string.widget_stat_genre)
            WidgetStat.TOP_DIRECTOR -> (values.topDirector ?: "–") to context.getString(R.string.widget_stat_director)
            WidgetStat.DECADE -> (values.decade?.let { decadeLabel(context, it) } ?: "–") to context.getString(R.string.widget_stat_decade)
        }
    }
}

/** Widget « Mes stats » : jusqu'à 4 chiffres choisis. */
class StatsWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initial = statsCells(context, initialPrefs)
        val title = context.getString(R.string.widget_stats_label)
        provideContent {
            val cells = rememberWidgetModel(context, initialPrefs, initial) { c, p -> statsCells(c, p) }
            WidgetFrame(GlanceModifier.clickable(actionStartActivity(WidgetLinks.intent(context, WidgetLinks.STATS)))) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Header(title)
                    Spacer(GlanceModifier.height(8.dp))
                    cells.chunked(2).forEach { row ->
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            row.forEach { (value, label) ->
                                Column(GlanceModifier.defaultWeight()) {
                                    Text(value, style = text(Color.White, if (value.length > 9) 15 else 22, bold = true), maxLines = 1)
                                    Text(label, style = text(Muted, 11), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_STATS = 4
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

// --- Top ---

private class TopModel(val title: String, val films: List<WidgetData.TopFilm>, val posters: List<Bitmap?>) {
    companion object {
        suspend fun load(context: Context, prefs: Preferences): TopModel {
            val genre = prefs[WidgetKeys.GENRE]?.takeIf { it.isNotBlank() }
            val films = WidgetData.top(context, (prefs[WidgetKeys.COUNT] ?: TopWidget.DEFAULT_COUNT).coerceIn(1, TopWidget.COUNTS.max()), genre)
            val title = genre?.let { context.getString(R.string.widget_top_genre, it) } ?: context.getString(R.string.widget_top_label)
            return TopModel(title, films, films.map { WidgetData.poster(context, it.posterPath) })
        }
    }
}

/** Widget « Mon top » : les affiches des films les mieux classés (tous genres ou un genre). */
class TopWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initial = TopModel.load(context, initialPrefs)
        val empty = context.getString(R.string.widget_top_empty)
        provideContent {
            val model = rememberWidgetModel(context, initialPrefs, initial) { c, p -> TopModel.load(c, p) }
            val films = model.films
            val size = LocalSize.current
            WidgetFrame(GlanceModifier.clickable(actionStartActivity(WidgetLinks.intent(context, WidgetLinks.TOP)))) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
                    Header(model.title)
                    Spacer(GlanceModifier.height(8.dp))
                    if (films.isEmpty()) {
                        Text(empty, style = text(Soft, 13))
                    } else {
                        // Une rangée d'affiches à la largeur du widget. Pas d'espaceurs entre elles :
                        // une rangée Glance ne garde que ses 10 premiers éléments.
                        val gap = 6.dp
                        val posterWidth = (size.width - 24.dp + gap) / films.size
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            films.forEachIndexed { index, film ->
                                Column(
                                    GlanceModifier.width(posterWidth).fillMaxHeight().padding(end = gap)
                                        .clickable(actionStartActivity(WidgetLinks.intent(context, WidgetLinks.movie(film.tmdbId)))),
                                ) {
                                    val bitmap = model.posters.getOrNull(index)
                                    if (bitmap != null) {
                                        Image(
                                            provider = ImageProvider(bitmap),
                                            contentDescription = film.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(8.dp),
                                        )
                                    } else {
                                        Box(GlanceModifier.fillMaxWidth().defaultWeight().background(Color(0xFF222222)).cornerRadius(8.dp)) {
                                            Text(film.title, style = text(Soft, 10), maxLines = 4)
                                        }
                                    }
                                    Text("${index + 1}", style = text(Red, 12, bold = true), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_COUNT = 5
        val COUNTS = listOf(3, 4, 5, 6)
    }
}

class TopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopWidget()
}
