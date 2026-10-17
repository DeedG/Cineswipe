package io.github.deedg.cineswipe.data.tmdb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Monde des séries : l'appli parle à TMDB comme pour les films, et cet intercepteur traduit.
 *
 * - Requêtes : `movie/…` devient `tv/…`, la découverte, la recherche, les tendances et les genres
 *   passent sur leurs équivalents séries, les dates de sortie deviennent des dates de première
 *   diffusion, et la fiche demande le casting de toutes les saisons (`aggregate_credits`).
 * - Réponses : une série reçoit les champs d'un film (`title`, `original_title`, `release_date`,
 *   `runtime` = durée d'un épisode) ; ses créateurs prennent la place du réalisateur ; les
 *   mots-clés et les filmographies des personnes reprennent la forme des films.
 *
 * Les champs propres aux séries (saisons, statut de diffusion) sont gardés tels quels.
 */
class TmdbSeriesAdapter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val byPerson = personDiscover(request.url)
        val url = byPerson?.let { personCreditsUrl(request.url, it) } ?: translateUrl(request.url)
        val response = chain.proceed(request.newBuilder().url(url).build())
        if (!response.isSuccessful) return response
        val text = response.body.string()
        val translated = runCatching {
            if (byPerson != null) personPage(text, byPerson) else translateBody(url, text)
        }.getOrDefault(text)
        return response.newBuilder().body(translated.toResponseBody(JSON)).build()
    }

    /** Découverte des séries d'une personne : `discover/tv` ne filtre pas par personne, on passe par sa filmographie. */
    data class PersonDiscover(val personId: Long, val asCrew: Boolean, val page: Int, val minVotes: Int)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Au-delà, un final (souvent rallongé) ne donne pas la durée d'un épisode. */
        private const val MAX_FINALE_MINUTES = 70

        /** Job TMDB des créateurs d'une série, rendus comme « réalisateurs ». */
        const val DIRECTOR = "Director"

        /** Genres des émissions de plateau et d'information : pas des séries à recommander. */
        private val TALK_GENRES = setOf(10763, 10767)
        private val SELF = Regex("""\b(self|himself|herself|themselves|lui-même|elle-même)\b""", RegexOption.IGNORE_CASE)

        fun personDiscover(url: HttpUrl): PersonDiscover? {
            val segments = url.pathSegments
            if (segments.size < 2 || segments[segments.size - 2] != "discover" || segments.last() != "movie") return null
            val crew = url.queryParameter("with_crew")?.toLongOrNull()
            val cast = url.queryParameter("with_cast")?.toLongOrNull()
            val id = crew ?: cast ?: return null
            return PersonDiscover(
                personId = id,
                asCrew = crew != null,
                page = url.queryParameter("page")?.toIntOrNull() ?: 1,
                minVotes = url.queryParameter("vote_count.gte")?.toIntOrNull() ?: 0,
            )
        }

        fun personCreditsUrl(url: HttpUrl, discover: PersonDiscover): HttpUrl {
            val segments = url.pathSegments.dropLast(2)
            val builder = url.newBuilder().encodedPath("/").query(null)
            (segments + listOf("person", discover.personId.toString(), "tv_credits")).forEach { builder.addPathSegment(it) }
            // La clé (v3) est déjà dans l'adresse : elle suit.
            listOf("language", "api_key").forEach { name -> url.queryParameter(name)?.let { builder.addQueryParameter(name, it) } }
            return builder.build()
        }

        /** Filmographie séries d'une personne rendue comme une page de découverte (les plus votées d'abord). */
        fun personPage(body: String, discover: PersonDiscover): String {
            val credits = person(buildJsonObject { put("tv_credits", TmdbApi.json.parseToJsonElement(body)) })["movie_credits"] as JsonObject
            val entries = (credits[if (discover.asCrew) "crew" else "cast"] as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .filter { !discover.asCrew || (it["job"] as? JsonPrimitive)?.contentOrNull == DIRECTOR }
                .distinctBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
                .filter { ((it["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0) >= discover.minVotes }
                .sortedByDescending { (it["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0 }
            val results = if (discover.page == 1) entries else emptyList()
            val page = buildJsonObject {
                put("page", JsonPrimitive(discover.page))
                put("results", JsonArray(results))
                put("total_pages", JsonPrimitive(1))
                put("total_results", JsonPrimitive(entries.size))
            }
            return showFields(page).toString()
        }

        fun translateUrl(url: HttpUrl): HttpUrl {
            val segments = url.pathSegments
            val builder = url.newBuilder()
            val rewritten = segments.mapIndexed { index, segment ->
                val previous = segments.getOrNull(index - 1)
                val next = segments.getOrNull(index + 1)
                when {
                    // movie/{id}… → tv/{id}…
                    segment == "movie" && next?.toLongOrNull() != null -> "tv"
                    // discover/movie, search/movie, trending/movie/week, genre/movie/list
                    segment == "movie" && previous in setOf("discover", "search", "trending", "genre") -> "tv"
                    else -> segment
                }
            }
            if (rewritten != segments) {
                builder.encodedPath("/")
                rewritten.forEach { builder.addPathSegment(it) }
            }
            url.queryParameterNames.forEach { name ->
                val values = url.queryParameterValues(name)
                val (newName, keep) = when (name) {
                    "primary_release_date.gte" -> "first_air_date.gte" to true
                    "primary_release_date.lte" -> "first_air_date.lte" to true
                    "year" -> "first_air_date_year" to true
                    "with_release_type", "region", "include_video" -> name to false
                    else -> name to true
                }
                builder.removeAllQueryParameters(name)
                if (!keep) return@forEach
                values.forEach { value ->
                    val newValue = when (name) {
                        "sort_by" -> value?.replace("primary_release_date", "first_air_date")?.replace("release_date", "first_air_date")
                        "append_to_response" -> value?.split(',')?.joinToString(",") { part ->
                            when (part) {
                                "credits" -> "aggregate_credits"
                                "movie_credits" -> "tv_credits"
                                "release_dates" -> "content_ratings"
                                else -> part
                            }
                        }
                        else -> value
                    }
                    builder.addQueryParameter(newName, newValue)
                }
            }
            return builder.build()
        }

        fun translateBody(url: HttpUrl, body: String): String {
            val root = TmdbApi.json.parseToJsonElement(body)
            val segments = url.pathSegments
            val tvIndex = segments.indexOf("tv")
            val isDetails = tvIndex >= 0 && segments.getOrNull(tvIndex + 1)?.toLongOrNull() != null && segments.size == tvIndex + 2
            val isPerson = segments.contains("person") && segments.lastOrNull()?.toLongOrNull() != null
            var translated = showFields(root)
            if (isDetails && translated is JsonObject) translated = details(translated)
            if (isPerson && translated is JsonObject) translated = person(translated)
            if (segments.contains("find") && translated is JsonObject) {
                translated = JsonObject(translated + ("movie_results" to (translated["tv_results"] ?: JsonArray(emptyList()))))
            }
            return translated.toString()
        }

        /** Partout dans la réponse, une série reçoit les champs de titre et de date d'un film. */
        private fun showFields(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::showFields))
            is JsonObject -> {
                val children = element.mapValues { (_, value) -> showFields(value) }
                val isShow = "first_air_date" in element || "origin_country" in element || "number_of_seasons" in element
                if (!isShow) {
                    JsonObject(children)
                } else {
                    JsonObject(
                        children + buildMap {
                            if ("title" !in element) element["name"]?.let { put("title", it) }
                            if ("original_title" !in element) element["original_name"]?.let { put("original_title", it) }
                            if ("release_date" !in element) element["first_air_date"]?.let { put("release_date", it) }
                        },
                    )
                }
            }
            else -> element
        }

        /** Fiche d'une série : durée d'un épisode, créateurs, casting de toutes les saisons, mots-clés. */
        private fun details(show: JsonObject): JsonObject {
            val fields = show.toMutableMap()
            val runtime = episodeRuntime(show)
            fields["runtime"] = runtime?.let(::JsonPrimitive) ?: JsonNull

            val creators = (show["created_by"] as? JsonArray).orEmpty().mapNotNull { creator ->
                val person = creator as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    person["id"]?.let { put("id", it) }
                    person["name"]?.let { put("name", it) }
                    put("job", DIRECTOR)
                }
            }
            val credits = (show["aggregate_credits"] as? JsonObject) ?: (show["credits"] as? JsonObject)
            if (credits != null || creators.isNotEmpty()) {
                val cast = (credits?.get("cast") as? JsonArray).orEmpty().mapNotNull { member ->
                    val person = member as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        person["id"]?.let { put("id", it) }
                        person["name"]?.let { put("name", it) }
                        person["profile_path"]?.let { put("profile_path", it) }
                        person["order"]?.let { put("order", it) }
                    }
                }
                fields["credits"] = buildJsonObject {
                    put("cast", JsonArray(cast))
                    put("crew", JsonArray(creators))
                }
            }
            (show["keywords"] as? JsonObject)?.let { keywords ->
                if ("keywords" !in keywords) fields["keywords"] = buildJsonObject { put("keywords", keywords["results"] ?: JsonArray(emptyList())) }
            }
            return JsonObject(fields)
        }

        /**
         * Durée d'un épisode : la médiane des durées annoncées par TMDB ; sinon celle du dernier
         * (ou du prochain) épisode, s'il est ordinaire. Un final ou un épisode spécial, souvent bien
         * plus long, ne compte pas (Stranger Things affichait 129 min).
         */
        fun episodeRuntime(show: JsonObject): Int? {
            val announced = (show["episode_run_time"] as? JsonArray).orEmpty().mapNotNull { it.intValue()?.takeIf { minutes -> minutes > 0 } }.sorted()
            if (announced.isNotEmpty()) return announced[announced.size / 2]
            val episodes = listOf("last_episode_to_air", "next_episode_to_air").mapNotNull { show[it] as? JsonObject }
            fun runtime(episode: JsonObject) = episode["runtime"]?.intValue()?.takeIf { it > 0 }
            fun standard(episode: JsonObject) = (episode["episode_type"] as? JsonPrimitive)?.contentOrNull.let { it == null || it == "standard" }
            // Un épisode ordinaire d'abord ; sinon un final, s'il garde une durée d'épisode (≤ 70 min).
            return episodes.filter(::standard).firstNotNullOfOrNull(::runtime)
                ?: episodes.firstNotNullOfOrNull { episode -> runtime(episode)?.takeIf { it <= MAX_FINALE_MINUTES } }
        }

        /** Personne : ses séries à la place de ses films, sans les plateaux télé où elle joue son propre rôle. */
        private fun person(person: JsonObject): JsonObject {
            // Sans filmographie séries, aucune œuvre (jamais ses films dans le monde des séries).
            val credits = person["tv_credits"] as? JsonObject ?: buildJsonObject {}
            val cast = (credits["cast"] as? JsonArray).orEmpty().filter { entry ->
                val credit = entry as? JsonObject ?: return@filter false
                val genres = (credit["genre_ids"] as? JsonArray).orEmpty().mapNotNull { it.intValue() }
                val character = (credit["character"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                genres.none { it in TALK_GENRES } && !SELF.containsMatchIn(character)
            }
            val crew = (credits["crew"] as? JsonArray).orEmpty().map { entry ->
                val credit = entry as? JsonObject ?: return@map entry
                val job = (credit["job"] as? JsonPrimitive)?.contentOrNull
                if (job == "Creator") JsonObject(credit + ("job" to JsonPrimitive(DIRECTOR))) else credit
            }
            return JsonObject(person + ("movie_credits" to buildJsonObject {
                put("cast", JsonArray(cast))
                put("crew", JsonArray(crew))
            }))
        }

        private fun JsonElement.intValue(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
