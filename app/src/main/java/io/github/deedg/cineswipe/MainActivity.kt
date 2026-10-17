package io.github.deedg.cineswipe

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.deedg.cineswipe.data.homelab.WatchedNotifier
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.domain.taste.TasteCode
import io.github.deedg.cineswipe.ui.demo.restartMain
import io.github.deedg.cineswipe.ui.navigation.CineSwipeRoot
import io.github.deedg.cineswipe.ui.theme.CineSwipeTheme
import io.github.deedg.cineswipe.widget.WidgetLinks
import io.github.deedg.cineswipe.widget.Widgets
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            receiveShortcut(intent)
            receiveShare(intent)
            receiveOpen(intent)
        }
        setContent {
            CineSwipeTheme {
                CineSwipeRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Un raccourci, un widget, une notification ou un partage qui change de monde relance l'écran.
        val switched = receiveShortcut(intent) or receiveShare(intent) or receiveOpen(intent)
        if (switched) restartMain()
    }

    /**
     * Raccourci « Films » ou « Séries » de l'icône (appui long). Vrai si le monde a changé
     * (l'écran déjà affiché doit alors être relancé).
     */
    private fun receiveShortcut(intent: Intent?): Boolean {
        val target = when (intent?.action) {
            ACTION_FILMS -> MediaKind.FILMS
            ACTION_SERIES -> MediaKind.SERIES
            else -> return false
        }
        return enterWorld(target)
    }

    /**
     * Les widgets, les notifications et les partages parlent de films : ils rouvrent le monde des
     * films. Vrai si le monde a changé.
     */
    private fun enterWorld(target: MediaKind): Boolean {
        val app = application as CineSwipeApp
        if (app.inDemo || app.kind == target) return false
        runBlocking { app.switchWorld(target) }
        return true
    }

    /** Un widget ouvre un écran précis (« Ce soir », les stats, un film). Vrai si le monde a changé. */
    private fun receiveOpen(intent: Intent?): Boolean {
        val target = intent?.getStringExtra(WidgetLinks.EXTRA_OPEN) ?: return false
        // Notification d'une série vue sur le serveur : l'Arène des séries ; un widget dit son monde ;
        // le reste parle de films.
        val world = when {
            WatchedNotifier.isSeriesLink(target) -> MediaKind.SERIES
            else -> MediaKind.fromName(intent.getStringExtra(WidgetLinks.EXTRA_WORLD))
        }
        val switched = enterWorld(world)
        (application as CineSwipeApp).openInbox.value = target
        return switched
    }

    override fun onStop() {
        super.onStop()
        // Les widgets suivent ce qui vient de changer (films vus, duels, liste À voir).
        val app = application as CineSwipeApp
        app.mainContainer.appScope.launch { Widgets.updateAll(app) }
    }

    /** « Partager › CineSwipe » depuis Letterboxd, IMDb, TMDB, un navigateur… : le texte est mis de côté pour l'écran. */
    private fun receiveShare(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n").trim()
        if (text.isEmpty()) return false
        // Code de goûts d'un ami : le monde du code (films ou séries) ; un film partagé : les films.
        val friend = TasteCode.decode(text)
        val switched = enterWorld(if (friend?.series == true) MediaKind.SERIES else MediaKind.FILMS)
        (application as CineSwipeApp).sharedInbox.value = if (friend != null) text.take(MAX_CODE_LENGTH) else text.take(MAX_SHARED_LENGTH)
        return switched
    }

    companion object {
        private const val MAX_SHARED_LENGTH = 2_000
        private const val MAX_CODE_LENGTH = 8_000

        /** Raccourcis de l'icône (res/xml/shortcuts.xml). */
        const val ACTION_FILMS = "io.github.deedg.cineswipe.FILMS"
        const val ACTION_SERIES = "io.github.deedg.cineswipe.SERIES"
    }

    override fun onStart() {
        super.onStart()
        // Homelab : demandes en attente envoyées et synchro si la dernière date un peu.
        (application as CineSwipeApp).homelabs().forEach { it.onForeground() }
    }
}
