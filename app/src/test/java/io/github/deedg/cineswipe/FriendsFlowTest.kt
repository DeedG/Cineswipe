package io.github.deedg.cineswipe

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.domain.taste.TasteCode
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.friends.FriendsTestTags
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Entre amis : mon QR code, puis la comparaison avec le code d'un ami (collé). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class FriendsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    private val watched = FakeTmdbServer.RECOS.map { it.id } + FakeTmdbServer.DUNE.id

    @Before
    fun setUp() {
        runBlocking(Dispatchers.IO) {
            app.container.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            app.container.settings.setOnboardingDone(true)
            watched.forEach { id ->
                app.container.tmdb.ensureDetails(id)
                app.container.library.markWatched(id)
            }
            // Quelques duels : le premier gagne tout, le dernier perd tout.
            val arena = app.container.database.arenaDao()
            watched.drop(1).forEach { arena.recordDuel(winnerId = watched.first(), loserId = it, draw = false, now = 10) }
            watched.dropLast(1).forEach { arena.recordDuel(winnerId = it, loserId = watched.last(), draw = false, now = 20) }
            arena.rerank()
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setTmdbApiKey(null) }
    }

    @Test
    fun myCodeIsShownAndAFriendCodeIsCompared() {
        val mine = runBlocking(Dispatchers.IO) { app.container.tasteShare.myCode("Alex") }
        assertEquals(watched.toSet(), mine.ratings.keys)
        // L'ami a vu les mêmes films, avec les mêmes étoiles.
        val friend = TasteCode("Sam", series = false, ratings = mine.ratings, watchlist = emptyList()).encode()

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            compose.waitForTag(ProfileTestTags.FRIENDS)
            compose.onNodeWithTag(ProfileTestTags.FRIENDS).performScrollTo().performClick()
            compose.waitForTag(FriendsTestTags.QR)
            // Le prénom tapé est enregistré (une fois la saisie finie).
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(FriendsTestTags.NAME) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(FriendsTestTags.NAME).performTextInput("Alex")
            compose.onNodeWithTag(FriendsTestTags.NAME).assertTextContains("Alex")
            // L'enregistrement attend la fin de la saisie (400 ms) : l'horloge de Robolectric avance à la main.
            compose.waitUntil(TIMEOUT_MS) {
                ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
                runBlocking { app.container.settings.tasteName.first() } == "Alex"
            }

            compose.onNodeWithTag(FriendsTestTags.TAB_SCAN).performClick()
            compose.waitForTag(FriendsTestTags.PASTE)
            compose.onNodeWithTag(FriendsTestTags.PASTE).performScrollTo().performTextInput("bonjour")
            compose.onNodeWithTag(FriendsTestTags.PASTE_GO).performScrollTo().performClick()
            compose.waitForTag(FriendsTestTags.ERROR)

            compose.onNodeWithTag(FriendsTestTags.PASTE).performTextInput(friend)
            compose.onNodeWithTag(FriendsTestTags.PASTE_GO).performScrollTo().performClick()
            compose.waitForTag(FriendsTestTags.SCORE)
            compose.onNodeWithText("Toi et Sam").assertExists()
            // Mêmes étoiles sur 4 films : 78 % (un petit échantillon reste prudent).
            compose.onNodeWithText("78 %").assertExists()
        }
        assertTrue(TasteCode.decode(friend) != null)
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
