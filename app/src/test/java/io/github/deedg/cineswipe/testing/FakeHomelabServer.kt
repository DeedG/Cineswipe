package io.github.deedg.cineswipe.testing

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Faux homelab (MockWebServer) : Jellyfin, Radarr et Seerr sur le même port, chacun sous son
 * chemin (/jellyfin, /radarr, /seerr), comme derrière un proxy. Les clés sont vérifiées.
 */
object FakeHomelabServer {
    const val JELLYFIN_KEY = "cle-jellyfin"
    const val RADARR_KEY = "cle-radarr"
    const val SEERR_KEY = "cle-seerr"
    const val JELLYFIN_USER = "u1"
    const val SEERR_REQUEST_ID = 77L
    const val SEERR_USER_EMAIL = "camille@exemple.fr"
    const val SEERR_USER_PASSWORD = "mot-de-passe"
    const val SEERR_USER_ID = 7L
    const val SEERR_SESSION = "s%3Asession-camille.signature"
    const val RADARR_MOVIE_ID = 42L

    /** Vu sur Jellyfin (connu du faux TMDB). */
    const val WATCHED_ID = 1001L
    const val WATCHED_AT = "2024-05-01T20:00:00Z"

    /** Sur Jellyfin, pas encore vu. */
    const val AVAILABLE_ID = 1002L

    /** Surveillé par Radarr, pas encore téléchargé. */
    const val RADARR_WANTED_ID = 1003L

    /** Requêtes reçues, pour les vérifications. */
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    val server: MockWebServer by lazy {
        MockWebServer().apply {
            dispatcher = HomelabDispatcher
            start()
        }
    }

    /** Adresse d'un service, port compris (« http://localhost:41234/radarr »). */
    fun url(service: String): String = server.url("/$service").toString()

    /** Démarre le serveur au besoin et oublie les requêtes des tests précédents. */
    fun reset() {
        check(server.started)
        requests.clear()
    }

    private object HomelabDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.url.encodedPath
            return when {
                path.startsWith("/jellyfin/") -> jellyfin(request, path.removePrefix("/jellyfin"))
                path.startsWith("/radarr/") -> radarr(request, path.removePrefix("/radarr"))
                path.startsWith("/seerr/") -> seerr(request, path.removePrefix("/seerr"))
                else -> json(404, "{}")
            }
        }
    }

    private fun jellyfin(request: RecordedRequest, path: String): MockResponse {
        if (path == "/System/Info/Public") return json(200, """{"ServerName":"NAS","Version":"10.10.3"}""")
        if (!request.headers["Authorization"].orEmpty().contains("""Token="$JELLYFIN_KEY"""")) return json(401, "{}")
        return when (path) {
            "/Users" -> json(200, """[{"Id":"$JELLYFIN_USER","Name":"Alex"},{"Id":"u2","Name":"Invité"}]""")
            "/Items" -> if (request.url.queryParameter("userId") != JELLYFIN_USER) {
                json(400, "{}")
            } else {
                json(
                    200,
                    """{"Items":[
                        {"Name":"Film Reco Un","ProductionYear":2019,"ProviderIds":{"Tmdb":"$WATCHED_ID"},
                         "UserData":{"Played":true,"LastPlayedDate":"$WATCHED_AT"}},
                        {"Name":"Drive","ProductionYear":2011,"ProviderIds":{"Tmdb":"64690"},"UserData":{"Played":true}},
                        {"Id":"jf-available","Name":"Film Reco Deux","ProductionYear":2020,"ProviderIds":{"Tmdb":"$AVAILABLE_ID"},"UserData":{"Played":false}},
                        {"Name":"Vidéo de vacances","ProviderIds":{},"UserData":{"Played":true}}
                    ],"TotalRecordCount":4}""",
                )
            }
            else -> json(404, "{}")
        }
    }

    private fun radarr(request: RecordedRequest, path: String): MockResponse {
        if (request.headers["X-Api-Key"] != RADARR_KEY) return json(401, "{}")
        val wanted = """{"id":5,"tmdbId":$RADARR_WANTED_ID,"title":"Film Reco Trois","year":2021,"hasFile":false,"monitored":true}"""
        return when {
            path == "/api/v3/system/status" -> json(200, """{"appName":"Radarr","instanceName":"Radarr","version":"5.20.1"}""")
            path == "/api/v3/qualityprofile" -> json(200, """[{"id":4,"name":"HD-1080p"},{"id":6,"name":"4K"}]""")
            path == "/api/v3/rootfolder" -> json(200, """[{"id":1,"path":"/movies","accessible":true}]""")
            path == "/api/v3/movie" && request.method == "GET" -> {
                val tmdbId = request.url.queryParameter("tmdbId")
                when (tmdbId) {
                    null -> json(200, "[$wanted]")
                    RADARR_WANTED_ID.toString() -> json(200, "[$wanted]")
                    else -> json(200, "[]")
                }
            }
            path == "/api/v3/movie/lookup/tmdb" -> {
                val tmdbId = request.url.queryParameter("tmdbId")
                json(200, """{"title":"Film $tmdbId","tmdbId":$tmdbId,"year":2020,"images":[],"id":0}""")
            }
            path == "/api/v3/movie" && request.method == "POST" -> json(201, """{"id":$RADARR_MOVIE_ID}""")
            path == "/api/v3/movie/$RADARR_MOVIE_ID" && request.method == "DELETE" -> json(200, "{}")
            else -> json(404, "{}")
        }
    }

    private fun seerr(request: RecordedRequest, path: String): MockResponse {
        if (path == "/api/v1/status") return json(200, """{"version":"2.7.3"}""")
        if (path == "/api/v1/settings/public") return json(200, """{"localLogin":true,"mediaServerLogin":true,"mediaServerType":2}""")
        if (path == "/api/v1/auth/local" && request.method == "POST") {
            val body = request.body?.utf8().orEmpty()
            return if (body.contains("\"$SEERR_USER_EMAIL\"") && body.contains("\"$SEERR_USER_PASSWORD\"")) {
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "connect.sid=$SEERR_SESSION; Path=/; HttpOnly")
                    .body("""{"id":$SEERR_USER_ID,"displayName":"Camille","email":"$SEERR_USER_EMAIL"}""")
                    .build()
            } else {
                json(403, """{"message":"Access denied."}""")
            }
        }
        val session = request.headers["Cookie"].orEmpty().contains("connect.sid=$SEERR_SESSION")
        if (request.headers["X-Api-Key"] != SEERR_KEY && !session) return json(403, "{}")
        return when {
            path == "/api/v1/auth/me" ->
                if (session) json(200, """{"id":$SEERR_USER_ID,"displayName":"Camille"}""") else json(200, """{"id":1,"displayName":"Alex"}""")
            path == "/api/v1/service/radarr" -> json(200, """[{"id":0,"name":"Radarr","is4k":false,"isDefault":true,"activeProfileId":4,"activeDirectory":"/movies"}]""")
            path == "/api/v1/service/radarr/0" ->
                json(200, """{"profiles":[{"id":4,"name":"HD-1080p"},{"id":6,"name":"4K"}],"rootFolders":[{"id":1,"path":"/movies"}]}""")
            path == "/api/v1/request" && request.method == "GET" -> json(200, """{"pageInfo":{"pages":1,"page":1,"results":0},"results":[]}""")
            path == "/api/v1/request" && request.method == "POST" ->
                json(201, """{"id":$SEERR_REQUEST_ID,"status":2,"type":"movie","media":{"status":3}}""")
            path == "/api/v1/request/$SEERR_REQUEST_ID" && request.method == "DELETE" -> MockResponse.Builder().code(204).build()
            path == "/api/v1/media" -> json(200, """{"pageInfo":{"pages":1,"page":1,"results":0},"results":[]}""")
            else -> json(404, "{}")
        }
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()
}
