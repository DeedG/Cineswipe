package io.github.deedg.cineswipe

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.demo.DemoSeeder
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.ui.journey.JourneyTestTags
import io.github.deedg.cineswipe.ui.navigation.DemoTestTags
import io.github.deedg.cineswipe.ui.settings.TmdbKeyTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Démo : lancée depuis les réglages, elle fait tourner toute l'appli sur un bac à
 * sable (les 100 films les plus vus, un profil fictif, TMDB simulé), puis rend les vraies données
 * intactes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class DemoFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Les réglages DataStore survivent d'un test à l'autre dans la même JVM : premier lancement
     * simulé (ni clé TMDB, ni accueil déjà passé).
     */
    @Before
    fun freshSettings() {
        runBlocking {
            app.container.settings.setTmdbApiKey(null)
            app.container.settings.setOnboardingDone(false)
        }
    }

    @After
    fun tearDown() {
        app.stopDemo()
    }

    @Test
    fun theDemoIsASandboxThatLeavesRealDataUntouched() {
        // La démo se lance depuis les réglages ; ici, directement.
        runBlocking { app.startDemo() }
        assertTrue(app.inDemo)

        // L'écran s'ouvre sur le bac à sable : bandeau, profil de démo, recos ouvertes (pas de parcours) et Arène.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag(DemoTestTags.STRIP)
            val watched = runBlocking { app.container.database.userMovieDao().watchedCount() }
            assertTrue(watched >= 15)
            assertTrue(runBlocking { app.container.database.arenaDao().lastDuel() } != null)
            waitOrExplain("cartes des recos de la démo") {
                compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodesWithTag(JourneyTestTags.SCREEN).fetchSemanticsNodes().isEmpty())

            // Un swipe à gauche marque le film vu, dans le bac à sable.
            swipeCurrentCardLeft()
            waitOrExplain("film marqué vu") { runBlocking { app.container.database.userMovieDao().watchedCount() } == watched + 1 }

            compose.onNodeWithText("Arène").performClick()
            compose.waitForTag(ArenaTestTags.FIGHTER_A)
            assertTrue(compose.onAllNodesWithTag(JourneyTestTags.BANNER).fetchSemanticsNodes().isEmpty())

            // Quitter : retour aux vraies données, vides et intactes, sans repasser par l'accueil.
            compose.onNodeWithTag(DemoTestTags.EXIT).performClick()
            waitOrExplain("démo quittée") { !app.inDemo && runBlocking { app.container.settings.onboardingDone.first() } }
        }

        // L'écran repart sur les vraies données : sans clé TMDB, le guide de la clé s'ouvre.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitOrExplain("guide de la clé") { compose.onAllNodesWithTag(TmdbKeyTestTags.FIELD).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TmdbKeyTestTags.LATER).performScrollTo().performClick()
            waitOrExplain("guide fermé") { !app.keyGuidePending }
        }
        assertFalse(app.container.isDemo)
        assertEquals(0, runBlocking { app.container.database.userMovieDao().watchedCount() })
        assertTrue(runBlocking { app.container.settings.onboardingDone.first() })
        assertTrue(runBlocking { app.container.settings.tmdbApiKey.first() } == null)
        assertTrue(DemoSeeder.WATCHED.isNotEmpty())
    }

    /** Swipe à gauche sur la carte des recos affichée (celle du haut de l'écran). */
    private fun swipeCurrentCardLeft() {
        compose.waitForIdle()
        val card = compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
            .minBy { it.boundsInRoot.top }
            .config[SemanticsProperties.TestTag]
        compose.onNode(hasTestTag(card)).performTouchInput { swipeLeft() }
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag commençant par $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /** Attend une condition en laissant tourner le fil principal ; un échec dit où en est la démo. */
    private fun waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(TIMEOUT_MS) {
                compose.waitForIdle()
                condition()
            }
        } catch (error: ComposeTimeoutException) {
            val cards = runCatching {
                compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes()
                    .joinToString { "${it.config.getOrNull(SemanticsProperties.TestTag)}@${it.boundsInRoot}" }
            }.getOrNull()
            val watched = runCatching { runBlocking { app.container.database.userMovieDao().watchedCount() } }.getOrNull()
            throw AssertionError("DIAG $what : démo=${app.inDemo} vus=$watched cartes=$cards", error)
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
