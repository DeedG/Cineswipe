package io.github.deedg.cineswipe

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
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.importer.ImportFile
import io.github.deedg.cineswipe.data.importer.ImportState
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.importer.ImportTestTags
import io.github.deedg.cineswipe.ui.settings.SettingsTestTags
import io.github.deedg.cineswipe.ui.settings.TmdbKeyTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Import depuis d'autres applis : Letterboxd par l'écran (avec le détour par le guide de la clé
 * TMDB), IMDb fusionné avec une bibliothèque existante, et restauration d'une sauvegarde CineSwipe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class ImportFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            app.container.settings.setTmdbApiKey(null)
            app.container.settings.setOnboardingDone(true)
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setTmdbApiKey(null) }
    }

    private fun userMovie(id: Long): UserMovieEntity? = runBlocking { app.container.database.userMovieDao().get(id) }

    private fun csv(name: String, text: String) = ImportFile(name, text.toByteArray())

    private val letterboxd = listOf(
        csv(
            "watched.csv",
            "Date,Name,Year,Letterboxd URI\n2024-01-02,Dune,2021,https://boxd.it/1\n2024-01-03,Film Introuvable,1999,https://boxd.it/2\n",
        ),
        csv("ratings.csv", "Date,Name,Year,Letterboxd URI,Rating\n2024-01-02,Dune,2021,https://boxd.it/1,4.5\n"),
        csv("watchlist.csv", "Date,Name,Year,Letterboxd URI\n2024-01-04,Film Reco Deux,2020,https://boxd.it/3\n"),
    )

    @Test
    fun letterboxdImportAsksForTheKeyThenFillsTheLibrary() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.onNodeWithContentDescription("Réglages").performClick()
            compose.waitForTag(SettingsTestTags.IMPORT)
            compose.onNodeWithTag(SettingsTestTags.IMPORT).performScrollTo().performClick()
            compose.waitForTag(ImportTestTags.PICK)
            compose.waitForText("Letterboxd")

            // Le sélecteur de fichiers est remplacé par une lecture directe.
            app.container.importer.load(letterboxd)

            // Sans clé, les films ne peuvent pas être retrouvés : détour par le guide de la clé.
            compose.waitForTag(ImportTestTags.KEY)
            compose.onNodeWithTag(ImportTestTags.KEY).performScrollTo().performClick()
            compose.waitForTag(TmdbKeyTestTags.FIELD)
            compose.onNodeWithTag(TmdbKeyTestTags.FIELD).performScrollTo().performTextInput(FakeTmdbServer.API_KEY)
            compose.onNodeWithTag(TmdbKeyTestTags.SAVE).performScrollTo().performClick()

            // De retour à l'import, la clé est là : on lance.
            compose.waitForTag(ImportTestTags.START)
            compose.onNodeWithTag(ImportTestTags.START).performScrollTo().performClick()
            compose.waitForTag(ImportTestTags.LIBRARY)
            compose.waitForText("1 film introuvable sur TMDB")

            val dune = userMovie(FakeTmdbServer.DUNE.id)!!
            assertEquals(WatchStatus.WATCHED, dune.status)
            assertEquals(WatchSource.IMPORT, dune.watchedSource)
            assertEquals(9.0, dune.importedRating!!, 0.0)
            assertEquals(WatchStatus.WATCHLIST, userMovie(FakeTmdbServer.RECOS[1].id)?.status)

            compose.onNodeWithTag(ImportTestTags.LIBRARY).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Dune", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun imdbRatingsMergeWithAnExistingLibrary() {
        runBlocking {
            TestData.seed(app.container.database)
            app.container.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
        }
        val importer = app.container.importer
        importer.load(
            listOf(
                csv(
                    "ratings.csv",
                    "Const,Your Rating,Date Rated,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors\n" +
                        "tt0780504,9,2023-01-01,Drive,Drive,https://www.imdb.com/title/tt0780504/,Movie,7.8,100,2011,Drama,1,2011-09-16,X\n" +
                        "tt1160419,7,2023-01-02,Dune,Dune,https://www.imdb.com/title/tt1160419/,Movie,8.0,155,2021,Sci-Fi,1,2021-09-15,Y\n",
                ),
            ),
        )
        val ready = awaitState<ImportState.Ready>()
        assertFalse(ready.needsTmdb)
        importer.start()
        val report = awaitState<ImportState.Done>().report

        assertEquals(1, report.counts.watched)
        assertEquals(2, report.counts.rated)
        assertTrue(report.notFound.isEmpty())
        // Drive était déjà vu : il le reste, avec sa date et sa source, et gagne une note reprise.
        val drive = userMovie(FakeTmdbServer.WATCHED.id)!!
        assertEquals(WatchStatus.WATCHED, drive.status)
        assertEquals(9.0, drive.importedRating!!, 0.0)
        val dune = userMovie(FakeTmdbServer.DUNE.id)!!
        assertEquals(WatchStatus.WATCHED, dune.status)
        assertEquals(7.0, dune.importedRating!!, 0.0)
        // Le classement tient compte des notes reprises : Drive (9) passe devant Dune (7).
        assertTrue(drive.rating > dune.rating)
    }

    @Test
    fun cineSwipeBackupRestoresEverythingWithoutAKey() {
        val backup = """{"app":"CineSwipe","version":"0.1.24","exportedAt":1,"schema":5,""" +
            """"films":[""" +
            """{"tmdbId":64690,"title":"Drive","year":2011,"status":"WATCHED","rating":1550.0,"deviation":300.0,"confidence":0.2,"duels":1,""" +
            """"priorRating":1500.0,"priorDuels":0,"watchedAt":1000,"watchedSource":"MANUAL","availability":"NONE","shownCount":0,"updatedAt":5},""" +
            """{"tmdbId":329865,"title":"Premier Contact","year":2016,"status":"WATCHED","rating":1450.0,"deviation":300.0,"confidence":0.2,"duels":1,""" +
            """"priorRating":1500.0,"priorDuels":0,"impression":"LOVED","availability":"NONE","shownCount":0,"updatedAt":5},""" +
            """{"tmdbId":1002,"status":"WATCHLIST","rating":1500.0,"deviation":350.0,"confidence":0.0,"duels":0,"priorRating":1500.0,""" +
            """"priorDuels":0,"availability":"NONE","shownCount":0,"updatedAt":5}],""" +
            """"duels":[{"id":7,"winnerId":64690,"loserId":329865,"draw":false,"createdAt":10,"winnerRatingBefore":1500.0,""" +
            """"loserRatingBefore":1500.0,"winnerRatingAfter":1550.0,"loserRatingAfter":1450.0}],""" +
            """"recoSources":[],""" +
            """"catalog":[{"tmdbId":64690,"title":"Drive","year":2011,"posterPath":"/drive.jpg"},""" +
            """{"tmdbId":329865,"title":"Premier Contact","year":2016,"posterPath":"/arrival.jpg"}]}"""
        val importer = app.container.importer
        importer.load(listOf(ImportFile("cineswipe-20261008.json", backup.toByteArray())))
        val ready = awaitState<ImportState.Ready>()
        assertTrue(ready.restore)
        assertFalse(ready.needsTmdb)
        importer.start()
        val report = awaitState<ImportState.Done>().report

        assertTrue(report.restored)
        assertEquals(2, report.restoredFilms)
        assertEquals(1, report.restoredDuels)
        val database = app.container.database
        assertEquals(1, runBlocking { database.diagnosticsDao().duelCount() })
        assertEquals(2, runBlocking { database.userMovieDao().watchedCount() })
        assertEquals(WatchStatus.WATCHLIST, userMovie(1002)?.status)
        assertEquals("/drive.jpg", runBlocking { database.movieDao().get(64690) }?.posterPath)
        assertEquals(1000L, userMovie(64690)?.watchedAt)
    }

    private inline fun <reified T : ImportState> awaitState(): T = runBlocking {
        withTimeout(TIMEOUT_MS) {
            app.container.importer.state.first { it is T || it is ImportState.Failed }
        }
    }.let { state ->
        assertTrue("état inattendu : $state", state is T)
        state as T
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
