package io.github.deedg.cineswipe

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import io.github.deedg.cineswipe.ui.retro.RetroTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Rétro de l'année : ouverte depuis le profil, avec sa carte à partager et le top. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class RetroFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking { app.container.settings.setOnboardingDone(true) }
    }

    @Test
    fun theYearInReviewOpensFromTheProfile() {
        runBlocking {
            val library = app.container.database.libraryDao()
            val now = System.currentTimeMillis()
            (1..4).forEach { i ->
                val id = 8100L + i
                library.upsertMovie(MovieEntity(tmdbId = id, title = "Film Rétro $i", posterPath = "/$id.jpg", runtimeMinutes = 120, genres = listOf("Drame")))
                library.upsertUserMovie(
                    UserMovieEntity(
                        tmdbId = id,
                        status = WatchStatus.WATCHED,
                        rating = 1500.0 + 50 * i,
                        watchedAt = now - i * 60_000L,
                        watchedSource = WatchSource.MANUAL,
                        updatedAt = now,
                    ),
                )
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            compose.waitForTag(ProfileTestTags.RETRO)
            compose.onNodeWithTag(ProfileTestTags.RETRO).performScrollTo().performClick()
            compose.waitForTag(RetroTestTags.CARD)
            compose.waitForTag(RetroTestTags.SHARE)
            // Le mieux classé en tête du top ; un toucher ouvre sa fiche.
            compose.waitForTag(RetroTestTags.top(8104))
            compose.onNodeWithTag(RetroTestTags.top(8104)).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(RetroTestTags.CARD).fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
