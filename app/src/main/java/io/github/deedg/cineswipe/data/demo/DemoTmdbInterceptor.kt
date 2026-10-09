package io.github.deedg.cineswipe.data.demo

import io.github.deedg.cineswipe.data.tmdb.TmdbApi
import io.github.deedg.cineswipe.data.tmdb.TmdbAuthenticationDto
import io.github.deedg.cineswipe.data.tmdb.TmdbCollectionDto
import io.github.deedg.cineswipe.data.tmdb.TmdbGenreDto
import io.github.deedg.cineswipe.data.tmdb.TmdbGenresDto
import io.github.deedg.cineswipe.data.tmdb.TmdbKnownForDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieDetailsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPageDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonCreditDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonCreditsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonHitDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonPageDto
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.min

/**
 * TMDB simulé pour la démo : les requêtes de l'appli reçoivent leurs réponses du catalogue
 * embarqué, sans jamais partir sur le réseau. Fiches, recommandations (films proches par les
 * genres, le réalisateur et la saga), découverte avec ses filtres, tendances, recherche par
 * titre, acteurs et réalisateurs (recherche et filmographie) et sagas. Tout le reste répond « introuvable ».
 */
class DemoTmdbInterceptor(private val catalog: DemoCatalog) : Interceptor {
    private val json = TmdbApi.json
    private val films = catalog.films
    private val byId = films.associateBy { it.id }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = route(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message(if (body != null) "OK" else "Not Found")
            .body((body ?: NOT_FOUND).toResponseBody(JSON_TYPE))
            .build()
    }

    private fun route(url: HttpUrl): String? {
        val path = url.pathSegments.filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "3") it.drop(1) else it }
        return when {
            path == listOf("authentication") -> encode(TmdbAuthenticationDto.serializer(), TmdbAuthenticationDto(success = true))
            path == listOf("genre", "movie", "list") ->
                encode(TmdbGenresDto.serializer(), TmdbGenresDto(catalog.genres.map { TmdbGenreDto(it.id, it.name) }))
            path.size == 2 && path[0] == "movie" ->
                byId[path[1].toLongOrNull()]?.let { encode(TmdbMovieDetailsDto.serializer(), catalog.details(it)) }
            path.size == 3 && path[0] == "movie" && (path[2] == "recommendations" || path[2] == "similar") ->
                byId[path[1].toLongOrNull()]?.let { page(similarTo(it), url) }
            path == listOf("trending", "movie", "week") -> page(films.sortedByDescending { it.popularity ?: 0.0 }, url)
            path.size == 2 && path[0] == "collection" -> path[1].toLongOrNull()?.let(::collection)
            path == listOf("discover", "movie") -> page(discover(url), url)
            path == listOf("search", "movie") -> page(search(url.queryParameter("query").orEmpty()), url)
            path == listOf("search", "person") -> encode(TmdbPersonPageDto.serializer(), searchPeople(url.queryParameter("query").orEmpty()))
            path.size == 2 && path[0] == "person" -> path[1].toLongOrNull()?.let(::person)?.let { encode(TmdbPersonDto.serializer(), it) }
            else -> null
        }
    }

    /**
     * Films proches : genres en commun (indice de Jaccard), même réalisateur, même saga, et un
     * peu la notoriété. Les 20 plus proches.
     */
    internal fun similarTo(film: DemoFilm): List<DemoFilm> {
        val genres = film.genres.toSet()
        val directors = film.directors.toSet()
        return films.asSequence()
            .filter { it.id != film.id }
            .map { other ->
                val shared = other.genres.count { it in genres }.toDouble()
                val union = (genres + other.genres).size.coerceAtLeast(1)
                var score = shared / union
                if (other.directors.any { it in directors }) score += SAME_DIRECTOR
                if (film.collection != null && other.collection == film.collection) score += SAME_SAGA
                score += FAME * min(1.0, (other.votes ?: 0) / FAMOUS_VOTES)
                other to score
            }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(RECOMMENDATIONS)
            .map { it.first }
            .toList()
    }

    /** `/discover/movie` : genres (« , » = tous, « | » = l'un d'eux), personnes, langue, note, votes, années, tri. */
    internal fun discover(url: HttpUrl): List<DemoFilm> {
        fun param(name: String) = url.queryParameter(name)?.takeIf { it.isNotBlank() }
        val genres = param("with_genres")
        val crew = param("with_crew")?.toLongOrNull()
        val cast = param("with_cast")?.toLongOrNull()
        if (param("with_keywords") != null) return emptyList()
        val language = param("with_original_language")
        val minRating = param("vote_average.gte")?.toDoubleOrNull()
        val minVotes = param("vote_count.gte")?.toIntOrNull()
        val maxVotes = param("vote_count.lte")?.toIntOrNull()
        val yearMin = param("primary_release_date.gte")?.take(4)?.toIntOrNull()
        val yearMax = param("primary_release_date.lte")?.take(4)?.toIntOrNull()
        val runtimeMin = param("with_runtime.gte")?.toIntOrNull()
        val runtimeMax = param("with_runtime.lte")?.toIntOrNull()
        val selected = films.filter { film ->
            (genres == null || matchesGenres(film, genres)) &&
                (crew == null || film.directors.any { DemoCatalog.personId(it) == crew }) &&
                (cast == null || film.cast.any { DemoCatalog.personId(it) == cast }) &&
                (language == null || film.language == language) &&
                (minRating == null || (film.rating ?: 0.0) >= minRating) &&
                (minVotes == null || (film.votes ?: 0) >= minVotes) &&
                (maxVotes == null || (film.votes ?: 0) <= maxVotes) &&
                (yearMin == null || (film.year ?: 0) >= yearMin) &&
                (yearMax == null || (film.year ?: Int.MAX_VALUE) <= yearMax) &&
                (runtimeMin == null || (film.runtime ?: 0) >= runtimeMin) &&
                (runtimeMax == null || (film.runtime ?: Int.MAX_VALUE) <= runtimeMax)
        }
        return when (param("sort_by")) {
            "vote_count.desc" -> selected.sortedByDescending { it.votes ?: 0 }
            "vote_average.desc" -> selected.sortedByDescending { it.rating ?: 0.0 }
            "primary_release_date.desc" -> selected.sortedByDescending { it.year ?: 0 }
            else -> selected.sortedByDescending { it.popularity ?: 0.0 }
        }
    }

    private fun matchesGenres(film: DemoFilm, filter: String): Boolean = if ('|' in filter) {
        filter.split('|').mapNotNull { it.trim().toIntOrNull() }.any { it in film.genres }
    } else {
        filter.split(',').mapNotNull { it.trim().toIntOrNull() }.all { it in film.genres }
    }

    /** Recherche par titre (français ou original), sans tenir compte des accents ni de la casse. */
    internal fun search(query: String): List<DemoFilm> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return emptyList()
        return films
            .filter { normalize(it.title).contains(wanted) || normalize(it.originalTitle.orEmpty()).contains(wanted) }
            .sortedByDescending { it.votes ?: 0 }
    }

    /** Tous les acteurs et réalisateurs du catalogue, par identifiant. */
    private val people: Map<Long, String> by lazy {
        films.flatMap { it.directors + it.cast }.distinct().associateBy { DemoCatalog.personId(it) }
    }

    /** Recherche de personnes par leur nom, comme `/search/person` : les plus présentes d'abord. */
    internal fun searchPeople(query: String): TmdbPersonPageDto {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return TmdbPersonPageDto()
        val hits = people.filterValues { normalize(it).contains(wanted) }.keys.mapNotNull(::person)
            .sortedByDescending { it.credits.cast.size + it.credits.crew.size }
            .take(PAGE_SIZE)
            .map { dto ->
                TmdbPersonHitDto(
                    id = dto.id,
                    name = dto.name,
                    knownFor = dto.knownFor,
                    works = (dto.credits.crew + dto.credits.cast).distinctBy { it.id }.take(3)
                        .map { TmdbKnownForDto(id = it.id, title = it.title, mediaType = "movie") },
                )
            }
        return TmdbPersonPageDto(page = 1, results = hits, totalPages = 1)
    }

    /** Une personne du catalogue et ses films (joués, réalisés), comme `/person/{id}`. */
    internal fun person(id: Long): TmdbPersonDto? {
        val name = people[id] ?: return null
        fun credit(film: DemoFilm, job: String?) = TmdbPersonCreditDto(
            id = film.id,
            title = film.title,
            originalTitle = film.originalTitle,
            releaseDate = film.year?.let { "$it-01-01" },
            posterPath = film.poster,
            voteAverage = film.rating,
            voteCount = film.votes,
            popularity = film.popularity,
            job = job,
        )
        val cast = films.filter { name in it.cast }.map { credit(it, null) }
        val crew = films.filter { name in it.directors }.map { credit(it, "Director") }
        return TmdbPersonDto(
            id = id,
            name = name,
            knownFor = if (crew.size >= cast.size) "Directing" else "Acting",
            credits = TmdbPersonCreditsDto(cast = cast, crew = crew),
        )
    }

    private fun collection(id: Long): String? {
        val parts = films.filter { it.collection == id }.sortedBy { it.year ?: 0 }
        if (parts.isEmpty()) return null
        return encode(TmdbCollectionDto.serializer(), TmdbCollectionDto(id = id, parts = parts.map(catalog::summary)))
    }

    private fun page(list: List<DemoFilm>, url: HttpUrl): String {
        val number = url.queryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val totalPages = ceil(list.size / PAGE_SIZE.toDouble()).toInt()
        val results = list.drop((number - 1) * PAGE_SIZE).take(PAGE_SIZE).map(catalog::summary)
        return encode(TmdbPageDto.serializer(), TmdbPageDto(page = number, results = results, totalPages = totalPages, totalResults = list.size))
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val NOT_FOUND = """{"success":false,"status_code":34,"status_message":"Introuvable dans la démo."}"""
        val DIACRITICS = Regex("\\p{Mn}+")
        const val PAGE_SIZE = 20
        const val RECOMMENDATIONS = 20
        const val SAME_DIRECTOR = 0.6
        const val SAME_SAGA = 1.5
        const val FAME = 0.15
        const val FAMOUS_VOTES = 40_000.0
        const val MIN_SIMILARITY = 0.25
    }
}
