package io.github.deedg.cineswipe.testing

import io.github.deedg.cineswipe.AppConfig
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.core.SecretCipher
import io.github.deedg.cineswipe.data.homelab.HomelabScheduler
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** Faux serveur TMDB (MockWebServer) : quelques films, la clé est vérifiée à chaque requête. */
object FakeTmdbServer {
    const val API_KEY = "cle-de-test"

    data class FakeMovie(val id: Long, val title: String, val year: Int, val genreIds: List<Int>)

    /** Films renvoyés par toutes les listes (recos, similaires, découverte, tendances). */
    val RECOS = listOf(
        FakeMovie(1001, "Film Reco Un", 2019, listOf(878)),
        FakeMovie(1002, "Film Reco Deux", 2020, listOf(18)),
        FakeMovie(1003, "Film Reco Trois", 2021, listOf(878, 18)),
    )
    val DUNE = FakeMovie(1004, "Dune", 2021, listOf(878, 12))

    /** Saga du premier film des recos : sa suite n'apparaît que par la saga. */
    const val SAGA_ID = 5000L
    val SEQUEL = FakeMovie(1005, "Film Reco Un : La Suite", 2023, listOf(878))

    /** Drive, déjà vu dans la base V4 de test : présent dans les listes TMDB, jamais proposé. */
    val WATCHED = FakeMovie(64690, "Drive", 2011, listOf(18))

    /** La réalisatrice de tous les films de test (elle joue aussi dans le premier) et l'actrice principale. */
    const val DIRECTOR_ID = 777L
    const val ACTRESS_ID = 888L

    private fun credit(movie: FakeMovie, extra: String) =
        """{"id":${movie.id},"title":"${movie.title}","release_date":"${movie.year}-05-01","poster_path":"/${movie.id}.jpg",""" +
            """"vote_count":${movie.id - 900},"popularity":1.0,$extra}"""

    private fun personJson(id: Long?): String? = when (id) {
        DIRECTOR_ID -> """{"id":$DIRECTOR_ID,"name":"Réalisatrice Test","known_for_department":"Directing","birthday":"1970-01-01",""" +
            """"movie_credits":{"cast":[${credit(RECOS.first(), "\"character\":\"Elle-même\"")}],""" +
            """"crew":[${credit(RECOS.first(), "\"job\":\"Director\"")},${credit(DUNE, "\"job\":\"Director\"")},""" +
            """${credit(DUNE, "\"job\":\"Writer\"")}]}}"""
        ACTRESS_ID -> """{"id":$ACTRESS_ID,"name":"Actrice Principale","known_for_department":"Acting",""" +
            """"movie_credits":{"cast":[${credit(DUNE, "\"character\":\"Héroïne\"")},${credit(RECOS[1], "\"character\":\"Sœur\"")}],"crew":[]}}"""
        else -> null
    }

    /** Où regarder Dune en France : un abonnement et une location. */
    const val STREAMING_PROVIDER = "StreamTest+"
    private val PROVIDERS_JSON = """{"id":1004,"results":{"FR":{"link":"https://www.themoviedb.org/movie/1004/watch?locale=FR",""" +
        """"flatrate":[{"provider_id":8,"provider_name":"$STREAMING_PROVIDER","logo_path":"/st.jpg","display_priority":1}],""" +
        """"rent":[{"provider_id":2,"provider_name":"LocaTest","logo_path":"/lt.jpg","display_priority":2}]}}}"""

    private val ALL: List<FakeMovie> get() = RECOS + DUNE + SEQUEL + WATCHED

    /** Identifiants IMDb connus de `/find`. */
    val IMDB_IDS: Map<String, FakeMovie> get() = mapOf("tt1160419" to DUNE, "tt0780504" to WATCHED)

    private val GENRES = mapOf(18 to "Drame", 12 to "Aventure", 878 to "Science-Fiction")

    val server: MockWebServer by lazy {
        MockWebServer().apply {
            dispatcher = FakeTmdbDispatcher
            start()
        }
    }

    val baseUrl: String get() = server.url("/3/").toString()

    private object FakeTmdbDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            if (url.queryParameter("api_key") != API_KEY) return response(401, """{"success":false,"status_code":7}""")
            val path = url.encodedPath
            return when {
                path == "/3/authentication" -> response(200, """{"success":true}""")
                path == "/3/genre/movie/list" -> response(200, genresJson())
                path.endsWith("/recommendations") || path.endsWith("/similar") ||
                    path == "/3/trending/movie/week" || path == "/3/discover/movie" -> response(200, pageJson(listOf(WATCHED) + RECOS))
                path == "/3/search/movie" -> {
                    // Titre contenant la recherche, et année de sortie si elle est demandée.
                    val query = url.queryParameter("query").orEmpty()
                    val year = url.queryParameter("year")?.toIntOrNull()
                    val found = ALL.filter { it.title.contains(query, ignoreCase = true) && (year == null || it.year == year) }
                    response(200, pageJson(found))
                }
                path == "/3/search/person" -> {
                    // Personnes dont le nom contient la recherche.
                    val query = url.queryParameter("query").orEmpty()
                    val hits = listOf(DIRECTOR_ID to "Réalisatrice Test", ACTRESS_ID to "Actrice Principale")
                        .filter { it.second.contains(query, ignoreCase = true) }
                        .joinToString(",") { (id, name) ->
                            val department = if (id == DIRECTOR_ID) "Directing" else "Acting"
                            """{"id":$id,"name":"$name","known_for_department":"$department","known_for":[{"id":${DUNE.id},"title":"${DUNE.title}","media_type":"movie"}]}"""
                        }
                    response(200, """{"page":1,"results":[$hits],"total_pages":1}""")
                }
                path.startsWith("/3/find/") -> {
                    val movie = IMDB_IDS[path.removePrefix("/3/find/")]
                    response(200, """{"movie_results":[${movie?.let(::summaryJson).orEmpty()}],"tv_results":[]}""")
                }
                path == "/3/collection/$SAGA_ID" -> response(200, collectionJson())
                path.endsWith("/watch/providers") -> response(
                    200,
                    if (path == "/3/movie/${DUNE.id}/watch/providers") PROVIDERS_JSON else """{"id":0,"results":{}}""",
                )
                path.startsWith("/3/person/") -> personJson(path.removePrefix("/3/person/").toLongOrNull())
                    ?.let { response(200, it) } ?: response(404, """{"success":false}""")
                path.startsWith("/3/movie/") -> {
                    val movie = (RECOS + DUNE + SEQUEL + WATCHED).firstOrNull { it.id.toString() == path.removePrefix("/3/movie/") }
                    if (movie == null) response(404, """{"success":false,"status_code":34}""") else response(200, detailsJson(movie))
                }
                else -> response(404, """{"success":false}""")
            }
        }
    }

    private fun response(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()

    private fun genresJson(): String =
        GENRES.entries.joinToString(prefix = """{"genres":[""", postfix = "]}") { (id, name) -> """{"id":$id,"name":"$name"}""" }

    private fun summaryJson(movie: FakeMovie): String =
        """{"id":${movie.id},"title":"${movie.title}","original_title":"${movie.title}","release_date":"${movie.year}-05-01",""" +
            """"poster_path":"/${movie.id}.jpg","overview":"Synopsis de ${movie.title}.","vote_average":7.1,"vote_count":900,""" +
            """"genre_ids":${movie.genreIds},"adult":false}"""

    private fun pageJson(movies: List<FakeMovie>): String {
        val results = movies.joinToString(",", transform = ::summaryJson)
        val pages = if (movies.isEmpty()) 0 else 1
        return """{"page":1,"total_pages":$pages,"total_results":${movies.size},"results":[$results]}"""
    }

    private fun collectionJson(): String =
        """{"id":$SAGA_ID,"name":"Saga Reco","parts":[${summaryJson(RECOS.first())},${summaryJson(SEQUEL)}]}"""

    private fun detailsJson(movie: FakeMovie): String {
        val genres = movie.genreIds.joinToString(",") { """{"id":$it,"name":"${GENRES[it]}"}""" }
        return """{"id":${movie.id},"title":"${movie.title}","original_title":"${movie.title}","release_date":"${movie.year}-05-01",""" +
            """"poster_path":"/${movie.id}.jpg","overview":"Synopsis de ${movie.title}.","vote_average":7.4,"vote_count":1200,""" +
            """"runtime":118,"genres":[$genres],""" +
            (if (movie == RECOS.first() || movie == SEQUEL) """"belongs_to_collection":{"id":$SAGA_ID,"name":"Saga Reco"},""" else "") +
            """"credits":{"cast":[{"id":$ACTRESS_ID,"name":"Actrice Principale","profile_path":null,"order":0}],""" +
            """"crew":[{"id":$DIRECTOR_ID,"name":"Réalisatrice Test","job":"Director"}]},""" +
            """"videos":{"results":[{"key":"yt${movie.id}","site":"YouTube","type":"Trailer","iso_639_1":"fr","official":true}]}}"""
    }
}

/** Les clés sont stockées en clair : Robolectric n'a pas d'Android Keystore. */
object PlainCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain

    override fun decrypt(encoded: String): String? = encoded
}

/**
 * Appli de test : TMDB pointe vers [FakeTmdbServer], pas de tâches de fond WorkManager, et les
 * demandes au homelab partent sans délai.
 */
class TestCineSwipeApp : CineSwipeApp() {
    override fun createContainer(): AppContainer =
        AppContainer(
            this,
            AppConfig(
                tmdbBaseUrl = FakeTmdbServer.baseUrl,
                cipher = { PlainCipher },
                homelabScheduler = { HomelabScheduler.None },
                requestGraceMs = 0,
            ),
        )
}
