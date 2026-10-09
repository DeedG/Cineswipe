package io.github.deedg.cineswipe

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.db.Impression
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.census.CensusTestTags
import io.github.deedg.cineswipe.ui.discover.DiscoverTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Recensement sur le faux serveur TMDB : la grille (vu, adoré, page suivante, annulation), la
 * suite d'un film vu qui arrive par sa saga, puis le mode cartes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class CensusFlowTest {

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
    fun censusFindsWatchedFilmsAndTheirSequels() {
        runBlocking { TestData.seed(app.container.database) }
        val (first, second, third) = FakeTmdbServer.RECOS.map { it.id }
        val sequel = FakeTmdbServer.SEQUEL.id

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Découverte").performClick()
            compose.waitForTag(DiscoverTestTags.CENSUS)
            compose.onNodeWithTag(DiscoverTestTags.CENSUS).performClick()

            // La grille montre le catalogue TMDB, jamais un film déjà vu (Drive).
            listOf(first, second, third).forEach { compose.waitForTag(CensusTestTags.cell(it)) }
            assertTrue(compose.onAllNodesWithTag(CensusTestTags.cell(FakeTmdbServer.WATCHED.id)).fetchSemanticsNodes().isEmpty())

            // Un toucher : vu. Deux : adoré. Sans réponse : pas vu.
            compose.onNodeWithTag(CensusTestTags.cell(first)).performClick()
            compose.onNodeWithTag(CensusTestTags.cell(second)).performClick()
            compose.onNodeWithTag(CensusTestTags.cell(second)).performClick()
            compose.onNodeWithTag(CensusTestTags.NEXT).performClick()

            waitOrExplain("réponses de la page") { userMovie(first)?.status == WatchStatus.WATCHED }
            assertEquals(WatchStatus.WATCHED, userMovie(second)?.status)
            assertEquals(Impression.LOVED, userMovie(second)?.impression)
            assertEquals(WatchStatus.NONE, userMovie(third)?.status)
            assertNotNull(userMovie(third)?.censusAt)
            // Les films vus prennent place dans l'Arène ; « adoré » part plus haut.
            waitOrExplain("classement des films recensés") {
                val loved = userMovie(second)?.rating ?: 0.0
                val seen = userMovie(first)?.rating ?: 0.0
                loved > seen + 100
            }

            // La suite du film vu arrive par sa saga, dans la page suivante.
            compose.waitForTag(CensusTestTags.cell(sequel))

            // « Annuler » remet la page d'avant, réponses comprises, et efface ce qui avait été enregistré.
            compose.onNodeWithTag(CensusTestTags.UNDO).performClick()
            waitOrExplain("annulation de la page") {
                userMovie(first)?.status != WatchStatus.WATCHED && userMovie(third)?.censusAt == null
            }
            compose.waitForTag(CensusTestTags.cell(third))

            // En cartes, les réponses déjà données partent, puis on répond carte par carte.
            compose.onNodeWithTag(CensusTestTags.MODE).performClick()
            waitOrExplain("réponses données avant le passage en cartes") { userMovie(first)?.status == WatchStatus.WATCHED }
            compose.waitForTag(CensusTestTags.CARD)
            compose.onNodeWithTag(CensusTestTags.action(CensusChoice.WATCHLIST)).performClick()
            waitOrExplain("carte « à voir »") { userMovie(third)?.status == WatchStatus.WATCHLIST }
            compose.waitForTag(CensusTestTags.CARD)
            compose.onNodeWithTag(CensusTestTags.action(CensusChoice.SEEN)).performClick()
            waitOrExplain("carte « vu »") { userMovie(sequel)?.status == WatchStatus.WATCHED }
        }
    }

    private fun userMovie(tmdbId: Long): UserMovieEntity? =
        runBlocking { app.container.database.userMovieDao().get(tmdbId) }

    /** Attend une condition sur la base en laissant tourner le fil principal ; un échec dit où en est le recensement. */
    private fun waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(TIMEOUT_MS) {
                compose.waitForIdle()
                condition()
            }
        } catch (error: ComposeTimeoutException) {
            val rows = (FakeTmdbServer.RECOS + FakeTmdbServer.SEQUEL).associate { it.id to userMovie(it.id) }
            val pool = runBlocking { app.container.database.censusDao().candidates(null, null, null).map { it.tmdbId } }
            throw AssertionError("DIAG $what : lignes=$rows ; catalogue ouvert=$pool", error)
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
