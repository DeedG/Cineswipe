package io.github.deedg.cineswipe.data.series

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.MainActivity
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.domain.SeasonAlert
import io.github.deedg.cineswipe.domain.SeasonAlerts
import io.github.deedg.cineswipe.widget.WidgetLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Une alerte prête à montrer : la série est-elle finie (« Remettre en cours ») et peut-on la demander ? */
data class SeasonNotice(val alert: SeasonAlert, val finished: Boolean, val canRequest: Boolean)

fun interface SeasonNotifier {
    fun show(notices: List<SeasonNotice>)

    companion object {
        val None = SeasonNotifier { }
    }
}

/**
 * Nouvelles saisons des séries suivies (vues, finies ou en cours, encore diffusées) : une fois par
 * jour, leur fiche est rechargée ; une saison sortie, ou annoncée dans le mois, fait une
 * notification (une seule par saison).
 */
class SeasonWatchRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val notifier: SeasonNotifier,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) {
    suspend fun check(): List<SeasonAlert> {
        if (!settings.newSeasonNotifications.first() || settings.tmdbApiKey.first() == null) return emptyList()
        val shows = database.recoDao().followedShows()
        if (shows.isEmpty()) return emptyList()
        var state = settings.seasonWatch.first()
        val alerts = ArrayList<SeasonAlert>()
        val now = today()
        shows.forEach { show ->
            val (fresh, episodes) = try {
                tmdb.refreshShow(show.tmdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TmdbException) {
                return@forEach
            }
            val known = state.known[show.tmdbId]
            val lastAired = episodes.last?.season?.takeIf { it >= 1 }
            val next = episodes.next
            SeasonAlerts.check(
                tmdbId = show.tmdbId,
                title = fresh.title,
                known = known,
                lastAired = lastAired,
                nextSeason = next?.season,
                nextEpisode = next?.episode,
                nextDate = next?.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                today = now,
                sent = state.sent,
            )?.let { alert ->
                alerts += alert
                state = state.copy(sent = state.sent + alert.key)
            }
            state = state.copy(known = state.known + (show.tmdbId to SeasonAlerts.nextKnown(known, lastAired, fresh.seasonCount)))
        }
        settings.setSeasonWatch(state)
        if (alerts.isNotEmpty()) {
            val canRequest = settings.homelabConfig.first().requestService != null
            notifier.show(
                alerts.map { alert ->
                    val user = database.userMovieDao().get(alert.tmdbId)
                    SeasonNotice(alert, finished = user?.progress == null || user.progress == SeriesProgress.FINISHED, canRequest = canRequest)
                },
            )
        }
        return alerts
    }
}

/** Notifications des nouvelles saisons : un toucher ouvre la fiche ; « Remettre en cours », « Demander ». */
class AndroidSeasonNotifier(context: Context) : SeasonNotifier {
    private val context = context.applicationContext

    override fun show(notices: List<SeasonNotice>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        notices.forEach { notice ->
            val alert = notice.alert
            val id = NOTIFICATION_BASE + (alert.tmdbId % 100_000).toInt()
            val title = context.getString(R.string.notify_season_title, alert.title)
            val text = when (alert.kind) {
                SeasonAlert.Kind.OUT -> context.getString(R.string.notify_season_out, alert.season)
                SeasonAlert.Kind.ANNOUNCED -> context.getString(
                    R.string.notify_season_announced,
                    alert.season,
                    alert.date?.format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE)).orEmpty(),
                )
            }
            val open = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("cineswipe://season/${alert.tmdbId}/${alert.season}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(WidgetLinks.EXTRA_OPEN, WidgetLinks.movie(alert.tmdbId))
                .putExtra(WidgetLinks.EXTRA_WORLD, MediaKind.SERIES.name)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(SERIES_ACCENT)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            if (notice.finished && alert.kind == SeasonAlert.Kind.OUT) {
                builder.addAction(0, context.getString(R.string.notify_season_resume), action(SeasonActionReceiver.RESUME, alert, id))
            }
            if (notice.canRequest) {
                builder.addAction(0, context.getString(R.string.notify_season_request), action(SeasonActionReceiver.REQUEST, alert, id))
            }
            runCatching { manager.notify(id, builder.build()) }
        }
    }

    private fun action(action: String, alert: SeasonAlert, id: Int): PendingIntent {
        val intent = Intent(context, SeasonActionReceiver::class.java)
            .setAction(action)
            .putExtra(SeasonActionReceiver.EXTRA_ID, alert.tmdbId)
            .putExtra(SeasonActionReceiver.EXTRA_SEASON, alert.season)
            .putExtra(SeasonActionReceiver.EXTRA_NOTIFICATION, id)
        return PendingIntent.getBroadcast(context, id * 2 + if (action == SeasonActionReceiver.REQUEST) 1 else 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_seasons), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_seasons_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "new_seasons"
        private const val NOTIFICATION_BASE = 5_000_000
        private const val SERIES_ACCENT = 0xFF7C3AED.toInt()
    }
}

/** Boutons des notifications : remettre la série en cours (sur la nouvelle saison), ou la demander. */
class SeasonActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tmdbId = intent.getLongExtra(EXTRA_ID, 0L).takeIf { it > 0 } ?: return
        val season = intent.getIntExtra(EXTRA_SEASON, 0).takeIf { it > 0 } ?: return
        val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val app = context.applicationContext as CineSwipeApp
        val series = app.seriesWorld()
        val pending = goAsync()
        series.appScope.launch {
            try {
                when (intent.action) {
                    RESUME -> series.library.setProgress(tmdbId, SeriesProgress.IN_PROGRESS, season)
                    REQUEST -> {
                        val movie = series.tmdb.ensureDetails(tmdbId)
                        // Une série demandée qui n'est pas encore dans la bibliothèque rejoint la liste À voir.
                        if (series.database.userMovieDao().get(tmdbId) == null) series.library.setStatus(tmdbId, WatchStatus.WATCHLIST)
                        series.homelab.requestMovie(movie, listOf(season))
                    }
                }
                NotificationManagerCompat.from(context).cancel(notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val RESUME = "io.github.deedg.cineswipe.SEASON_RESUME"
        const val REQUEST = "io.github.deedg.cineswipe.SEASON_REQUEST"
        const val EXTRA_ID = "tmdb_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_NOTIFICATION = "notification"
    }
}

/** Vérification quotidienne, appli fermée (vraies données des séries, jamais la démo). */
class SeasonWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as CineSwipeApp).seriesWorld().seasonWatch.check()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.retry()
    }

    companion object {
        private const val WORK = "new-seasons"

        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SeasonWatchWorker>(1, TimeUnit.DAYS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                        .build(),
                )
            }
        }
    }
}
