package io.github.deedg.cineswipe.data.homelab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.deedg.cineswipe.MainActivity
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.widget.WidgetLinks
import java.time.LocalTime

/** Un film tout juste vu sur un serveur média. */
data class WatchedNotice(val tmdbId: Long, val title: String, val source: WatchSource)

/** Prévient qu'un film vient d'être vu sur Plex ou Jellyfin, pour le placer dans le classement. */
fun interface WatchedNotifier {
    fun show(films: List<WatchedNotice>)

    companion object {
        /** Rien (tests, démo). */
        val None = WatchedNotifier { }

        /** Lien « placer ces films dans l'Arène » (ouvert par la notification). */
        fun rankLink(ids: List<Long>): String = RANK_PREFIX + ids.joinToString(",")

        /** Films à placer d'après le lien, ou null si ce n'en est pas un. */
        fun parseRankLink(link: String): List<Long>? =
            link.takeIf { it.startsWith(RANK_PREFIX) }?.removePrefix(RANK_PREFIX)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() }

        private const val RANK_PREFIX = "rank:"
    }
}

/** Notification Android : un toucher (ou « Classer ») ouvre l'Arène sur ces films. */
class AndroidWatchedNotifier(private val context: Context) : WatchedNotifier {
    override fun show(films: List<WatchedNotice>) {
        if (films.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        ensureChannel()
        val server = films.map { it.source }.distinct().singleOrNull()?.let(::serverName)
        val title = if (films.size == 1) {
            if (server != null) {
                context.getString(R.string.notify_watched_one_on, server, films.first().title)
            } else {
                context.getString(R.string.notify_watched_one, films.first().title)
            }
        } else {
            context.resources.getQuantityString(R.plurals.notify_watched_many, films.size, films.size)
        }
        val text = if (films.size == 1) {
            context.getString(R.string.notify_watched_text_one)
        } else {
            context.getString(R.string.notify_watched_text_many, films.take(MAX_TITLES).joinToString(", ") { it.title })
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(WidgetLinks.EXTRA_OPEN, WatchedNotifier.rankLink(films.map { it.tmdbId }))
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.notify_watched_action), pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            // La nuit, la notification arrive sans bruit.
            .setSilent(isNight())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_watched), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_watched_description)
        manager.createNotificationChannel(channel)
    }

    private fun serverName(source: WatchSource): String? = when (source) {
        WatchSource.PLEX -> "Plex"
        WatchSource.JELLYFIN -> "Jellyfin"
        else -> null
    }

    private fun isNight(): Boolean {
        val hour = LocalTime.now().hour
        return hour >= NIGHT_START || hour < NIGHT_END
    }

    companion object {
        const val CHANNEL_ID = "watched_on_server"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_CODE = 4201
        private const val MAX_TITLES = 3
        private const val NIGHT_START = 23
        private const val NIGHT_END = 8
        private const val ACCENT = 0xFFED1C24.toInt()
    }
}
