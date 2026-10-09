package io.github.deedg.cineswipe

import android.content.Intent
import android.graphics.Color
import io.github.deedg.cineswipe.widget.WidgetLinks
import io.github.deedg.cineswipe.widget.Widgets
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.deedg.cineswipe.ui.navigation.CineSwipeRoot
import io.github.deedg.cineswipe.ui.theme.CineSwipeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
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
        receiveShare(intent)
        receiveOpen(intent)
    }

    /** Un widget ouvre un écran précis (« Ce soir », les stats, un film). */
    private fun receiveOpen(intent: Intent?) {
        val target = intent?.getStringExtra(WidgetLinks.EXTRA_OPEN) ?: return
        (application as CineSwipeApp).openInbox.value = target
    }

    override fun onStop() {
        super.onStop()
        // Les widgets suivent ce qui vient de changer (films vus, duels, liste À voir).
        val app = application as CineSwipeApp
        app.mainContainer.appScope.launch { Widgets.updateAll(app) }
    }

    /** « Partager › CineSwipe » depuis Letterboxd, IMDb, TMDB, un navigateur… : le texte est mis de côté pour l'écran. */
    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n").trim()
        if (text.isNotEmpty()) (application as CineSwipeApp).sharedInbox.value = text.take(MAX_SHARED_LENGTH)
    }

    private companion object {
        const val MAX_SHARED_LENGTH = 2_000
    }

    override fun onStart() {
        super.onStart()
        // Homelab : demandes en attente envoyées et synchro si la dernière date un peu.
        (application as CineSwipeApp).mainContainer.homelab.onForeground()
    }
}
