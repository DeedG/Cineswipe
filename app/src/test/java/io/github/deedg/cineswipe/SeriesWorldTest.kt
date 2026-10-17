package io.github.deedg.cineswipe

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import io.github.deedg.cineswipe.data.backup.BackupEntry
import io.github.deedg.cineswipe.data.backup.BackupRepository
import io.github.deedg.cineswipe.data.backup.BackupResult
import io.github.deedg.cineswipe.data.backup.BackupScheduler
import io.github.deedg.cineswipe.data.backup.BackupStorage
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.importer.ImportFailure
import io.github.deedg.cineswipe.data.importer.ImportFile
import io.github.deedg.cineswipe.data.importer.ImportFormats
import io.github.deedg.cineswipe.data.importer.ImportState
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.data.repository.ExportFile
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.reco.RecoNudge
import io.github.deedg.cineswipe.domain.reco.RecoNudges
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.components.WorldSwitchTestTags
import io.github.deedg.cineswipe.ui.detail.SeriesTestTags
import io.github.deedg.cineswipe.ui.feed.FeedTestTags
import io.github.deedg.cineswipe.ui.recos.RecosTestTags
import io.github.deedg.cineswipe.ui.theme.Accent
import io.github.deedg.cineswipe.ui.theme.CineAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Mode séries : un monde à part (base, TMDB en séries, couleur), et le suivi saison par saison. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class SeriesWorldTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            app.mainContainer.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            app.mainContainer.settings.setOnboardingDone(true)
            app.seriesWorld().settings.setOnboardingDone(true)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.switchWorld(MediaKind.FILMS)
            app.mainContainer.settings.setTmdbApiKey(null)
        }
    }

    @Test
    fun seriesLiveInTheirOwnWorld() = runBlocking(Dispatchers.IO) {
        val films = app.mainContainer
        app.switchWorld(MediaKind.SERIES)
        val series = app.container
        assertEquals(MediaKind.SERIES, series.kind)
        assertNotSame(films, series)
        assertEquals(MediaKind.SERIES, films.settings.mediaKind.first())
        assertEquals(Accent.Series, CineAccent.current)

        // Même identifiant TMDB, deux œuvres : Dune chez les films, Les Gardiens chez les séries.
        val show = series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
        assertEquals("Les Gardiens", show.title)
        assertEquals(3, show.seasonCount)
        assertEquals(listOf(1, 2, 3), show.seasons.map { it.number })
        assertEquals(52, show.runtimeMinutes)
        assertEquals("Créatrice Test", show.director)
        assertEquals("Ended", show.airStatus)
        assertEquals("Dune", films.tmdb.ensureDetails(FakeTmdbServer.DUNE.id).title)

        // Une série vue n'apparaît pas chez les films, et inversement.
        series.library.setProgress(show.tmdbId, SeriesProgress.IN_PROGRESS, 2)
        val user = checkNotNull(series.database.userMovieDao().get(show.tmdbId))
        assertEquals(WatchStatus.WATCHED, user.status)
        assertEquals(SeriesProgress.IN_PROGRESS, user.progress)
        assertEquals(2, user.currentSeason)
        assertNull(films.database.userMovieDao().get(FakeTmdbServer.DUNE.id))
        // Finie : la saison n'a plus de sens.
        series.library.setProgress(show.tmdbId, SeriesProgress.FINISHED, 3)
        assertNull(series.database.userMovieDao().get(show.tmdbId)?.currentSeason)

        // Les séries d'une créatrice (la découverte des séries ne filtre pas par personne).
        val byCreator = series.tmdb.discover(io.github.deedg.cineswipe.data.repository.DiscoverQuery(crewId = FakeTmdbServer.CREATOR_ID), 1)
        assertEquals(setOf(3001L, 3002L, 1004L), byCreator.results.map { it.id }.toSet())

        // Recensement : l'avancement est noté d'un toucher, sans saison.
        series.database.censusDao().answer(
            mapOf(3001L to CensusChoice.IN_PROGRESS, 3002L to CensusChoice.EPISODES),
            now = 10,
        )
        assertEquals(SeriesProgress.IN_PROGRESS, series.database.userMovieDao().get(3001)?.progress)
        assertNull(series.database.userMovieDao().get(3001)?.currentSeason)
        assertEquals(SeriesProgress.EPISODES, series.database.userMovieDao().get(3002)?.progress)
        assertEquals(WatchStatus.WATCHED, series.database.userMovieDao().get(3002)?.status)

        // Aucun service configuré dans ce test : pas de homelab.
        assertEquals(false, series.settings.homelabConnected.first())

        // Retour aux films : rouge, et la même base qu'avant.
        app.switchWorld(MediaKind.FILMS)
        assertEquals(Accent.Films, CineAccent.current)
        assertEquals(films, app.container)
    }

    @Test
    fun theAppReopensInSeriesAndTracksTheSeason() {
        runBlocking { app.switchWorld(MediaKind.SERIES) }
        ActivityScenario.launch(MainActivity::class.java).use {
            // Recos de séries, la pastille sur « Séries ».
            compose.waitForTag(FeedTestTags.card(FakeTmdbServer.SHOWS.first().id))
            compose.onNodeWithTag(WorldSwitchTestTags.segment(MediaKind.SERIES))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

            // Aucune série vue ni recensée : les recos invitent au recensement. Refusé, plus jamais.
            compose.waitForTag(RecosTestTags.nudge(RecoNudge.CENSUS))
            compose.onNodeWithTag(RecosTestTags.NUDGE_DISMISS).performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(RecosTestTags.nudge(RecoNudge.CENSUS)).fetchSemanticsNodes().isEmpty() }
            assertEquals(
                RecoNudges.NEVER,
                runBlocking(Dispatchers.IO) { app.container.settings.recoNudgesHidden.first()[RecoNudge.CENSUS] },
            )

            // Recherche d'une série, puis sa fiche : saisons, durée d'un épisode, créatrice.
            compose.onNodeWithText("Recherche").performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("gardiens")
            compose.onNodeWithContentDescription("Lancer la recherche").performClick()
            compose.waitForTag(FeedTestTags.card(FakeTmdbServer.GUARDIANS.id))
            compose.waitForIdle()
            compose.onNodeWithTag(FeedTestTags.card(FakeTmdbServer.GUARDIANS.id)).performClick()
            compose.waitForTag(SeriesTestTags.INFO)
            compose.waitForText("3 saisons", substring = true)
            compose.waitForText("Créée par", substring = true)

            // Marquée vue, puis en cours, saison 2.
            compose.onNodeWithText("Marquer comme vue").performScrollTo().performClick()
            // Le toast « Marquée comme vue » (en haut) ne doit pas recouvrir les boutons touchés ensuite.
            compose.waitForText("Marquée comme vue")
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Marquée comme vue").fetchSemanticsNodes().isEmpty() }
            compose.waitForTag(SeriesTestTags.progress(SeriesProgress.IN_PROGRESS))
            compose.onNodeWithTag(SeriesTestTags.progress(SeriesProgress.IN_PROGRESS)).performScrollTo().performClick()
            compose.waitForTag(SeriesTestTags.season(2))
            // La fiche se recompose à chaque écriture (étoiles, badge) : on retouche tant que la saison n'est pas prise.
            val seasonSaved = (1..3).any {
                compose.waitForIdle()
                compose.onNodeWithTag(SeriesTestTags.season(2)).performScrollTo().performClick()
                runCatching {
                    compose.waitUntil(5_000) {
                        runBlocking(Dispatchers.IO) { app.container.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id) }?.currentSeason == 2
                    }
                }.isSuccess
            }
            assertTrue("Saison non enregistrée", seasonSaved)
            // Le badge est en capitales.
            compose.waitForText("EN COURS · S2")
            assertEquals(
                SeriesProgress.IN_PROGRESS,
                runBlocking(Dispatchers.IO) { app.container.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id) }?.progress,
            )
            // Rien chez les films.
            assertNull(runBlocking(Dispatchers.IO) { app.mainContainer.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id) })
        }
    }

    @Test
    fun aSeriesBackupIsRestoredInTheSeriesWorldOnly() = runBlocking(Dispatchers.IO) {
        app.switchWorld(MediaKind.SERIES)
        val series = app.container
        series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
        series.library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.ABANDONED, 2)
        val backup = series.diagnostics.exportData()
        assertEquals("SERIES", backup.kind)
        val file = ImportFile("cineswipe-series.json", Json { encodeDefaults = true }.encodeToString(ExportFile.serializer(), backup).toByteArray())

        // Bibliothèque des séries remise à zéro, puis restaurée en entier.
        series.library.eraseAll()
        assertNull(series.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id)?.progress)
        series.importer.load(listOf(file))
        val ready = withTimeout(TIMEOUT_MS) { series.importer.state.first { it is ImportState.Ready } } as ImportState.Ready
        assertTrue(ready.restore)
        series.importer.start()
        val done = withTimeout(TIMEOUT_MS) { series.importer.state.first { it is ImportState.Done || it is ImportState.Failed } }
        assertTrue(done.toString(), done is ImportState.Done)
        val restored = checkNotNull(series.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id))
        assertEquals(SeriesProgress.ABANDONED, restored.progress)
        assertEquals(2, restored.currentSeason)

        // Chez les films, la même sauvegarde est refusée (c'est celle des séries).
        val films = app.mainContainer
        films.importer.load(listOf(file))
        val refused = withTimeout(TIMEOUT_MS) { films.importer.state.first { it is ImportState.Failed || it is ImportState.Ready } }
        assertEquals(ImportState.Failed(ImportFailure.WRONG_WORLD), refused)
        films.importer.reset()
        // Et un export Letterboxd n'est pas lu chez les séries.
        series.importer.reset()
        series.importer.load(listOf(ImportFile("watched.csv", "Date,Name,Year,Letterboxd URI\n2024-01-02,Dune,2021,https://boxd.it/1\n".toByteArray())))
        val notSeries = withTimeout(TIMEOUT_MS) { series.importer.state.first { it is ImportState.Failed || it is ImportState.Ready } }
        assertEquals(ImportState.Failed(ImportFailure.SERIES_BACKUP_ONLY), notSeries)
        series.importer.reset()
    }

    @Test
    fun tonightStartsWithTheSeriesInProgress() = runBlocking(Dispatchers.IO) {
        app.switchWorld(MediaKind.SERIES)
        val series = app.container
        series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
        series.library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.IN_PROGRESS, 2)
        val first = series.recos.tonight(series = true).first()
        assertEquals(FakeTmdbServer.GUARDIANS.id, first.movie.tmdbId)
        assertTrue(first.resume)
        assertEquals(2, first.season)
        // Saison 2 finie : la 3 ; la dernière saison finie : la série est finie.
        assertEquals(3, io.github.deedg.cineswipe.ui.tonight.nextSeason(first))
        assertNull(io.github.deedg.cineswipe.ui.tonight.nextSeason(first.copy(season = 3)))
        // Chez les films, pas de reprise.
        assertTrue(app.mainContainer.recos.tonight().none { it.resume })
    }

    @Test
    fun aNewSeasonOfAFollowedShowIsNotifiedOnce() = runBlocking(Dispatchers.IO) {
        val series = app.seriesWorld()
        series.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
        FakeTmdbServer.airedSeasons[FakeTmdbServer.GUARDIANS.id] = 3
        try {
            series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id, force = true)
            series.library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.FINISHED, null)
            val notices = (app as TestCineSwipeApp).seasonNotices
            notices.clear()
            // Premier passage : la saison diffusée devient la référence, rien n'est annoncé.
            assertTrue(series.seasonWatch.check().isEmpty())
            // La saison 4 sort : une notification, avec « Remettre en cours ».
            FakeTmdbServer.airedSeasons[FakeTmdbServer.GUARDIANS.id] = 4
            val alert = series.seasonWatch.check().single()
            assertEquals(4, alert.season)
            assertEquals(io.github.deedg.cineswipe.domain.SeasonAlert.Kind.OUT, alert.kind)
            assertTrue(notices.single().finished)
            assertEquals(4, series.database.movieDao().get(FakeTmdbServer.GUARDIANS.id)?.seasonCount)
            // Une seule fois.
            assertTrue(series.seasonWatch.check().isEmpty())
            // Réglage coupé : plus rien.
            series.settings.setNewSeasonNotifications(false)
            FakeTmdbServer.airedSeasons[FakeTmdbServer.GUARDIANS.id] = 5
            assertTrue(series.seasonWatch.check().isEmpty())
        } finally {
            FakeTmdbServer.airedSeasons.clear()
            series.settings.setNewSeasonNotifications(true)
        }
    }

    @Test
    fun seasonsFightInTheirOwnArena() = runBlocking(Dispatchers.IO) {
        val series = app.seriesWorld()
        // Les Gardiens finie (3 saisons), Série Reco Un en cours en saison 2 (saison 1 vue).
        series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
        series.tmdb.ensureDetails(3001)
        series.library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.FINISHED, null)
        series.library.setProgress(3001, SeriesProgress.IN_PROGRESS, 2)
        val arena = series.seasonArena
        val items = arena.items()
        assertEquals(setOf(1004001L, 1004002L, 1004003L, 3001001L), items.map { it.key }.toSet())
        val match = checkNotNull(arena.pickMatch())
        val duel = arena.vote(match.a.key, match.b.key)
        val after = arena.items().associateBy { it.key }
        assertTrue(after.getValue(match.a.key).rating > after.getValue(match.b.key).rating)
        assertEquals(1, after.getValue(match.a.key).duels)
        // Annulé : tout revient au départ.
        arena.undo(duel)
        assertEquals(0, arena.items().first { it.key == match.a.key }.duels)
        // Le classement des séries n'a pas bougé.
        assertEquals(0, series.database.userMovieDao().get(FakeTmdbServer.GUARDIANS.id)?.duels)
    }

    @Test
    fun theWeeklyBackupHoldsBothWorlds() = runBlocking(Dispatchers.IO) {
        val films = app.mainContainer
        films.tmdb.ensureDetails(FakeTmdbServer.DUNE.id)
        films.library.markWatched(FakeTmdbServer.DUNE.id)
        val series = app.seriesWorld()
        series.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
        series.library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.IN_PROGRESS, 3)
        val written = HashMap<String, ByteArray>()
        val storage = object : BackupStorage {
            override fun keep(folder: String) = Unit
            override fun release(folder: String) = Unit
            override fun displayName(folder: String): String? = null
            override fun write(folder: String, name: String, mimeType: String, bytes: ByteArray) {
                written[name] = bytes
            }
            override fun list(folder: String): List<BackupEntry> = written.keys.map { BackupEntry(it, it) }
            override fun delete(folder: String, entry: BackupEntry) {
                written.remove(entry.name)
            }
        }
        val backup = BackupRepository(films.diagnostics, films.settings, storage, BackupScheduler.None, otherWorlds = { listOf(series.diagnostics) })
        assertEquals(BackupResult.DONE, backup.setFolder("content://test/tree/sauvegardes"))
        val archive = ImportFile(written.keys.single(), written.values.single())
        // La même archive : les films en mode films, les séries en mode séries.
        assertEquals("FILMS", ImportFormats.parse(listOf(archive)).backup?.kind)
        val seriesBackup = checkNotNull(ImportFormats.parse(listOf(archive), series = true).backup)
        assertEquals("SERIES", seriesBackup.kind)
        assertEquals("IN_PROGRESS", seriesBackup.films.single { it.tmdbId == FakeTmdbServer.GUARDIANS.id }.progress)
        // Et le monde des séries passe par la sauvegarde des films (un seul dossier, un seul réglage).
        assertTrue(series.backup === films.backup)
    }

    @Test
    fun theSeriesDemoHasItsOwnWelcomeAndProfile() = runBlocking(Dispatchers.IO) {
        // L'accueil des séries est à part de celui des films.
        app.seriesWorld().settings.setOnboardingDone(false)
        assertEquals(false, app.seriesWorld().settings.onboardingDone.first())
        assertEquals(true, app.mainContainer.settings.onboardingDone.first())

        app.switchWorld(MediaKind.SERIES)
        app.startDemo(MediaKind.SERIES)
        val demo = app.container
        try {
            assertTrue(demo.isDemo)
            assertEquals(MediaKind.SERIES, demo.kind)
            assertEquals(Accent.Series, CineAccent.current)
            // Des séries vues, avec leur avancement, et des fiches de séries.
            val dao = demo.database.userMovieDao()
            assertTrue(dao.watchedCount() >= 10)
            assertEquals(SeriesProgress.IN_PROGRESS, dao.get(76479)?.progress)
            assertEquals(3, dao.get(76479)?.currentSeason)
            assertEquals(SeriesProgress.EPISODES, dao.get(456)?.progress)
            assertEquals(WatchStatus.WATCHLIST, dao.get(60059)?.status)
            val got = demo.tmdb.ensureDetails(1399)
            assertEquals("Game of Thrones", got.title)
            assertEquals(8, got.seasonCount)
            // Seerr fictif : les demandes de séries passent saison par saison.
            assertEquals(io.github.deedg.cineswipe.data.homelab.HomelabService.SEERR, demo.settings.homelabConfig.first().requestService)
        } finally {
            app.stopDemo()
        }
        // Retour aux vraies séries.
        assertEquals(MediaKind.SERIES, app.container.kind)
        assertTrue(!app.container.isDemo)
    }

    private fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
