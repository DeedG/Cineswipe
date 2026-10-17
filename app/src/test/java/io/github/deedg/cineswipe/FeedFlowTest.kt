package io.github.deedg.cineswipe

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.repository.RecoRepository
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.feed.FeedTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Parcours TMDB complet sur un faux serveur : recos (swipe « Vu » puis annulation), recherche
 * et découverte. Vérifie aussi qu'un film déjà vu n'est jamais proposé, que les recos disent
 * pourquoi elles proposent un film et qu'elles apprennent des actions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class FeedFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking { app.container.settings.setTmdbApiKey(FakeTmdbServer.API_KEY) }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setTmdbApiKey(null) }
    }

    @Test
    fun recosSearchAndDiscoverRunOnTmdb() {
        runBlocking { TestData.seed(app.container.database) }
        val first = FakeTmdbServer.RECOS.first().id

        ActivityScenario.launch(MainActivity::class.java).use {
            // Recos : la première carte est affichée et retenue par la mémoire du feed.
            compose.waitForTag(FeedTestTags.card(first))
            compose.waitUntil(TIMEOUT_MS) { userMovie(first)?.shownCount == 1 }
            assertTrue(compose.onAllNodesWithTag(FeedTestTags.card(FakeTmdbServer.WATCHED.id)).fetchSemanticsNodes().isEmpty())
            // Chaque carte dit pourquoi elle est là ; les recommandations des films vus sont gardées.
            // (La ligne est dans la carte cliquable : on la cherche dans l'arbre non fusionné.)
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodesWithTag(FeedTestTags.REASON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(runBlocking { app.container.database.recoDao().seeds() }.isNotEmpty())
            waitOrExplain("cartes montrées") { sources().sumOf { it.shown } >= 1.0 }

            // Swipe gauche = Vu (un demi-succès pour la source : un film déjà vu n'est pas une découverte),
            // puis « Annuler » remet tout comme avant, y compris le bilan des recos.
            compose.onNodeWithTag(FeedTestTags.card(first)).performTouchInput { swipeLeft() }
            compose.waitUntil(TIMEOUT_MS) { userMovie(first)?.status == WatchStatus.WATCHED }
            waitOrExplain("bilan des recos après le swipe") { sources().sumOf { it.liked } == RecoRepository.WATCHED_CREDIT }
            compose.waitForText("Annuler")
            compose.onNodeWithText("Annuler").performClick()
            compose.waitUntil(TIMEOUT_MS) { userMovie(first)?.status == WatchStatus.NONE }
            waitOrExplain("bilan des recos après l'annulation") { sources().sumOf { it.liked } == 0.0 }
            compose.waitForTag(FeedTestTags.card(first))

            // Un toucher sur la carte (ailleurs que sur un bouton) ouvre la fiche du film.
            compose.waitForIdle()
            compose.onNodeWithTag(FeedTestTags.card(first)).performClick()
            compose.waitForText("Marquer comme vu")
            compose.onNodeWithContentDescription("Retour").performClick()
            compose.waitForTag(FeedTestTags.card(first))

            // Recherche : les résultats arrivent en cartes.
            compose.onNodeWithText("Recherche").performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("dune")
            compose.onNodeWithContentDescription("Lancer la recherche").performClick()
            compose.waitForTag(FeedTestTags.card(FakeTmdbServer.DUNE.id))

            // Découverte : les filtres puis le flux.
            compose.onNodeWithText("Découverte").performClick()
            compose.waitForText("Science-Fiction")
            compose.onNodeWithText("Générer mon flux précis").performClick()
            compose.waitForTag(FeedTestTags.card(first))
        }
    }

    private fun userMovie(tmdbId: Long): UserMovieEntity? =
        runBlocking { app.container.database.userMovieDao().get(tmdbId) }

    private fun sources() = runBlocking { app.container.database.recoDao().sources() }

    /**
     * Attend une condition sur la base qui dépend d'une suite exécutée sur le fil principal (le
     * bilan des recos est écrit après l'action) : chaque essai laisse d'abord tourner ce fil.
     * Un échec dit où en sont le bilan des recos et les raisons des cartes.
     */
    private fun waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(TIMEOUT_MS) {
                compose.waitForIdle()
                condition()
            }
        } catch (error: ComposeTimeoutException) {
            val reasons = compose.onAllNodesWithTag(FeedTestTags.REASON, useUnmergedTree = true).fetchSemanticsNodes().size
            val firstReason = compose.onAllNodes(
                hasTestTag(FeedTestTags.REASON) and hasAnyAncestor(hasTestTag(FeedTestTags.card(FakeTmdbServer.RECOS.first().id))),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
            val state = userMovie(FakeTmdbServer.RECOS.first().id)
            throw AssertionError(
                "DIAG $what : sources=${sources()} ; lignes de raison=$reasons ; sur la 1re carte=$firstReason ; 1re carte=$state",
                error,
            )
        }
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
