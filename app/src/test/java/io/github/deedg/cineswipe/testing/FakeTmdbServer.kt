package io.github.deedg.cineswipe.testing

import io.github.deedg.cineswipe.AppConfig
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.core.SecretCipher
import io.github.deedg.cineswipe.data.homelab.HomelabScheduler
import io.github.deedg.cineswipe.data.model.MediaKind
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

    // --- Séries (points d'accès `tv`, au format TMDB des séries) ---

    data class FakeShow(val id: Long, val name: String, val year: Int, val genreIds: List<Int>)

    /** Séries renvoyées par toutes les listes de séries. */
    val SHOWS = listOf(
        FakeShow(3001, "Série Reco Un", 2015, listOf(10765)),
        FakeShow(3002, "Série Reco Deux", 2018, listOf(18)),
    )

    /** Même identifiant TMDB que Dune : une autre œuvre, dans l'autre monde. */
    val GUARDIANS = FakeShow(1004, "Les Gardiens", 2016, listOf(18, 10765))
    const val CREATOR_ID = 999L
    private val TV_GENRES = mapOf(18 to "Drame", 10765 to "Science-Fiction & Fantastique")
    private val ALL_SHOWS: List<FakeShow> get() = SHOWS + GUARDIANS

    private fun showSummaryJson(show: FakeShow): String =
        """{"id":${show.id},"name":"${show.name}","original_name":"${show.name}","first_air_date":"${show.year}-09-01",""" +
            """"origin_country":["FR"],"poster_path":"/tv${show.id}.jpg","overview":"Synopsis de ${show.name}.","vote_average":8.1,""" +
            """"vote_count":700,"genre_ids":${show.genreIds},"original_language":"fr","popularity":12.0}"""

    private fun showPageJson(shows: List<FakeShow>): String =
        """{"page":1,"total_pages":${if (shows.isEmpty()) 0 else 1},"total_results":${shows.size},"results":[${shows.joinToString(",", transform = ::showSummaryJson)}]}"""

    /** Nouvelles saisons simulées : dernière saison diffusée de chaque série (3 par défaut, série finie). */
    val airedSeasons = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private fun showDetailsJson(show: FakeShow): String {
        val genres = show.genreIds.joinToString(",") { """{"id":$it,"name":"${TV_GENRES[it]}"}""" }
        val aired = airedSeasons[show.id] ?: 3
        val status = if (airedSeasons.containsKey(show.id)) "Returning Series" else "Ended"
        return """{"id":${show.id},"name":"${show.name}","original_name":"${show.name}","first_air_date":"${show.year}-09-01",""" +
            """"last_air_date":"${show.year + 3}-06-01","origin_country":["FR"],"poster_path":"/tv${show.id}.jpg",""" +
            """"overview":"Synopsis de ${show.name}.","vote_average":8.3,"vote_count":1500,"episode_run_time":[52],""" +
            """"number_of_seasons":${maxOf(3, aired)},"status":"$status","genres":[$genres],"original_language":"fr","popularity":20.0,""" +
            """"last_episode_to_air":{"season_number":$aired,"episode_number":1,"air_date":"${show.year + aired}-03-01"},""" +
            """"created_by":[{"id":$CREATOR_ID,"name":"Créatrice Test"}],""" +
            """"seasons":[{"season_number":0,"name":"Épisodes spéciaux","episode_count":2,"air_date":"${show.year}-12-24"},""" +
            """{"season_number":1,"name":"Saison 1","episode_count":8,"air_date":"${show.year}-09-01"},""" +
            """{"season_number":2,"name":"Saison 2","episode_count":8,"air_date":"${show.year + 1}-09-01"},""" +
            """{"season_number":3,"name":"Saison 3","episode_count":6,"air_date":"${show.year + 3}-03-01"}],""" +
            """"aggregate_credits":{"cast":[{"id":$ACTRESS_ID,"name":"Actrice Principale","profile_path":null,"order":0,""" +
            """"roles":[{"character":"Capitaine","episode_count":22}]}],"crew":[]},""" +
            """"keywords":{"results":[{"id":42,"name":"enquête"}]},""" +
            """"videos":{"results":[{"key":"tv${show.id}","site":"YouTube","type":"Trailer","iso_639_1":"fr","official":true}]}}"""
    }

    private fun tvCreditsJson(id: Long?): String? = when (id) {
        CREATOR_ID -> """{"cast":[],"crew":[""" + ALL_SHOWS.joinToString(",") { show ->
            """{"id":${show.id},"name":"${show.name}","first_air_date":"${show.year}-09-01","origin_country":["FR"],""" +
                """"poster_path":"/tv${show.id}.jpg","vote_count":${show.id - 900},"popularity":1.0,"job":"Creator","genre_ids":${show.genreIds}}"""
        } + "]}"
        ACTRESS_ID -> """{"cast":[${showSummaryJson(GUARDIANS).dropLast(1)},"character":"Capitaine"},""" +
            """{"id":4000,"name":"Le Grand Plateau","first_air_date":"2010-01-01","origin_country":["FR"],"genre_ids":[10767],"character":"Elle-même"}],"crew":[]}"""
        else -> null
    }

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
                // Séries
                path == "/3/genre/tv/list" -> response(200, TV_GENRES.entries.joinToString(prefix = """{"genres":[""", postfix = "]}") { (id, name) -> """{"id":$id,"name":"$name"}""" })
                path == "/3/trending/tv/week" || path == "/3/discover/tv" ||
                    (path.startsWith("/3/tv/") && (path.endsWith("/recommendations") || path.endsWith("/similar"))) -> response(200, showPageJson(SHOWS))
                path == "/3/search/tv" -> {
                    val query = url.queryParameter("query").orEmpty()
                    response(200, showPageJson(ALL_SHOWS.filter { it.name.contains(query, ignoreCase = true) }))
                }
                path.startsWith("/3/tv/") && path.endsWith("/watch/providers") -> response(200, """{"id":0,"results":{}}""")
                path.startsWith("/3/tv/") && path.endsWith("/external_ids") -> {
                    val id = path.removePrefix("/3/tv/").removeSuffix("/external_ids").toLongOrNull() ?: 0
                    response(200, """{"id":$id,"tvdb_id":${90_000 + id},"imdb_id":null}""")
                }
                path.startsWith("/3/tv/") -> {
                    val show = ALL_SHOWS.firstOrNull { it.id.toString() == path.removePrefix("/3/tv/") }
                    if (show == null) response(404, """{"success":false,"status_code":34}""") else response(200, showDetailsJson(show))
                }
                path.startsWith("/3/person/") && path.endsWith("/tv_credits") ->
                    tvCreditsJson(path.removePrefix("/3/person/").removeSuffix("/tv_credits").toLongOrNull())?.let { response(200, it) }
                        ?: response(404, """{"success":false}""")
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
                path.startsWith("/3/person/") -> {
                    val id = path.removePrefix("/3/person/").toLongOrNull()
                    val person = personJson(id) ?: if (id == CREATOR_ID) """{"id":$CREATOR_ID,"name":"Créatrice Test","known_for_department":"Writing"}""" else null
                    // Filmographie séries ajoutée sur demande (append_to_response=tv_credits).
                    val withTv = if (person != null && url.queryParameter("append_to_response").orEmpty().contains("tv_credits")) {
                        person.dropLast(1) + ""","tv_credits":${tvCreditsJson(id) ?: """{"cast":[],"crew":[]}"""}}"""
                    } else {
                        person
                    }
                    withTv?.let { response(200, it) } ?: response(404, """{"success":false}""")
                }
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
    /** Notifications de nouvelles saisons envoyées pendant le test. */
    val seasonNotices = java.util.Collections.synchronizedList(ArrayList<io.github.deedg.cineswipe.data.series.SeasonNotice>())

    private fun testConfig() = AppConfig(
        tmdbBaseUrl = FakeTmdbServer.baseUrl,
        cipher = { PlainCipher },
        homelabScheduler = { HomelabScheduler.None },
        requestGraceMs = 0,
        // Les tests vérifient le parcours sans clé : pas de clé intégrée.
        builtinTmdbKey = null,
        updateUrl = null,
        seasonNotifier = { context -> io.github.deedg.cineswipe.data.series.SeasonNotifier { notices -> seasonNotices += notices } },
    )

    override val scheduleBackgroundWork: Boolean = false

    override fun createContainer(): AppContainer = AppContainer(this, testConfig())

    override fun createSeriesContainer(): AppContainer = AppContainer(this, testConfig(), kind = MediaKind.SERIES)
}
