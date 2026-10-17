package io.github.deedg.cineswipe

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.homelab.AndroidWatchedNotifier
import io.github.deedg.cineswipe.data.homelab.FreshWatch
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.WatchedNotice
import io.github.deedg.cineswipe.data.homelab.WatchedNotifier
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.widget.WidgetLinks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Après un film vu sur Plex ou Jellyfin : une notification, qui ouvre l'Arène sur ce film. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class WatchedNotificationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking { app.container.settings.setOnboardingDone(true) }
    }

    @Test
    fun onlyRecentServerWatchesNotify() {
        val now = 10L * 24 * 60 * 60 * 1000
        val fresh = listOf(
            FreshWatch(1, WatchSource.PLEX, now - 60_000),
            FreshWatch(2, WatchSource.PLEX, now - 5L * 24 * 60 * 60 * 1000),
            FreshWatch(3, WatchSource.JELLYFIN, null),
            FreshWatch(4, WatchSource.JELLYFIN, now - 47L * 60 * 60 * 1000),
        )
        assertEquals(listOf(1L, 4L), HomelabRepository.recentWatches(fresh, now).map { it.tmdbId })
        assertEquals(listOf(5L, 6L), WatchedNotifier.parseRankLink(WatchedNotifier.rankLink(listOf(5, 6))))
        assertEquals(null, WatchedNotifier.parseRankLink("movie:5"))
    }

    @Test
    fun theNotificationOpensTheArenaOnTheFilm() {
        runBlocking { TestData.seed(app.container.database) }
        val film = runBlocking { app.container.database.userMovieDao().ranked() }.first()
        AndroidWatchedNotifier(app).show(listOf(WatchedNotice(film.tmdbId, "Film vu", WatchSource.PLEX)))

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Vu sur Plex : Film vu", posted.extras.getCharSequence("android.title")?.toString())
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(WatchedNotifier.rankLink(listOf(film.tmdbId)), intent.getStringExtra(WidgetLinks.EXTRA_OPEN))

        // Toucher la notification : l'Arène s'ouvre et place ce film (bandeau, et il joue le duel).
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ArenaTestTags.FOCUS).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(film.tmdbId, app.container.arena.focus.value?.current)
        }
        assertTrue(app.container.arena.focus.value?.played == 0)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
