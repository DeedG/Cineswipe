package io.github.deedg.cineswipe

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.backup.BackupEntry
import io.github.deedg.cineswipe.data.backup.BackupRepository
import io.github.deedg.cineswipe.data.backup.BackupResult
import io.github.deedg.cineswipe.data.backup.BackupScheduler
import io.github.deedg.cineswipe.data.backup.BackupStorage
import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.importer.ImportFile
import io.github.deedg.cineswipe.data.importer.ImportFormats
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.components.WatchTestTags
import io.github.deedg.cineswipe.ui.recos.RecosTestTags
import io.github.deedg.cineswipe.ui.settings.BackupTestTags
import io.github.deedg.cineswipe.ui.share.SharedTestTags
import io.github.deedg.cineswipe.ui.tonight.TonightTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** « Ce soir », partage vers CineSwipe et sauvegarde automatique. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class TonightShareBackupTest {

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
        runBlocking {
            app.container.settings.setTmdbApiKey(null)
            app.container.settings.setBackupFolder(null)
        }
    }

    private fun userMovie(id: Long): UserMovieEntity? = runBlocking { app.container.database.userMovieDao().get(id) }

    private fun movie(id: Long, title: String, runtime: Int) = MovieEntity(
        tmdbId = id,
        title = title,
        year = 2020,
        posterPath = "/$id.jpg",
        runtimeMinutes = runtime,
        genreIds = listOf(18),
        genres = listOf("Drame"),
        tmdbRating = 7.5,
        voteCount = 1_000,
    )

    @Test
    fun tonightListsWhatIsAtHandAndFilters() {
        runBlocking {
            TestData.seed(app.container.database)
            val library = app.container.database.libraryDao()
            library.upsertMovie(movie(9001, "Le Long Film", 170))
            library.upsertUserMovie(UserMovieEntity(tmdbId = 9001, status = WatchStatus.WATCHLIST, updatedAt = 1))
            library.upsertMovie(movie(9002, "Le Court Film", 90))
            library.upsertUserMovie(UserMovieEntity(tmdbId = 9002, status = WatchStatus.WATCHLIST, updatedAt = 1))
            // Sans homelab, la disponibilité sur le serveur ne compte pas.
            library.upsertMovie(movie(9003, "Sur Le Serveur", 95))
            library.upsertUserMovie(
                UserMovieEntity(tmdbId = 9003, availability = Availability.AVAILABLE, updatedAt = 1),
            )
        }
        assertEquals(setOf(9001L, 9002L), runBlocking { app.container.recos.tonight() }.map { it.movie.tmdbId }.toSet())

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag(RecosTestTags.TONIGHT)
            compose.onNodeWithTag(RecosTestTags.TONIGHT).performClick()
            compose.waitForTag(TonightTestTags.HERO)
            compose.waitForText("Le Long Film")
            compose.waitForText("Le Court Film")

            // Moins de 1 h 40 : le film de 2 h 50 disparaît.
            compose.onNodeWithText("≤ 1 h 40").performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Le Long Film").fetchSemanticsNodes().isEmpty() }
            compose.waitForText("Le Court Film")

            compose.onNodeWithTag(TonightTestTags.seen(9002)).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { userMovie(9002)?.status == WatchStatus.WATCHED }
            compose.waitForText("Aucun film avec ces filtres.")
        }
    }

    private fun share(text: String): Intent = Intent(app, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

    @Test
    fun sharedImdbLinkOpensTheMovie() {
        ActivityScenario.launch<MainActivity>(share("Dune - IMDb https://www.imdb.com/title/tt1160419/")).use {
            compose.waitForText("Marquer comme vu")
            compose.waitForText(FakeTmdbServer.DUNE.title)
            // Sans homelab : où regarder le film (abonnement, location).
            compose.waitForTag(WatchTestTags.PROVIDERS)
            compose.onNodeWithTag(WatchTestTags.PROVIDERS).performScrollTo()
            compose.waitForText(FakeTmdbServer.STREAMING_PROVIDER)
            compose.waitForText("LocaTest")
        }
    }

    @Test
    fun sharedTitleOpensTheMovieOrOffersAChoice() {
        // Titre et année : un seul film possible, sa fiche s'ouvre.
        ActivityScenario.launch<MainActivity>(share("Film Reco (2020)")).use {
            compose.waitForText("Marquer comme vu")
            compose.waitForText(FakeTmdbServer.RECOS[1].title)
        }
        // Titre seul, plusieurs films : on choisit.
        ActivityScenario.launch<MainActivity>(share("Film Reco")).use {
            compose.waitForTag(SharedTestTags.choice(FakeTmdbServer.RECOS[0].id))
            compose.onNodeWithTag(SharedTestTags.choice(FakeTmdbServer.RECOS[2].id)).performClick()
            compose.waitForText("Marquer comme vu")
            compose.waitForText(FakeTmdbServer.RECOS[2].title)
        }
    }

    /** Dossier de sauvegarde en mémoire. */
    private class MemoryStorage : BackupStorage {
        val files = LinkedHashMap<String, ByteArray>()
        var denied = false

        override fun keep(folder: String) = Unit

        override fun release(folder: String) = Unit

        override fun displayName(folder: String): String = "Sauvegardes"

        override fun write(folder: String, name: String, mimeType: String, bytes: ByteArray) {
            if (denied) throw SecurityException("permission perdue")
            files[name] = bytes
        }

        override fun list(folder: String): List<BackupEntry> = files.keys.map { BackupEntry(it, it) }

        override fun delete(folder: String, entry: BackupEntry) {
            files.remove(entry.id)
        }
    }

    @Test
    fun backupsAreWrittenRotatedAndRestorable() {
        runBlocking { TestData.seed(app.container.database) }
        val storage = MemoryStorage()
        var now = 1_760_000_000_000L
        val backup = BackupRepository(app.container.diagnostics, app.container.settings, storage, BackupScheduler.None, clock = { now })

        assertEquals(BackupResult.DONE, runBlocking { backup.setFolder("content://test/tree/sauvegardes") })
        val (name, bytes) = storage.files.entries.single()
        assertTrue(name.startsWith(BackupRepository.PREFIX) && name.endsWith(".zip"))
        // L'archive se relit avec l'import : c'est une sauvegarde CineSwipe complète.
        val batch = ImportFormats.parse(listOf(ImportFile(name, bytes)))
        assertNotNull(batch.backup)
        assertEquals(TestData.WATCHED, batch.watched)

        repeat(10) {
            now += 60 * 60 * 1000L
            assertEquals(BackupResult.DONE, runBlocking { backup.backupNow() })
        }
        assertEquals(BackupRepository.KEEP, storage.files.size)
        assertTrue(name !in storage.files)
        assertNotNull(runBlocking { app.container.settings.backupStatus.first() }.lastAt)

        storage.denied = true
        assertEquals(BackupResult.FAILED, runBlocking { backup.backupNow() })
        assertEquals(BackupRepository.ERROR_ACCESS, runBlocking { app.container.settings.backupStatus.first() }.error)

        assertEquals(BackupResult.DISABLED, runBlocking { backup.setFolder(null) })
        assertNull(runBlocking { app.container.settings.backupFolder.first() })
        assertEquals(BackupResult.DISABLED, runBlocking { backup.backupNow() })
    }

    @Test
    fun settingsOfferTheAutomaticBackup() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.onNodeWithContentDescription("Réglages").performClick()
            compose.waitForTag(BackupTestTags.CHOOSE)
            compose.onNodeWithTag(BackupTestTags.CHOOSE).performScrollTo()
            compose.waitForText("Sauvegarde automatique")
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
