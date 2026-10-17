package io.github.deedg.cineswipe.data.homelab

import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Homelab des séries : Plex, Jellyfin, Sonarr et Seerr face à un faux serveur, et la fusion de l'avancement. */
class SeriesHomelabTest {

    private val server = MockWebServer()
    private val http = HomelabHttp()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun next(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Aucune requête reçue")

    private fun config(service: HomelabService, path: String, key: String = "cle") = ServiceConfig(
        service = service,
        enabled = true,
        url = server.url(path).toString(),
        apiKey = key,
        userId = "u1",
        qualityProfileId = 4,
        rootFolder = "/tv",
    )

    @Test
    fun currentSeasonIsTheFirstUnfinishedOne() {
        val seasons = listOf(SeasonWatch(0, 3, 0), SeasonWatch(1, 10, 10), SeasonWatch(2, 10, 4), SeasonWatch(3, 8, 0))
        assertEquals(2, SeriesWatch.currentSeason(seasons))
        assertFalse(SeriesWatch.finished(seasons))
        // Les épisodes spéciaux (saison 0) ne comptent pas.
        assertTrue(SeriesWatch.finished(listOf(SeasonWatch(0, 3, 0), SeasonWatch(1, 6, 6))))
        assertNull(SeriesWatch.currentSeason(listOf(SeasonWatch(1, 6, 6))))
    }

    @Test
    fun plexShowsCarryTheirProgress() = runBlocking {
        enqueue("""{"MediaContainer":{"Directory":[{"key":"1","title":"Films","type":"movie"},{"key":"2","title":"Séries","type":"show"}]}}""")
        enqueue(
            """{"MediaContainer":{"size":3,"totalSize":3,"Metadata":[
                {"ratingKey":"10","title":"Breaking Bad","year":2008,"leafCount":62,"viewedLeafCount":62,"lastViewedAt":1700000000,"Guid":[{"id":"tmdb://1396"}]},
                {"ratingKey":"11","title":"Dark","year":2017,"leafCount":26,"viewedLeafCount":12,"Guid":[{"id":"tmdb://70523"}]},
                {"ratingKey":"12","title":"Lost","year":2004,"leafCount":121,"viewedLeafCount":0,"Guid":[{"id":"tmdb://4607"}]}
            ]}}""",
        )
        enqueue(
            """{"MediaContainer":{"Metadata":[
                {"index":1,"leafCount":10,"viewedLeafCount":10},{"index":2,"leafCount":8,"viewedLeafCount":2},{"index":3,"leafCount":8,"viewedLeafCount":0}
            ]}}""",
        )
        val shows = PlexClient(http, "appareil").shows(config(HomelabService.PLEX, "/", key = "jeton"))

        val bb = shows.first { it.tmdbId == 1396L }
        assertTrue(bb.watched && bb.finished)
        assertNull(bb.currentSeason)
        val dark = shows.first { it.tmdbId == 70523L }
        assertTrue(dark.watched)
        assertFalse(dark.finished)
        assertEquals(2, dark.currentSeason)
        val lost = shows.first { it.tmdbId == 4607L }
        assertFalse(lost.watched)

        assertEquals("/library/sections", next().url.encodedPath)
        val list = next()
        assertEquals("/library/sections/2/all", list.url.encodedPath)
        assertEquals("2", list.url.queryParameter("type"))
        // Les saisons ne sont lues que pour la série commencée.
        assertEquals("/library/metadata/11/children", next().url.encodedPath)
    }

    @Test
    fun jellyfinSeriesCarryTheirProgress() = runBlocking {
        enqueue(
            """{"Items":[
                {"Id":"s1","Name":"Breaking Bad","ProviderIds":{"Tmdb":"1396"},"UserData":{"Played":true,"UnplayedItemCount":0,"PlayedPercentage":100}},
                {"Id":"s2","Name":"Dark","ProviderIds":{"Tmdb":"70523"},"UserData":{"Played":false,"UnplayedItemCount":14,"PlayedPercentage":46.1,"LastPlayedDate":"2026-10-10T21:00:00Z"}},
                {"Id":"s3","Name":"Lost","ProviderIds":{"Tmdb":"4607"},"UserData":{"Played":false,"UnplayedItemCount":121}}
            ],"TotalRecordCount":3}""",
        )
        enqueue(
            """{"Items":[
                {"ParentIndexNumber":1,"UserData":{"Played":true}},{"ParentIndexNumber":1,"UserData":{"Played":true}},
                {"ParentIndexNumber":2,"UserData":{"Played":true}},{"ParentIndexNumber":2,"UserData":{"Played":false}},
                {"ParentIndexNumber":3,"UserData":{"Played":false}}
            ]}""",
        )
        val series = JellyfinClient(http, deviceId = "appareil", appVersion = "0.2.0").series(config(HomelabService.JELLYFIN, "/", key = "jf"))

        assertTrue(series.first { it.tmdbId == 1396L }.finished)
        val dark = series.first { it.tmdbId == 70523L }
        assertTrue(dark.watched)
        assertEquals(2, dark.currentSeason)
        assertFalse(series.first { it.tmdbId == 4607L }.watched)

        val items = next()
        assertEquals("Series", items.url.queryParameter("IncludeItemTypes"))
        val episodes = next()
        assertEquals("/Shows/s2/Episodes", episodes.url.encodedPath)
        assertEquals("u1", episodes.url.queryParameter("userId"))
    }

    @Test
    fun sonarrAddsASeriesByTvdbId() = runBlocking {
        enqueue("""[{"id":3,"tvdbId":81189,"title":"Breaking Bad","monitored":true,"statistics":{"episodeFileCount":62,"episodeCount":62}}]""")
        enqueue("""[{"title":"Dark","tvdbId":334824,"year":2017,"seasons":[],"id":0}]""")
        enqueue("""[{"id":1,"name":"English"}]""")
        enqueue("""{"id":9}""", code = 201)

        val result = SonarrClient(http).addSeries(config(HomelabService.SONARR, "/"), tvdbId = 334824)

        assertEquals(SonarrAddResult(9, alreadyPresent = false), result)
        assertEquals("/api/v3/series", next().url.encodedPath)
        val lookup = next()
        assertEquals("/api/v3/series/lookup", lookup.url.encodedPath)
        assertEquals("tvdb:334824", lookup.url.queryParameter("term"))
        assertEquals("/api/v3/languageprofile", next().url.encodedPath)
        val post = next()
        assertEquals("POST", post.method)
        assertEquals("cle", post.headers["X-Api-Key"])
        val body = Json.parseToJsonElement(post.body?.utf8().orEmpty()).jsonObject
        assertEquals(4, body.getValue("qualityProfileId").jsonPrimitive.int)
        assertEquals(1, body.getValue("languageProfileId").jsonPrimitive.int)
        assertEquals("/tv", body.getValue("rootFolderPath").jsonPrimitive.content)
        assertEquals("all", body.getValue("addOptions").jsonObject.getValue("monitor").jsonPrimitive.content)
        assertTrue(body.getValue("addOptions").jsonObject.getValue("searchForMissingEpisodes").jsonPrimitive.boolean)
        assertFalse("id" in body)
    }

    @Test
    fun sonarrSaysWhenASeriesIsAlreadyThere() = runBlocking {
        enqueue("""[{"id":3,"tvdbId":81189,"title":"Breaking Bad","monitored":true}]""")
        assertEquals(SonarrAddResult(3, alreadyPresent = true), SonarrClient(http).addSeries(config(HomelabService.SONARR, "/"), tvdbId = 81189))
    }

    @Test
    fun seerrRequestsAWholeSeries() = runBlocking {
        enqueue("""{"id":12,"status":1,"type":"tv"}""", code = 201)
        val result = SeerrClient(http).request(config(HomelabService.SEERR, "/").copy(targetServerId = 0), 70523, tv = true)
        assertEquals(12L, result.requestId)
        val body = Json.parseToJsonElement(next().body?.utf8().orEmpty()).jsonObject
        assertEquals("tv", body.getValue("mediaType").jsonPrimitive.content)
        assertEquals("all", body.getValue("seasons").jsonPrimitive.content)
        // Le serveur Radarr choisi pour les films ne s'applique pas aux séries.
        assertFalse("serverId" in body)
    }

    @Test
    fun someSeasonsOnlyAreRequested() = runBlocking {
        // Seerr : la liste des saisons.
        enqueue("""{"id":13,"status":1,"type":"tv"}""", code = 201)
        SeerrClient(http).request(config(HomelabService.SEERR, "/"), 70523, tv = true, seasons = listOf(1, 3))
        val seerrBody = Json.parseToJsonElement(next().body?.utf8().orEmpty()).jsonObject
        assertEquals(listOf(1, 3), seerrBody.getValue("seasons").jsonArray.map { it.jsonPrimitive.int })

        // Sonarr : seules les saisons choisies sont surveillées.
        enqueue("[]")
        enqueue("""[{"title":"Dark","tvdbId":334824,"seasons":[{"seasonNumber":0,"monitored":true},{"seasonNumber":1,"monitored":true},{"seasonNumber":2,"monitored":true},{"seasonNumber":3,"monitored":true}],"id":0}]""")
        enqueue("{}", code = 404)
        enqueue("""{"id":10}""", code = 201)
        SonarrClient(http).addSeries(config(HomelabService.SONARR, "/"), tvdbId = 334824, seasons = listOf(1, 3))
        next()
        next()
        next()
        val sonarrBody = Json.parseToJsonElement(next().body?.utf8().orEmpty()).jsonObject
        val monitored = sonarrBody.getValue("seasons").jsonArray.associate {
            it.jsonObject.getValue("seasonNumber").jsonPrimitive.int to it.jsonObject.getValue("monitored").jsonPrimitive.boolean
        }
        assertEquals(mapOf(0 to false, 1 to true, 2 to false, 3 to true), monitored)
        assertEquals("unknown", sonarrBody.getValue("addOptions").jsonObject.getValue("monitor").jsonPrimitive.content)
        // Sonarr v4 : pas de profil de langue.
        assertFalse("languageProfileId" in sonarrBody)
    }

    @Test
    fun seriesProgressFollowsServersWithoutGoingBack() {
        fun row(id: Long, progress: SeriesProgress?, season: Int? = null, status: WatchStatus = WatchStatus.WATCHED) =
            UserMovieEntity(tmdbId = id, status = status, progress = progress, currentSeason = season, updatedAt = 0)
        fun inProgress(season: Int?) = WatchedOnServer(WatchSource.PLEX, 1_000, series = true, season = season)
        val finished = WatchedOnServer(WatchSource.PLEX, 2_000, series = true, finished = true)
        val current = listOf(
            row(2, SeriesProgress.IN_PROGRESS, season = 1),
            row(3, SeriesProgress.FINISHED),
            row(4, SeriesProgress.ABANDONED, season = 2),
            row(5, SeriesProgress.IN_PROGRESS, season = 4),
            row(6, SeriesProgress.EPISODES),
        )
        val snapshot = HomelabSnapshot(
            watched = mapOf(
                1L to inProgress(2), // nouvelle
                2L to inProgress(3), // la saison avance
                3L to inProgress(2), // finie selon l'utilisateur : ne recule pas
                4L to finished, // décrochée, puis finie sur le serveur
                5L to inProgress(2), // la saison ne redescend pas
                6L to inProgress(5), // vue par épisodes : ne change pas
            ),
        )
        val changes = SyncMerger.merge(current, snapshot, 10)
        val byId = changes.userMovies.associateBy { it.tmdbId }

        assertEquals(WatchStatus.WATCHED, byId.getValue(1).status)
        assertEquals(SeriesProgress.IN_PROGRESS, byId.getValue(1).progress)
        assertEquals(2, byId.getValue(1).currentSeason)
        assertEquals(3, byId.getValue(2).currentSeason)
        assertFalse(3L in byId)
        assertEquals(SeriesProgress.FINISHED, byId.getValue(4).progress)
        assertNull(byId.getValue(4).currentSeason)
        assertFalse(5L in byId)
        assertFalse(6L in byId)
        // Nouvelle série et série tout juste finie : de quoi prévenir.
        assertEquals(setOf(1L, 4L), changes.fresh.map { it.tmdbId }.toSet())
    }
}
