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
import io.github.deedg.cineswipe.ui.navigation.DemoTestTags
import io.github.deedg.cineswipe.ui.navigation.Route
import io.github.deedg.cineswipe.ui.settings.TmdbKeyTestTags
import io.github.deedg.cineswipe.ui.tutorial.TutorialTestTags
import io.github.deedg.cineswipe.ui.welcome.WelcomeTestTags
import kotlinx.coroutines.Dispatchers
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
 * Démo : proposée à l'accueil du premier lancement, elle fait tourner toute l'appli sur un bac à
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
        // Premier lancement, sans clé ni film : l'accueil propose la démo.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag(WelcomeTestTags.DEMO)
            compose.onNodeWithTag(WelcomeTestTags.DEMO).performClick()
            waitOrExplain("démo prête") { app.inDemo }
        }

        // L'écran repart sur le bac à sable : bandeau, tuto pas à pas, profil de démo, recos et Arène.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag(DemoTestTags.STRIP)
            compose.waitForTag(TutorialTestTags.step(0))
            val watched = runBlocking { app.container.database.userMovieDao().watchedCount() }
            assertTrue(watched >= 15)
            assertTrue(runBlocking { app.container.database.arenaDao().lastDuel() } != null)
            waitOrExplain("cartes des recos de la démo") {
                compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty()
            }

            // Étapes d'explication : « Suivant ».
            compose.onNodeWithTag(TutorialTestTags.NEXT).performClick()
            compose.waitForTag(TutorialTestTags.step(1))
            compose.onNodeWithTag(TutorialTestTags.NEXT).performClick()
            compose.waitForTag(TutorialTestTags.step(2))

            // Étape à faire soi-même : le swipe à gauche passe sous le voile, marque le film vu
            // et fait avancer le tuto.
            swipeCurrentCardLeft()
            val duringTutorial = waitFor { compose.onAllNodesWithTag(TutorialTestTags.step(3)).fetchSemanticsNodes().isNotEmpty() }
            val watchedDuring = runBlocking { app.container.database.userMovieDao().watchedCount() }

            // « Montre-moi » : la main fait le geste et l'appli réagit pour de vrai.
            val listed = watchlistCount()
            compose.onNodeWithTag(TutorialTestTags.SHOW).performClick()
            waitOrExplain("swipe à droite joué par le tuto") { stepShown(4) && watchlistCount() == listed + 1 }
            compose.onNodeWithTag(TutorialTestTags.SHOW).performClick()
            waitOrExplain("défilement joué par le tuto") { stepShown(5) }

            // Changement d'écran : la main touche l'onglet de l'Arène, qui s'ouvre.
            compose.onNodeWithTag(TutorialTestTags.NEXT).performClick()
            waitOrExplain("trajet vers l'Arène") {
                stepShown(6) && app.container.tutorial.route == Route.Arena &&
                    compose.onAllNodesWithTag(ArenaTestTags.FIGHTER_A).fetchSemanticsNodes().isNotEmpty()
            }
            val duels = duelCount()
            compose.onNodeWithTag(TutorialTestTags.SHOW).performClick()
            waitOrExplain("vote joué par le tuto") { stepShown(7) && duelCount() == duels + 1 }

            // « Passer le tuto » le ferme ; « Tuto », dans le bandeau, le relance et ramène aux recos.
            compose.onNodeWithTag(TutorialTestTags.SKIP).performClick()
            waitOrExplain("tuto fermé") { compose.onAllNodesWithTag(TutorialTestTags.BUBBLE).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag(DemoTestTags.TUTORIAL).performClick()
            waitOrExplain("retour aux recos pour le tuto") { stepShown(0) && app.container.tutorial.route == Route.Recos }
            compose.onNodeWithTag(TutorialTestTags.SKIP).performClick()
            waitOrExplain("tuto refermé") { compose.onAllNodesWithTag(TutorialTestTags.BUBBLE).fetchSemanticsNodes().isEmpty() }

            // Sans le tuto, le même geste marque aussi le film vu (témoin).
            swipeCurrentCardLeft()
            val withoutTutorial = waitFor {
                runBlocking { app.container.database.userMovieDao().watchedCount() } > watchedDuring
            }
            assertTrue(
                "DIAG swipe : pendant le tuto=$duringTutorial (vus $watched -> $watchedDuring), sans le tuto=$withoutTutorial",
                duringTutorial && watchedDuring == watched + 1 && withoutTutorial,
            )

            compose.onNodeWithText("Arène").performClick()
            compose.waitForTag(ArenaTestTags.FIGHTER_A)

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

    private fun stepShown(index: Int) = compose.onAllNodesWithTag(TutorialTestTags.step(index)).fetchSemanticsNodes().isNotEmpty()

    private fun watchlistCount(): Int = runBlocking(Dispatchers.IO) {
        app.container.database.query("SELECT COUNT(*) FROM user_movie WHERE status = 'WATCHLIST'", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun duelCount(): Int = runBlocking { app.container.database.diagnosticsDao().duelCount() }

    /** Attend une condition jusqu'à 10 s ; renvoie faux au lieu d'échouer. */
    private fun waitFor(condition: () -> Boolean): Boolean = try {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            condition()
        }
        true
    } catch (error: ComposeTimeoutException) {
        false
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
            val tutorial = app.container.tutorial
            val cards = runCatching {
                compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes()
                    .joinToString { "${it.config.getOrNull(SemanticsProperties.TestTag)}@${it.boundsInRoot}" }
            }.getOrNull()
            val bubble = runCatching {
                compose.onAllNodesWithTag(TutorialTestTags.BUBBLE).fetchSemanticsNodes().joinToString { it.boundsInRoot.toString() }
            }.getOrNull()
            val watched = runCatching { runBlocking { app.container.database.userMovieDao().watchedCount() } }.getOrNull()
            throw AssertionError(
                "DIAG $what : démo=${app.inDemo} étape=${tutorial.stepIndex} cibles=${tutorial.bounds.toMap()} " +
                    "vus=$watched cartes=$cards bulle=$bubble",
                error,
            )
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
