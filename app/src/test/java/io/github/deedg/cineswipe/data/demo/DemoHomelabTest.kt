package io.github.deedg.cineswipe.data.demo

import io.github.deedg.cineswipe.data.homelab.HomelabHttp
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.PlexClient
import io.github.deedg.cineswipe.data.homelab.SeerrClient
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Homelab fictif de la démo : les vrais clients Plex et Seerr y trouvent leurs réponses. */
class DemoHomelabTest {

    private val catalog = DemoCatalog.parse(File("src/main/assets/${DemoCatalog.ASSET}").readText())
    private val interceptor = DemoHomelabInterceptor(catalog)
    private val http = HomelabHttp(HomelabHttp.defaultClient().newBuilder().addInterceptor(interceptor).build())
    private val plex = ServiceConfig(HomelabService.PLEX, enabled = true, url = DemoHomelab.PLEX_URL, apiKey = DemoHomelab.KEY)
    private val seerr = ServiceConfig(HomelabService.SEERR, enabled = true, url = DemoHomelab.SEERR_URL, apiKey = DemoHomelab.KEY)

    @Test
    fun plexHasTheWatchedFilmsAndAThirdOfTheCatalog() = runBlocking {
        val movies = PlexClient(http, "test").movies(plex)
        val ids = movies.mapNotNull { it.tmdbId }.toSet()
        assertEquals(interceptor.available, ids)
        assertTrue(ids.size in 35..50)
        assertTrue(movies.filter { it.watched }.all { it.tmdbId in DemoSeeder.WATCHED })
        assertFalse(DemoSeeder.WATCHLIST.any { it in ids })
        assertEquals("demo", PlexClient(http, "test").machineIdentifier(plex))
    }

    @Test
    fun seerrAcceptsRequests() = runBlocking {
        val client = SeerrClient(http)
        val result = client.request(seerr, 244786)
        assertFalse(result.alreadyRequested)
        assertTrue(result.requestId > 0)
        client.deleteRequest(seerr, result.requestId)
        assertEquals(1, client.movieRequests(seerr).size)
        assertEquals(interceptor.available, client.availableMovies(seerr))
    }
}
