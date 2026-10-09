package io.github.deedg.cineswipe.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.MainActivity
import io.github.deedg.cineswipe.data.repository.TonightPick
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.TmdbImages
import kotlinx.coroutines.CancellationException

/** Écrans de l'appli qu'un widget peut ouvrir. */
object WidgetLinks {
    const val EXTRA_OPEN = "io.github.deedg.cineswipe.OPEN"
    const val TONIGHT = "tonight"
    const val STATS = "stats"
    const val TOP = "top"
    fun movie(tmdbId: Long) = "movie:$tmdbId"

    fun intent(context: Context, target: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_OPEN, target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Réglages des widgets (un jeu par widget posé). */
object WidgetKeys {
    /** « Ce soir » : tout, serveur, à voir. */
    val SOURCE = stringPreferencesKey("source")

    /** « Ce soir » : durée maximale (minutes, 0 = toutes). */
    val MAX_MINUTES = intPreferencesKey("max_minutes")

    /** « Ce soir » : rang du film montré (bouton « Suivant »). */
    val OFFSET = intPreferencesKey("offset")

    /** Stats : chiffres montrés, séparés par des virgules. */
    val STATS = stringPreferencesKey("stats")

    /** Top : nombre de films. */
    val COUNT = intPreferencesKey("count")

    /** Top : genre (vide = tous). */
    val GENRE = stringPreferencesKey("genre")

    /** Actualisation demandée par l'appli : changer cette valeur fait recharger les données. */
    val REFRESH = longPreferencesKey("refresh")
}

/** Chiffres proposés par le widget de stats. */
enum class WidgetStat { WATCHED, HOURS, DUELS, RELIABILITY, WATCHLIST, TOP_GENRE, TOP_DIRECTOR, DECADE }

val DEFAULT_STATS = listOf(WidgetStat.WATCHED, WidgetStat.RELIABILITY, WidgetStat.HOURS, WidgetStat.TOP_GENRE)

fun Preferences.stats(): List<WidgetStat> =
    this[WidgetKeys.STATS]?.split(',')?.mapNotNull { name -> WidgetStat.entries.firstOrNull { it.name == name } }?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_STATS

/** Données des widgets : toujours les vraies données (jamais la démo). */
object WidgetData {
    fun container(context: Context): AppContainer = (context.applicationContext as CineSwipeApp).mainContainer

    /** Films de « Ce soir » selon les réglages du widget. */
    suspend fun tonight(context: Context, source: String?, maxMinutes: Int): List<TonightPick> = safe(emptyList()) {
        container(context).recos.tonight().filter { pick ->
            val sourceOk = when (source) {
                "server" -> pick.available
                "watchlist" -> pick.inWatchlist
                else -> true
            }
            val runtime = pick.movie.runtimeMinutes
            sourceOk && (maxMinutes <= 0 || (runtime != null && runtime <= maxMinutes))
        }
    }

    data class TopFilm(val tmdbId: Long, val title: String, val posterPath: String?, val stars: Float)

    /** Les mieux classés de l'Arène, éventuellement d'un genre. */
    suspend fun top(context: Context, count: Int, genre: String?): List<TopFilm> = safe(emptyList()) {
        val database = container(context).database
        val ranked = database.userMovieDao().ranked()
        val stars = StarScale.computeStars(ranked.associate { it.tmdbId to it.rating })
        val movies = database.recoDao().movies(ranked.map { it.tmdbId }).associateBy { it.tmdbId }
        ranked.sortedByDescending { it.rating }
            .mapNotNull { row -> movies[row.tmdbId]?.let { movie -> row to movie } }
            .filter { (_, movie) -> genre.isNullOrBlank() || genre in movie.genres }
            .take(count)
            .map { (row, movie) -> TopFilm(row.tmdbId, movie.title, movie.posterPath, stars[row.tmdbId] ?: 0f) }
    }

    data class StatValues(
        val watched: Int,
        val hours: Int,
        val duels: Int,
        val reliability: Int,
        val watchlist: Int,
        val topGenre: String?,
        val topDirector: String?,
        val decade: String?,
    )

    suspend fun stats(context: Context): StatValues? = safe(null) {
        val container = container(context)
        val stats = container.stats.load()
        val ranked = container.database.userMovieDao().ranked()
        val reliability = if (ranked.isEmpty()) 0 else (ranked.sumOf { it.confidence } / ranked.size * 100).toInt()
        val library = container.database.diagnosticsDao().userMovies()
        StatValues(
            watched = stats.watched,
            hours = stats.hours,
            duels = stats.duels,
            reliability = reliability,
            watchlist = library.count { it.status == io.github.deedg.cineswipe.data.db.WatchStatus.WATCHLIST },
            topGenre = stats.genres.firstOrNull()?.name,
            topDirector = stats.directors.firstOrNull()?.name ?: stats.mostWatchedDirector?.name,
            decade = stats.favoriteDecade?.key,
        )
    }

    /** Genres proposés pour le widget « Top » (ceux d'au moins 3 films vus). */
    suspend fun genres(context: Context): List<String> = safe(emptyList()) { container(context).stats.load().genres.map { it.name }.sorted() }

    /** Affiche prête pour un widget (taille réduite, pas en mémoire graphique). */
    suspend fun poster(context: Context, path: String?, size: TmdbImageSize = TmdbImageSize.Thumb, width: Int = 220): Bitmap? {
        val url = TmdbImages.url(path, size) ?: return null
        return safe(null) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(width, width * 3 / 2)
                .allowHardware(false)
                .build()
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        fallback
    }
}

/** Met à jour tous les widgets posés (à la sortie de l'appli, après un duel…). */
object Widgets {
    suspend fun updateAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val now = System.currentTimeMillis()
        listOf(TonightWidget(), StatsWidget(), TopWidget()).forEach { widget ->
            runCatching {
                manager.getGlanceIds(widget.javaClass).forEach { id ->
                    // Nouvelle valeur dans l'état : le widget recharge ses données même si sa session est encore ouverte.
                    updateAppWidgetState(context, id) { prefs -> prefs[WidgetKeys.REFRESH] = now }
                    widget.update(context, id)
                }
            }
        }
    }
}
