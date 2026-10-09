package io.github.deedg.cineswipe.data.homelab

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Clients Jellyfin, Plex, Radarr et Seerr face à un faux serveur (JVM, sans Android). */
class HomelabClientsTest {

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
        rootFolder = "/movies",
    )

    @Test
    fun jellyfinReadsEveryPageWithTmdbIdsAndWatchedState() = runBlocking {
        enqueue(
            """{"Items":[
                {"Name":"Dune","ProductionYear":2021,"ProviderIds":{"Tmdb":"438631","Imdb":"tt1160419"},
                 "UserData":{"Played":true,"LastPlayedDate":"2024-03-01T20:15:00.0000000Z"}},
                {"Name":"Sans identifiant","ProviderIds":{}}
            ],"TotalRecordCount":3}""",
        )
        enqueue("""{"Items":[{"Name":"Alien","ProductionYear":1979,"ProviderIds":{"tmdb":"348"},"UserData":{"Played":false}}],"TotalRecordCount":3}""")
        val client = JellyfinClient(http, deviceId = "appareil", appVersion = "0.2.0")

        val movies = client.movies(config(HomelabService.JELLYFIN, "/jellyfin", key = "jf"))

        assertEquals(3, movies.size)
        val dune = movies.first { it.title == "Dune" }
        assertEquals(438631L, dune.tmdbId)
        assertTrue(dune.watched)
        assertEquals(Instant.parse("2024-03-01T20:15:00Z").toEpochMilli(), dune.lastWatchedAt)
        assertNull(movies.first { it.title == "Sans identifiant" }.tmdbId)
        val alien = movies.first { it.title == "Alien" }
        assertEquals(348L, alien.tmdbId)
        assertFalse(alien.watched)

        val first = next()
        assertEquals("/jellyfin/Items", first.url.encodedPath)
        assertEquals("u1", first.url.queryParameter("userId"))
        assertEquals("0", first.url.queryParameter("StartIndex"))
        assertTrue(first.headers["Authorization"].orEmpty().contains("""Token="jf""""))
        assertEquals("2", next().url.queryParameter("StartIndex"))
    }

    @Test
    fun jellyfinLoginKeepsOnlyTheToken() = runBlocking {
        enqueue("""{"User":{"Id":"abc","Name":"Alex"},"AccessToken":"jeton"}""")
        val client = JellyfinClient(http, deviceId = "appareil", appVersion = "0.2.0")

        val login = client.login(server.url("/"), emptyMap(), "Alex", "secret")

        assertEquals(JellyfinLogin(token = "jeton", userId = "abc", userName = "Alex"), login)
        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/Users/AuthenticateByName", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
        assertEquals("Alex", body.getValue("Username").jsonPrimitive.content)
        assertEquals("secret", body.getValue("Pw").jsonPrimitive.content)
        val authorization = request.headers["Authorization"].orEmpty()
        assertTrue(authorization.startsWith("""MediaBrowser Client="CineSwipe""""))
        assertFalse(authorization.contains("Token="))
    }

    @Test
    fun plexReadsMovieLibrariesWithNewAndLegacyIds() = runBlocking {
        enqueue("""{"MediaContainer":{"Directory":[{"key":"1","title":"Films","type":"movie"},{"key":"2","title":"Séries","type":"show"}]}}""")
        enqueue(
            """{"MediaContainer":{"size":2,"totalSize":2,"Metadata":[
                {"title":"Dune","year":2021,"viewCount":2,"lastViewedAt":1700000000,"Guid":[{"id":"imdb://tt1160419"},{"id":"tmdb://438631"}]},
                {"title":"Ancien agent","year":1999,"guid":"com.plexapp.agents.themoviedb://603?lang=fr"}
            ]}}""",
        )
        val client = PlexClient(http, clientId = "appareil")

        val movies = client.movies(config(HomelabService.PLEX, "/plex", key = "jeton-plex"))

        assertEquals(2, movies.size)
        val dune = movies.first { it.title == "Dune" }
        assertEquals(438631L, dune.tmdbId)
        assertTrue(dune.watched)
        assertEquals(1_700_000_000_000L, dune.lastWatchedAt)
        val legacy = movies.first { it.title == "Ancien agent" }
        assertEquals(603L, legacy.tmdbId)
        assertFalse(legacy.watched)

        val sections = next()
        assertEquals("/plex/library/sections", sections.url.encodedPath)
        assertEquals("jeton-plex", sections.headers["X-Plex-Token"])
        assertEquals("/plex/library/sections/1/all", next().url.encodedPath)
    }

    @Test
    fun radarrDoesNotAddAMovieTwice() = runBlocking {
        enqueue("""[{"id":12,"tmdbId":438631,"title":"Dune","hasFile":false,"monitored":true}]""")

        val result = RadarrClient(http).addMovie(config(HomelabService.RADARR, "/radarr"), 438631)

        assertEquals(RadarrAddResult(radarrId = 12, alreadyPresent = true), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun radarrAddsTheLookedUpMovieWithTheChosenProfileAndFolder() = runBlocking {
        enqueue("[]")
        enqueue("""{"title":"Dune","tmdbId":438631,"year":2021,"images":[],"id":0}""")
        enqueue("""{"id":42,"tmdbId":438631}""", code = 201)

        val result = RadarrClient(http).addMovie(config(HomelabService.RADARR, "/radarr", key = "rd"), 438631)

        assertEquals(RadarrAddResult(radarrId = 42, alreadyPresent = false), result)
        assertEquals("438631", next().url.queryParameter("tmdbId"))
        assertEquals("/radarr/api/v3/movie/lookup/tmdb", next().url.encodedPath)
        val post = next()
        assertEquals("POST", post.method)
        assertEquals("/radarr/api/v3/movie", post.url.encodedPath)
        assertEquals("rd", post.headers["X-Api-Key"])
        val body = Json.parseToJsonElement(post.body?.utf8().orEmpty()).jsonObject
        assertEquals("Dune", body.getValue("title").jsonPrimitive.content)
        assertEquals(4, body.getValue("qualityProfileId").jsonPrimitive.int)
        assertEquals("/movies", body.getValue("rootFolderPath").jsonPrimitive.content)
        assertTrue(body.getValue("monitored").jsonPrimitive.boolean)
        assertTrue(body.getValue("addOptions").jsonObject.getValue("searchForMovie").jsonPrimitive.boolean)
        assertFalse("id" in body)
    }

    @Test
    fun seerrRequestReturnsItsIdOrSaysItWasAlreadyRequested() = runBlocking {
        val client = SeerrClient(http)
        val seerr = config(HomelabService.SEERR, "/seerr", key = "sr")
        enqueue("""{"id":77,"status":2,"type":"movie","media":{"tmdbId":438631,"status":3}}""", code = 201)
        enqueue("""{"message":"Request already exists"}""", code = 409)

        assertEquals(SeerrRequestResult(requestId = 77, alreadyRequested = false), client.request(seerr, 438631))
        assertTrue(client.request(seerr, 438631).alreadyRequested)

        val first = next()
        assertEquals("/seerr/api/v1/request", first.url.encodedPath)
        assertEquals("sr", first.headers["X-Api-Key"])
        val body = Json.parseToJsonElement(first.body?.utf8().orEmpty()).jsonObject
        assertEquals("movie", body.getValue("mediaType").jsonPrimitive.content)
        assertEquals(438631, body.getValue("mediaId").jsonPrimitive.int)
    }

    @Test
    fun seerrListsMovieRequestsAndAvailableMovies() = runBlocking {
        val client = SeerrClient(http)
        val seerr = config(HomelabService.SEERR, "/seerr")
        enqueue(
            """{"pageInfo":{"pages":1,"page":1,"results":2},"results":[
                {"id":1,"status":1,"type":"movie","media":{"tmdbId":10,"status":2}},
                {"id":2,"status":2,"type":"tv","media":{"tmdbId":20,"status":3}}
            ]}""",
        )
        enqueue("""{"pageInfo":{"results":2},"results":[{"tmdbId":30,"mediaType":"movie","status":5},{"tmdbId":40,"mediaType":"tv","status":5}]}""")

        assertEquals(listOf(10L), client.movieRequests(seerr).map { it.media?.tmdbId })
        assertEquals(setOf(30L), client.availableMovies(seerr))
    }

    @Test
    fun errorsBecomeHomelabExceptions() = runBlocking {
        enqueue("""{"message":"Unauthorized"}""", code = 401)
        expect(HomelabException.Kind.UNAUTHORIZED) { SeerrClient(http).me(server.url("/"), mapOf("X-Api-Key" to "mauvaise")) }

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>Radarr</html>").build())
        expect(HomelabException.Kind.INVALID_RESPONSE) { JellyfinClient(http, "appareil", "0.2.0").publicInfo(server.url("/"), emptyMap()) }

        enqueue("""{"error":"boom"}""", code = 503)
        val error = expect(HomelabException.Kind.HTTP) { RadarrClient(http).status(server.url("/"), emptyMap(), "cle") }
        assertEquals(503, error.code)
        assertTrue(error.isTransient)

        val closed = MockWebServer().apply { start() }
        val unreachable = closed.url("/")
        closed.close()
        val offline = expect(HomelabException.Kind.UNREACHABLE) { SeerrClient(http).status(unreachable, emptyMap()) }
        assertTrue(offline.isTransient)
    }

    @Test
    fun seerrUserLoginKeepsTheSessionCookieInsteadOfTheApiKey() = runBlocking {
        val client = SeerrClient(http)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "XSRF-TOKEN=jeton-csrf; Path=/")
                .body("""{"localLogin":true,"mediaServerLogin":true,"mediaServerType":2}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "connect.sid=s%3Asession.signature; Path=/; Expires=Sun, 01 Nov 2026 10:00:00 GMT; HttpOnly")
                .body("""{"id":7,"displayName":"Camille","email":"camille@exemple.fr"}""")
                .build(),
        )
        enqueue("""{"id":91,"status":1,"type":"movie","media":{"status":2}}""", code = 201)

        val session = client.login(server.url("/seerr"), emptyMap(), SeerrAuthMode.LOCAL, " camille@exemple.fr ", "secret")
        assertEquals(7L, session.userId)
        assertEquals("Camille", session.displayName)
        assertEquals("s:session.signature", SeerrClient.cookieValue(session.cookies, "connect.sid"))
        assertTrue(session.cookies.contains("connect.sid=s%3Asession.signature"))

        val config = ServiceConfig(
            service = HomelabService.SEERR,
            enabled = true,
            url = server.url("/seerr").toString(),
            apiKey = session.cookies,
            seerrAuthMode = SeerrAuthMode.LOCAL,
        )
        assertEquals(SeerrRequestResult(requestId = 91, alreadyRequested = false), client.request(config, 603))

        assertEquals("/seerr/api/v1/settings/public", next().url.encodedPath)
        val login = next()
        assertEquals("/seerr/api/v1/auth/local", login.url.encodedPath)
        val credentials = Json.parseToJsonElement(login.body?.utf8().orEmpty()).jsonObject
        assertEquals("camille@exemple.fr", credentials.getValue("email").jsonPrimitive.content)
        assertEquals("secret", credentials.getValue("password").jsonPrimitive.content)
        assertEquals("jeton-csrf", login.headers["X-XSRF-TOKEN"])
        val request = next()
        assertTrue(request.headers["Cookie"].orEmpty().contains("connect.sid=s%3Asession.signature"))
        assertNull(request.headers["X-Api-Key"])
    }

    @Test
    fun seerrRefusesALoginModeItDoesNotOffer() = runBlocking {
        enqueue("""{"localLogin":false,"mediaServerLogin":true,"mediaServerType":1}""")
        expect(HomelabException.Kind.LOGIN_DISABLED) {
            SeerrClient(http).login(server.url("/"), emptyMap(), SeerrAuthMode.JELLYFIN, "alex", "secret")
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun seerrRequestCarriesTheChosenServerProfileAndFolder() = runBlocking {
        enqueue("""{"id":5,"status":2,"type":"movie"}""", code = 201)
        val config = config(HomelabService.SEERR, "/seerr", key = "sr").copy(
            targetServerId = 2,
            targetServer4k = true,
            qualityProfileId = 9,
            rootFolder = "/films-4k",
        )

        SeerrClient(http).request(config, 438631)

        val body = Json.parseToJsonElement(next().body?.utf8().orEmpty()).jsonObject
        assertEquals(2, body.getValue("serverId").jsonPrimitive.int)
        assertTrue(body.getValue("is4k").jsonPrimitive.boolean)
        assertEquals(9, body.getValue("profileId").jsonPrimitive.int)
        assertEquals("/films-4k", body.getValue("rootFolder").jsonPrimitive.content)
    }

    @Test
    fun seerrListsItsRadarrServersWithProfilesAndFolders() = runBlocking {
        enqueue("""[{"id":0,"name":"Radarr","is4k":false,"isDefault":true,"activeProfileId":4,"activeDirectory":"/movies"}]""")
        enqueue("""{"server":{"id":0},"profiles":[{"id":4,"name":"HD-1080p"},{"id":6,"name":"4K"}],"rootFolders":[{"id":1,"path":"/movies","freeSpace":10}]}""")

        val targets = SeerrClient(http).targets(server.url("/"), mapOf("X-Api-Key" to "sr"))

        assertEquals(1, targets.size)
        val target = targets.single()
        assertEquals("Radarr", target.name)
        assertTrue(target.isDefault)
        assertEquals(4, target.activeProfileId)
        assertEquals(listOf("HD-1080p", "4K"), target.profiles.map { it.name })
        assertEquals(listOf("/movies"), target.rootFolders.map { it.path })
        assertEquals("/api/v1/service/radarr", next().url.encodedPath)
        assertEquals("/api/v1/service/radarr/0", next().url.encodedPath)
    }

    @Test
    fun plexSignInGivesTheAccountTokenThenItsServers() = runBlocking {
        val client = PlexClient(http, clientId = "appareil", plexTv = server.url("/"))
        enqueue("""{"id":4242,"code":"code-long","authToken":null}""", code = 201)
        enqueue("""{"id":4242,"code":"code-long","authToken":null}""")
        enqueue("""{"id":4242,"code":"code-long","authToken":"jeton-compte"}""")
        enqueue(
            """[
                {"name":"mon-serveur","provides":"server","owned":true,"accessToken":"jeton-serveur","connections":[
                    {"uri":"https://192-168-1-88.abc.plex.direct:32400","local":true,"relay":false},
                    {"uri":"https://relais.plex.direct:8443","local":false,"relay":true}]},
                {"name":"Pixel","provides":"client,player","owned":true,"connections":[]}
            ]""",
        )

        val pin = client.createPin()
        assertEquals(4242L, pin.id)
        assertTrue(pin.url.startsWith("https://app.plex.tv/auth#?clientID=appareil&code=code-long"))
        assertNull(client.pinToken(pin.id))
        assertEquals("jeton-compte", client.pinToken(pin.id))
        val servers = client.servers("jeton-compte")

        assertEquals(listOf("mon-serveur"), servers.map { it.name })
        assertEquals("jeton-serveur", servers.single().accessToken)
        assertEquals(2, servers.single().connections.size)
        val create = next()
        assertEquals("POST", create.method)
        assertEquals("/api/v2/pins", create.url.encodedPath)
        assertEquals("true", create.url.queryParameter("strong"))
        assertEquals("appareil", create.headers["X-Plex-Client-Identifier"])
        next()
        next()
        assertEquals("jeton-compte", next().headers["X-Plex-Token"])
    }

    @Test
    fun plexDirectNamesAreResolvedWithoutDns() {
        val addresses = PlexDirectDns.lookup("100-64-1-2.0123456789abcdef0123456789abcdef.plex.direct")
        assertEquals(listOf("100.64.1.2"), addresses.map { it.hostAddress })
    }

    private suspend fun expect(kind: HomelabException.Kind, block: suspend () -> Unit): HomelabException {
        try {
            block()
        } catch (error: HomelabException) {
            assertEquals(kind, error.kind)
            return error
        }
        fail("HomelabException $kind attendue")
        error("inaccessible")
    }
}
