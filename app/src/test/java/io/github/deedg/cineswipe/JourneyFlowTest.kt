package io.github.deedg.cineswipe

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.domain.journey.Journey
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.JourneyTestApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.ui.census.CensusTestTags
import io.github.deedg.cineswipe.ui.feed.FeedTestTags
import io.github.deedg.cineswipe.ui.journey.JourneyTestTags
import io.github.deedg.cineswipe.ui.navigation.BottomBarTestTags
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.recos.RecosTestTags
import io.github.deedg.cineswipe.ui.welcome.WelcomeTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Parcours d'un nouvel utilisateur, de l'accueil aux recos : on recense (étape 1), on note en
 * duels (étape 2), et seulement alors les recos s'ouvrent. Chaque écran dit où on en est et mène à
 * la suite.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = JourneyTestApp::class)
class JourneyFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            // Pas de clé saisie : celle du build suffit, comme pour un nouvel utilisateur.
            app.container.settings.setTmdbApiKey(null)
            app.container.settings.setOnboardingDone(false)
            app.container.settings.setJourneySkipped(false)
            app.container.settings.setRecosHintSeen(false)
            // Un autre test a pu laisser le recensement en cartes.
            app.container.settings.setCensusCardsMode(false)
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setOnboardingDone(true) }
    }

    @Test
    fun aNewUserIsGuidedFromTheCensusToTheRecos() {
        val first = FakeTmdbServer.RECOS.first().id
        ActivityScenario.launch(MainActivity::class.java).use {
            // Accueil : les trois étapes, puis « C'est parti » mène droit au recensement.
            compose.waitForTag(WelcomeTestTags.START)
            compose.waitForText("Recense tes films vus")
            compose.onNodeWithTag(WelcomeTestTags.START).performScrollTo().performClick()
            compose.waitForTag(CensusTestTags.cell(first))
            compose.waitForText("Étape 1 sur 3")
            compose.waitForText("0 sur ${Journey.FILMS_WATCHED} films vus")
            compose.waitForText("Touche les films que tu as vus, puis « Suivant » en bas.")

            // Un film recensé : le compteur de l'étape avance.
            compose.onNodeWithTag(CensusTestTags.cell(first)).performClick()
            compose.onNodeWithTag(CensusTestTags.NEXT).performClick()
            compose.waitForText("1 sur ${Journey.FILMS_WATCHED} films vus")

            // Les recos attendent : l'onglet montre le parcours, avec l'étape en cours.
            compose.onNodeWithTag(BottomBarTestTags.tab(Tab.RECOS)).performClick()
            compose.waitForTag(JourneyTestTags.SCREEN)
            compose.waitForText("Continuer le recensement")
            assertTrue(compose.onAllNodesWithTag(FeedTestTags.card(FakeTmdbServer.RECOS[1].id)).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(JourneyTestTags.CONTINUE).performScrollTo().performClick()
            compose.waitForText("Étape 1 sur 3")

            // Objectif de films atteint : le recensement annonce la suite et mène à l'Arène.
            runBlocking { TestData.seedWatched(app.container.database, Journey.FILMS_WATCHED, duels = 0) }
            compose.waitForText("Étape 1 terminée !")
            compose.onNodeWithTag(JourneyTestTags.BANNER_ACTION).performClick()
            compose.waitForTag(ArenaTestTags.FIGHTER_A)
            compose.waitForText("Étape 2 sur 3")
            compose.waitForText("Touche le film que tu préfères.")
            // Premiers duels : ni Elo ni étoiles, seulement les deux films.
            assertTrue(compose.onAllNodesWithTag(ArenaTestTags.RATING, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

            // Objectif de duels atteint sous nos yeux : l'Arène annonce les recos.
            runBlocking { TestData.seedWatched(app.container.database, Journey.FILMS_WATCHED, duels = 2) }
            compose.waitForText("Tes recos sont prêtes !")
            assertTrue(compose.onAllNodesWithTag(ArenaTestTags.RATING, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag(JourneyTestTags.BANNER_ACTION).performClick()

            // Les recos sont ouvertes, avec le rappel des gestes ; « Compris » le referme pour de bon.
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(JourneyTestTags.SCREEN).fetchSemanticsNodes().isEmpty() }
            compose.waitForTag(RecosTestTags.HINT)
            compose.onNodeWithTag(RecosTestTags.HINT_OK).performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(RecosTestTags.HINT).fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { app.container.settings.recosHintSeen.first() })
        }
    }

    private fun ComposeTestRule.waitForText(text: String) = waitOrExplain("texte « $text »") { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.waitForTag(tag: String) = waitOrExplain("repère $tag") { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Un échec dit ce que l'écran affichait. */
    private fun ComposeTestRule.waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            waitUntil(TIMEOUT_MS, condition)
        } catch (error: ComposeTimeoutException) {
            val screen = runCatching {
                onAllNodes(isRoot()).printToString(maxDepth = 40).lineSequence()
                    .mapNotNull { line -> Regex("Text = '(.*)'|Tag: '(.*)'").find(line)?.value }
                    .joinToString(" | ")
            }.getOrElse { it.toString() }
            throw AssertionError("DIAG $what introuvable. Écran : ${screen.takeLast(2500)}", error)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
