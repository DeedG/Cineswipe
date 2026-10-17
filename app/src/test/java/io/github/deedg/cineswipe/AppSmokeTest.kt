package io.github.deedg.cineswipe

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import io.github.deedg.cineswipe.ui.settings.TmdbKeyTestTags
import io.github.deedg.cineswipe.ui.welcome.WelcomeTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L'appli telle quelle, sans la clé TMDB intégrée (secret de la CI) ni la vérification des mises
 * à jour : le parcours sans clé reste testé, et aucun appel réseau ne part.
 */
class SmokeApp : CineSwipeApp() {
    private fun config() = AppConfig(builtinTmdbKey = null, updateUrl = null)

    override fun createContainer(): AppContainer = AppContainer(this, config())

    override fun createSeriesContainer(): AppContainer = AppContainer(this, config(), kind = io.github.deedg.cineswipe.data.model.MediaKind.SERIES)
}

/**
 * Lance la vraie appli (Robolectric) et parcourt les écrans, pour détecter un plantage au
 * démarrage ou à la navigation, à vide puis avec une petite bibliothèque. Sans clé TMDB,
 * aucun appel réseau ne part.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = SmokeApp::class)
class AppSmokeTest {

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

    @Test
    fun freshInstallOpensEveryScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Premier lancement : l'accueil, puis « Commencer ».
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(WelcomeTestTags.START).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(WelcomeTestTags.START).performClick()
            // Sans clé, le guide de la clé TMDB s'ouvre d'emblée ; « Plus tard » ramène aux Recos.
            compose.waitForTag(TmdbKeyTestTags.LATER)
            compose.waitForText("Ta clé TMDB")
            compose.onNodeWithTag(TmdbKeyTestTags.LATER).performScrollTo().performClick()
            // Les Recos ouvrent l'appli et réclament la clé TMDB.
            compose.waitForText("Ajoute ta clé TMDB")

            compose.onNodeWithText("Arène").performClick()
            compose.waitForText("Pas assez de films pour combattre")
            compose.onNodeWithText("Recherche").performClick()
            compose.waitForText("Rechercher")
            compose.onNodeWithText("Découverte").performClick()
            compose.waitForText("Ajoute ta clé TMDB")

            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.onNodeWithText("Voir ma bibliothèque").performScrollTo().performClick()
            compose.waitForText("Ta bibliothèque est vide")
            compose.onNodeWithContentDescription("Retour").performClick()

            compose.waitForText("Hall of Fame")
            // Le profil est resté défilé jusqu'au bouton de la bibliothèque : on remonte aux réglages.
            compose.onNodeWithContentDescription("Réglages").performScrollTo().performClick()
            compose.waitForText("Clé API TMDB")
            compose.waitForText("Mes données")
            compose.waitForText("Recommencer le classement")
        }
    }

    @Test
    fun libraryFeedsArenaProfileLibraryAndDetail() {
        runBlocking { TestData.seed(app.container.database) }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Ajoute ta clé TMDB")
            compose.onNodeWithText("Arène").performClick()

            // Un vote puis son annulation : aucun duel ne doit rester enregistré.
            compose.waitForTag(ArenaTestTags.FIGHTER_A)
            compose.onNodeWithTag(ArenaTestTags.FIGHTER_A).performClick()
            compose.waitForText("Annuler")
            compose.onNodeWithText("Annuler").performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { app.container.database.arenaDao().lastDuel() } == null }
            compose.waitForTag(ArenaTestTags.FIGHTER_B)
            compose.waitForTag(ArenaTestTags.RELIABILITY)

            // Égalité : enregistrée comme telle, puis annulée elle aussi.
            compose.waitForTag(ArenaTestTags.DRAW)
            compose.onNodeWithTag(ArenaTestTags.DRAW).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { app.container.database.arenaDao().lastDuel() }?.draw == true }
            compose.waitForText("Annuler")
            compose.onNodeWithText("Annuler").performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { app.container.database.arenaDao().lastDuel() } == null }

            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.waitForText("Drive")
            compose.waitForText(TestData.DUELS.toString())

            // Les compteurs du profil ouvrent la bibliothèque filtrée et l'historique des duels.
            compose.onNodeWithTag(ProfileTestTags.WATCHED).performClick()
            compose.waitForText("Interstellar (2014)")
            assertTrue(compose.onAllNodesWithText("Léon (1994)").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Retour").performClick()
            compose.waitForTag(ProfileTestTags.DUELS)
            compose.onNodeWithTag(ProfileTestTags.DUELS).performClick()
            compose.waitForText("Historique des duels")
            compose.waitForText("${TestData.DUELS} duels de l’ancienne version n’ont pas d’historique détaillé.")
            compose.onNodeWithContentDescription("Retour").performClick()

            compose.waitForText("Voir ma bibliothèque")
            compose.onNodeWithText("Voir ma bibliothèque").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("premier")
            compose.waitForText("Premier Contact (2016)")
            compose.onNodeWithText("Premier Contact (2016)").performClick()
            compose.waitForText("Retirer des vus")
            compose.waitForText("Réalisé par Denis Villeneuve")
        }
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
