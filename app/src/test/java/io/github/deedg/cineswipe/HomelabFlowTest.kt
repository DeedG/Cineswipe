package io.github.deedg.cineswipe

import org.robolectric.Shadows.shadowOf
import io.github.deedg.cineswipe.ui.recos.RecosTestTags
import io.github.deedg.cineswipe.ui.components.WatchTestTags
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
import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.OutboxEntity
import io.github.deedg.cineswipe.data.db.OutboxState
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.RequestTarget
import io.github.deedg.cineswipe.data.homelab.SeerrAuthMode
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import io.github.deedg.cineswipe.data.repository.CardAction
import io.github.deedg.cineswipe.testing.FakeHomelabServer
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.homelab.HomelabTestTags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.Instant

/**
 * Mode homelab de bout en bout, sur un faux Jellyfin + Radarr + Seerr : synchro des films vus et
 * des disponibilités, demande depuis une carte puis annulation, et écrans de réglages.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class HomelabFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeHomelabServer.reset()
        runBlocking {
            val settings = app.container.settings
            settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            settings.saveService(
                ServiceConfig(
                    service = HomelabService.JELLYFIN,
                    enabled = true,
                    // Saisie sans « http:// », comme le suggère l'écran de réglage.
                    url = FakeHomelabServer.url("jellyfin").removePrefix("http://"),
                    apiKey = FakeHomelabServer.JELLYFIN_KEY,
                    userId = FakeHomelabServer.JELLYFIN_USER,
                    userName = "Alex",
                ),
            )
            settings.saveService(
                ServiceConfig(
                    service = HomelabService.RADARR,
                    enabled = true,
                    url = FakeHomelabServer.url("radarr"),
                    apiKey = FakeHomelabServer.RADARR_KEY,
                    qualityProfileId = 4,
                    rootFolder = "/movies",
                ),
            )
            settings.saveService(
                ServiceConfig(
                    service = HomelabService.SEERR,
                    enabled = true,
                    url = FakeHomelabServer.url("seerr"),
                    apiKey = FakeHomelabServer.SEERR_KEY,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        // Les réglages (DataStore) survivent d'un test à l'autre : on repart de zéro.
        runBlocking {
            val settings = app.container.settings
            HomelabService.entries.forEach { settings.clearService(it) }
            settings.setRequestTarget(RequestTarget.AUTO)
            settings.setHomelabReport(null)
            settings.setTmdbApiKey(null)
        }
    }

    @Test
    fun syncBringsWatchedFilmsAvailabilityAndRequests() {
        val report = checkNotNull(runBlocking { app.container.homelab.sync() })

        assertTrue(report.failures.isEmpty())
        assertEquals(2, report.watched)
        assertEquals(2, report.newlyWatched)
        assertEquals(1, report.unmatched)
        assertEquals(3, report.available)
        assertEquals(1, report.requested)

        val watched = userMovie(FakeHomelabServer.WATCHED_ID)
        assertEquals(WatchStatus.WATCHED, watched?.status)
        assertEquals(WatchSource.JELLYFIN, watched?.watchedSource)
        assertEquals(Instant.parse(FakeHomelabServer.WATCHED_AT).toEpochMilli(), watched?.watchedAt)
        assertEquals(Availability.AVAILABLE, watched?.availability)
        assertEquals(Availability.AVAILABLE, userMovie(FakeHomelabServer.AVAILABLE_ID)?.availability)
        assertEquals(WatchStatus.NONE, userMovie(FakeHomelabServer.AVAILABLE_ID)?.status)
        assertEquals(Availability.REQUESTED, userMovie(FakeHomelabServer.RADARR_WANTED_ID)?.availability)

        // Les fiches des films venus du serveur sont complétées depuis TMDB.
        val movie = runBlocking { app.container.database.movieDao().get(FakeHomelabServer.WATCHED_ID) }
        assertNotNull(movie?.fetchedAt)
        assertEquals("Réalisatrice Test", movie?.director)

        assertTrue(
            FakeHomelabServer.requests.any {
                it.url.encodedPath == "/jellyfin/Items" && it.url.queryParameter("userId") == FakeHomelabServer.JELLYFIN_USER
            },
        )
    }

    @Test
    fun filmsOnTheServerOpenInJellyfin() {
        runBlocking { app.container.homelab.sync() }
        val homelab = app.container.homelab
        val having = runBlocking { homelab.mediaServersHaving(listOf(FakeHomelabServer.AVAILABLE_ID, 999L)) }
        // Seul Jellyfin est un serveur média ici (Radarr et Seerr ne servent qu'aux demandes).
        assertEquals(setOf(HomelabService.JELLYFIN), having.keys)
        assertEquals(setOf(FakeHomelabServer.AVAILABLE_ID), having[HomelabService.JELLYFIN])
        val link = checkNotNull(runBlocking { homelab.watchLink(HomelabService.JELLYFIN, FakeHomelabServer.AVAILABLE_ID) })
        assertTrue(link.webUrl.startsWith("http://") && link.webUrl.endsWith("/web/#/details?id=jf-available"))
        // L'appli Jellyfin n'accepte aucun lien : la fiche s'ouvre dans le navigateur.
        assertNull(link.appPackage)
        assertNull(runBlocking { homelab.watchLink(HomelabService.PLEX, FakeHomelabServer.AVAILABLE_ID) })
        assertNull(runBlocking { homelab.watchLink(HomelabService.JELLYFIN, 999L) })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForTag(RecosTestTags.TONIGHT)
            compose.onNodeWithTag(RecosTestTags.TONIGHT).performClick()
            // « Ce soir » : un bouton Jellyfin, pas de bouton Plex (non configuré).
            compose.waitForTag(WatchTestTags.button(HomelabService.JELLYFIN))
            assertTrue(compose.onAllNodesWithTag(WatchTestTags.button(HomelabService.PLEX)).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(WatchTestTags.button(HomelabService.JELLYFIN)).performScrollTo().performClick()
            var opened: Intent? = null
            compose.waitUntil(TIMEOUT_MS) {
                scenario.onActivity { activity -> opened = opened ?: shadowOf(activity).nextStartedActivity }
                opened != null
            }
            assertEquals(Intent.ACTION_VIEW, opened!!.action)
            assertTrue(opened!!.dataString.orEmpty().endsWith("/web/#/details?id=jf-available"))
            assertNull(opened!!.`package`)
        }
    }

    @Test
    fun requestFromACardGoesToSeerrAndUndoRemovesIt() {
        val feed = app.container.feed
        val dune = runBlocking { app.container.tmdb.ensureDetails(FakeTmdbServer.DUNE.id) }

        val record = runBlocking { feed.apply(dune, CardAction.REQUEST) }

        assertEquals(CardAction.REQUEST, record.action)
        val ticket = checkNotNull(record.request)
        assertEquals(HomelabService.SEERR, ticket.service)
        assertEquals(WatchStatus.WATCHLIST, userMovie(dune.tmdbId)?.status)
        assertEquals(Availability.REQUESTED, userMovie(dune.tmdbId)?.availability)

        // La demande part (sans délai dans les tests) et garde l'identifiant Seerr pour pouvoir l'annuler.
        waitFor { outbox(ticket.outboxId)?.state == OutboxState.SENT }
        assertEquals(FakeHomelabServer.SEERR_REQUEST_ID, outbox(ticket.outboxId)?.remoteId)
        val post = FakeHomelabServer.requests.single { it.method == "POST" && it.url.encodedPath == "/seerr/api/v1/request" }
        assertEquals(dune.tmdbId.toInt(), body(post.body?.utf8()).getValue("mediaId").jsonPrimitive.int)

        // Annuler : la demande est supprimée de Seerr et le film redevient comme avant.
        runBlocking { feed.undo(record) }
        waitFor {
            FakeHomelabServer.requests.any {
                it.method == "DELETE" && it.url.encodedPath == "/seerr/api/v1/request/${FakeHomelabServer.SEERR_REQUEST_ID}"
            }
        }
        waitFor { runBlocking { app.container.database.outboxDao().pending() }.isEmpty() }
        assertNull(userMovie(dune.tmdbId))
    }

    @Test
    fun radarrReceivesRequestsWhenChosen() {
        runBlocking { app.container.settings.setRequestTarget(RequestTarget.RADARR) }
        val film = runBlocking { app.container.tmdb.ensureDetails(FakeTmdbServer.RECOS[1].id) }

        val record = runBlocking { app.container.feed.apply(film, CardAction.REQUEST) }

        val ticket = checkNotNull(record.request)
        assertEquals(HomelabService.RADARR, ticket.service)
        waitFor { outbox(ticket.outboxId)?.state == OutboxState.SENT }
        val post = FakeHomelabServer.requests.single { it.method == "POST" && it.url.encodedPath == "/radarr/api/v3/movie" }
        val body = body(post.body?.utf8())
        assertEquals(film.tmdbId.toInt(), body.getValue("tmdbId").jsonPrimitive.int)
        assertEquals(4, body.getValue("qualityProfileId").jsonPrimitive.int)
        assertEquals("/movies", body.getValue("rootFolderPath").jsonPrimitive.content)
    }

    @Test
    fun aSeerrUserRequestsUnderTheirOwnName() {
        val homelab = app.container.homelab
        val session = runBlocking {
            homelab.seerrLogin(
                FakeHomelabServer.url("seerr"),
                headers = "",
                mode = SeerrAuthMode.LOCAL,
                identifier = FakeHomelabServer.SEERR_USER_EMAIL,
                secret = FakeHomelabServer.SEERR_USER_PASSWORD,
            )
        }
        assertEquals(FakeHomelabServer.SEERR_USER_ID, session.userId)
        val seerr = ServiceConfig(
            service = HomelabService.SEERR,
            enabled = true,
            url = FakeHomelabServer.url("seerr"),
            apiKey = session.cookies,
            seerrAuthMode = SeerrAuthMode.LOCAL,
            userName = session.displayName,
            qualityProfileId = 6,
        )
        val info = runBlocking { homelab.testConnection(seerr) }
        assertEquals("Camille", info.account)
        assertEquals(listOf("HD-1080p", "4K"), info.seerrTargets.single().profiles.map { it.name })
        runBlocking { app.container.settings.saveService(seerr) }

        val film = runBlocking { app.container.tmdb.ensureDetails(FakeTmdbServer.RECOS[2].id) }
        val ticket = checkNotNull(runBlocking { app.container.feed.apply(film, CardAction.REQUEST) }.request)
        waitFor { outbox(ticket.outboxId)?.state == OutboxState.SENT }

        val post = FakeHomelabServer.requests.last { it.method == "POST" && it.url.encodedPath == "/seerr/api/v1/request" }
        assertTrue(post.headers["Cookie"].orEmpty().contains("connect.sid=${FakeHomelabServer.SEERR_SESSION}"))
        assertNull(post.headers["X-Api-Key"])
        assertEquals(6, body(post.body?.utf8()).getValue("profileId").jsonPrimitive.int)
    }

    @Test
    fun homelabScreensShowEveryService() {
        runBlocking { app.container.homelab.sync() }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.onNodeWithContentDescription("Réglages").performClick()
            compose.waitForText("Mode homelab")
            compose.onNodeWithText("Mode homelab").performScrollTo().performClick()

            compose.waitForTag(HomelabTestTags.service(HomelabService.JELLYFIN))
            compose.waitForText("Synchroniser maintenant")
            compose.waitForText("LES DEMANDES VONT À")
            compose.onNodeWithTag(HomelabTestTags.service(HomelabService.JELLYFIN)).performClick()

            compose.waitForText("Adresse avec le port")
            compose.waitForText("Utilisateur : Alex")
            compose.onNodeWithText("Tester la connexion").performScrollTo().performClick()
            compose.waitForText("Connecté à NAS (version 10.10.3)")
            compose.waitForText("Invité")
        }
    }

    private fun userMovie(tmdbId: Long) = runBlocking { app.container.database.userMovieDao().get(tmdbId) }

    private fun outbox(id: Long): OutboxEntity? = runBlocking { app.container.database.outboxDao().get(id) }

    private fun body(raw: String?) = Json.parseToJsonElement(raw.orEmpty()).jsonObject

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition non remplie à temps" }
            Thread.sleep(50)
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
