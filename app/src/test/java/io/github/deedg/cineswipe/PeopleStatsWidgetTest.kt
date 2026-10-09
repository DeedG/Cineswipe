package io.github.deedg.cineswipe

import android.content.Intent
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
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.components.PersonTestTags
import io.github.deedg.cineswipe.ui.person.PersonScreenTags
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import io.github.deedg.cineswipe.ui.search.SearchTestTags
import io.github.deedg.cineswipe.widget.WidgetData
import io.github.deedg.cineswipe.widget.WidgetLinks
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

/** Fiches réalisateur et acteur, stats de goûts, données et liens des widgets. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class PeopleStatsWidgetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            app.container.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            app.container.settings.setOnboardingDone(true)
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setTmdbApiKey(null) }
    }

    private fun open(target: String): Intent = Intent(app, MainActivity::class.java).putExtra(WidgetLinks.EXTRA_OPEN, target)

    @Test
    fun directorAndActorOpenTheirFilms() {
        ActivityScenario.launch<MainActivity>(open(WidgetLinks.movie(FakeTmdbServer.DUNE.id))).use {
            compose.waitForText("Marquer comme vu")
            compose.waitForTag(PersonTestTags.DIRECTOR)
            // Le nom souligné de la réalisatrice ouvre sa fiche.
            compose.onNodeWithTag(PersonTestTags.DIRECTOR).performScrollTo().performClick()
            compose.waitForTag(PersonScreenTags.HEADER)
            compose.waitForText("Tu as vu 0 de ses 2 films")
            // Elle joue et réalise : chaque film dit ce qu'elle y a fait.
            // (Les icônes sont dans la ligne cliquable : on les cherche dans l'arbre non fusionné.)
            compose.waitForTag(PersonScreenTags.role(FakeTmdbServer.RECOS.first().id, directed = true), unmerged = true)
            compose.waitForTag(PersonScreenTags.role(FakeTmdbServer.RECOS.first().id, directed = false), unmerged = true)
            compose.waitForTag(PersonScreenTags.role(FakeTmdbServer.DUNE.id, directed = true), unmerged = true)
            assertTrue(
                compose.onAllNodesWithTag(PersonScreenTags.role(FakeTmdbServer.DUNE.id, directed = false), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithContentDescription("Retour").performClick()

            // La photo de l'actrice ouvre ses films ; elle ne fait que jouer : pas d'icônes de rôle.
            compose.waitForTag(PersonTestTags.actor(FakeTmdbServer.ACTRESS_ID))
            compose.onNodeWithTag(PersonTestTags.actor(FakeTmdbServer.ACTRESS_ID)).performScrollTo().performClick()
            compose.waitForText("Actrice Principale")
            compose.waitForTag(PersonScreenTags.film(FakeTmdbServer.RECOS[1].id))
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodesWithText("Héroïne", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                compose.onAllNodesWithTag(PersonScreenTags.role(FakeTmdbServer.DUNE.id, directed = false), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun searchFindsPeopleByName() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Recherche").performClick()
            compose.waitForText("Rechercher")
            compose.onNode(hasSetTextAction()).performTextInput("réalisatrice")
            // Pendant la saisie, la personne s'affiche avec ses films connus ; un toucher ouvre ses films.
            compose.waitForTag(SearchTestTags.person(FakeTmdbServer.DIRECTOR_ID))
            assertTrue(compose.onAllNodesWithTag(SearchTestTags.person(FakeTmdbServer.ACTRESS_ID)).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(SearchTestTags.person(FakeTmdbServer.DIRECTOR_ID)).performClick()
            compose.waitForTag(PersonScreenTags.HEADER)
            compose.waitForText("Tu as vu 0 de ses 2 films")
        }
    }

    @Test
    fun statsScreenAndWidgetData() {
        runBlocking {
            TestData.seed(app.container.database)
            val library = app.container.database.libraryDao()
            // Assez de films vus pour les stats (dix au moins).
            (1..12).forEach { i ->
                val id = 7000L + i
                library.upsertMovie(
                    MovieEntity(
                        tmdbId = id,
                        title = "Film Stats $i",
                        year = 1980 + i,
                        posterPath = "/$id.jpg",
                        runtimeMinutes = 100,
                        genres = listOf(if (i % 2 == 0) "Drame" else "Comédie"),
                        tmdbRating = 7.0,
                        voteCount = 500,
                    ),
                )
                library.upsertUserMovie(UserMovieEntity(tmdbId = id, status = WatchStatus.WATCHED, rating = 1400.0 + i * 20, updatedAt = 1))
            }
        }
        val stats = runBlocking { app.container.stats.load() }
        assertEquals(15, stats.watched)
        assertTrue(stats.genres.any { it.name == "Drame" })

        // Données des widgets.
        val top = runBlocking { WidgetData.top(app, 3, null) }
        assertEquals(3, top.size)
        assertTrue(top.zipWithNext().all { (a, b) -> a.stars >= b.stars })
        val drama = runBlocking { WidgetData.top(app, 5, "Drame") }
        assertTrue(drama.isNotEmpty() && drama.all { it.title.startsWith("Film Stats") })
        val values = runBlocking { WidgetData.stats(app) }
        assertNotNull(values)
        assertEquals(15, values!!.watched)
        assertTrue(values.reliability in 0..100)

        // Le widget « Mes stats » ouvre l'écran des stats.
        ActivityScenario.launch<MainActivity>(open(WidgetLinks.STATS)).use {
            compose.waitForText("Mes goûts")
            compose.waitForText("films vus")
            compose.waitForText("Genres")
            compose.onNodeWithContentDescription("Retour").performClick()
            compose.waitForTag(ProfileTestTags.STATS)
            compose.onNodeWithText("Mes goûts en chiffres").performScrollTo()
        }
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String, unmerged: Boolean = false) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
