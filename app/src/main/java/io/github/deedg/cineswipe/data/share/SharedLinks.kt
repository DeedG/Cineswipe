package io.github.deedg.cineswipe.data.share

import io.github.deedg.cineswipe.data.importer.ImportMatcher
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ce qu'on reconnaît dans un texte partagé depuis une autre appli. */
data class SharedQuery(
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    /** Lien Letterboxd (fiche ou lien court boxd.it), dont la page donne l'identifiant TMDB. */
    val letterboxdUrl: String? = null,
    val title: String? = null,
    val year: Int? = null,
)

/**
 * Lecture d'un partage : liens TMDB, IMDb, Letterboxd, SensCritique, JustWatch, Trakt, ou
 * simplement « Titre (année) » au milieu d'un texte.
 */
object SharedLinks {
    private val TMDB = Regex("themoviedb\\.org/movie/(\\d+)", RegexOption.IGNORE_CASE)
    private val IMDB = Regex("\\b(tt\\d{7,9})\\b")
    private val LETTERBOXD_FILM = Regex("https?://(?:www\\.)?letterboxd\\.com/(?:[^/\\s]+/)?film/([a-z0-9-]+)/?", RegexOption.IGNORE_CASE)
    private val LETTERBOXD_SHORT = Regex("https?://boxd\\.it/[A-Za-z0-9]+", RegexOption.IGNORE_CASE)
    private val SLUG_SITES = listOf(
        Regex("senscritique\\.com/film/([a-z0-9_-]+)/\\d+", RegexOption.IGNORE_CASE),
        Regex("justwatch\\.com/[a-z]{2}/(?:film|movie|filme|pelicula)/([a-z0-9-]+)", RegexOption.IGNORE_CASE),
        Regex("trakt\\.tv/movies/([a-z0-9-]+)", RegexOption.IGNORE_CASE),
    )
    private val URL = Regex("https?://\\S+")
    private val TITLE_YEAR = Regex("^(.{1,120}?)\\s*[\\(\\[]((?:18|19|20)\\d{2})[\\)\\]]")
    private val SLUG_YEAR = Regex("^(.+)-((?:18|19|20)\\d{2})$")

    /** Mots des applis qui accompagnent le titre (« sur Letterboxd », « - IMDb »…). */
    private val NOISE = listOf(
        Regex("\\s+(?:on|sur)\\s+(?:Letterboxd|IMDb|TMDB|SensCritique|Allociné|AlloCiné|JustWatch|Trakt)\\b.*$", RegexOption.IGNORE_CASE),
        Regex("\\s*[-|–]\\s*(?:IMDb|Letterboxd|SensCritique|Allociné|AlloCiné|JustWatch|Trakt|The Movie Database \\(TMDB\\)|TMDB)\\s*$", RegexOption.IGNORE_CASE),
        Regex("^(?:Check out|Regarde|Découvre|Watch|J['’]ai regardé|I watched)\\s+", RegexOption.IGNORE_CASE),
        Regex("[\"«»“”]"),
    )

    fun parse(text: String): SharedQuery {
        val raw = text.trim()
        TMDB.find(raw)?.let { return SharedQuery(tmdbId = it.groupValues[1].toLong()) }
        IMDB.find(raw)?.let { return SharedQuery(imdbId = it.groupValues[1]) }
        val withoutUrls = raw.replace(URL, " ").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (title, year) = titleAndYear(withoutUrls)
        LETTERBOXD_FILM.find(raw)?.let { match ->
            val (slugTitle, slugYear) = fromSlug(match.groupValues[1])
            return SharedQuery(letterboxdUrl = match.value, title = title ?: slugTitle, year = year ?: slugYear)
        }
        LETTERBOXD_SHORT.find(raw)?.let { return SharedQuery(letterboxdUrl = it.value, title = title, year = year) }
        SLUG_SITES.firstNotNullOfOrNull { it.find(raw) }?.let { match ->
            val (slugTitle, slugYear) = fromSlug(match.groupValues[1])
            return SharedQuery(title = title ?: slugTitle, year = year ?: slugYear)
        }
        return SharedQuery(title = title, year = year)
    }

    private fun titleAndYear(lines: List<String>): Pair<String?, Int?> {
        lines.forEach { line ->
            TITLE_YEAR.find(clean(line))?.let { match ->
                val title = clean(match.groupValues[1]).takeIf { it.isNotBlank() }
                if (title != null) return title to match.groupValues[2].toInt()
            }
        }
        val first = lines.firstOrNull()?.let(::clean)?.takeIf { it.length in 1..120 }
        return first to null
    }

    private fun clean(text: String): String = NOISE.fold(text) { acc, noise -> acc.replace(noise, "") }.trim().trimEnd(':', '-', '–', '|').trim()

    /** « the-dark-knight » → The Dark Knight ; « dune-2021 » → Dune, 2021. */
    fun fromSlug(slug: String): Pair<String, Int?> {
        val cleaned = slug.trim('-', '_').lowercase(Locale.ROOT).replace('_', '-')
        val match = SLUG_YEAR.find(cleaned)
        val base = match?.groupValues?.get(1) ?: cleaned
        val year = match?.groupValues?.get(2)?.toIntOrNull()
        return base.split('-').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } to year
    }

    /** Identifiant TMDB dans une page Letterboxd (`data-tmdb-id="…"`). */
    fun tmdbIdInLetterboxdPage(html: String): Long? =
        Regex("data-tmdb-id=\"(\\d+)\"").find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("themoviedb\\.org/movie/(\\d+)").find(html)?.groupValues?.get(1)?.toLongOrNull()
}

/** Résultat de la lecture d'un partage. */
sealed interface SharedResult {
    /** Un seul film, sûr : on ouvre sa fiche. */
    data class Found(val tmdbId: Long) : SharedResult

    /** Plusieurs possibilités : l'utilisateur choisit. */
    data class Choices(val query: String, val movies: List<TmdbMovieSummaryDto>) : SharedResult

    data class NotFound(val query: String) : SharedResult

    data object NeedsKey : SharedResult

    data object Offline : SharedResult
}

/** Retrouve sur TMDB le film d'un partage. */
class SharedRepository(private val tmdb: TmdbRepository) {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()
    }

    suspend fun resolve(text: String): SharedResult = try {
        resolveQuery(SharedLinks.parse(text), text)
    } catch (error: CancellationException) {
        throw error
    } catch (error: TmdbException.MissingKey) {
        SharedResult.NeedsKey
    } catch (error: TmdbException.InvalidKey) {
        SharedResult.NeedsKey
    } catch (error: TmdbException.Network) {
        SharedResult.Offline
    }

    private suspend fun resolveQuery(query: SharedQuery, text: String): SharedResult {
        query.tmdbId?.let { return SharedResult.Found(it) }
        query.imdbId?.let { imdb ->
            val found = try {
                tmdb.findByImdb(imdb).filterNot { it.adult }
            } catch (error: TmdbException.Http) {
                emptyList()
            }
            found.singleOrNull()?.let { return SharedResult.Found(it.id) }
            if (found.isNotEmpty()) return SharedResult.Choices(imdb, found)
        }
        query.letterboxdUrl?.let { url -> letterboxdTmdbId(url)?.let { return SharedResult.Found(it) } }
        val title = query.title?.takeIf { it.isNotBlank() } ?: return SharedResult.NotFound(text.trim().take(80))
        val results = try {
            tmdb.search(title, 1, query.year).results.ifEmpty { if (query.year != null) tmdb.search(title, 1).results else emptyList() }
        } catch (error: TmdbException.Http) {
            emptyList()
        }.filterNot { it.adult }
        val label = listOfNotNull(title, query.year?.let { "($it)" }).joinToString(" ")
        if (results.isEmpty()) return SharedResult.NotFound(label)
        val best = ImportMatcher.best(title, query.year, results)
        // Titre et année identiques : pas de doute. Sinon, on laisse choisir parmi les premiers résultats.
        val sure = best != null && query.year != null && ImportMatcher.key(best.title.orEmpty()).let { key ->
            key == ImportMatcher.key(title) || ImportMatcher.key(best.originalTitle.orEmpty()) == ImportMatcher.key(title)
        }
        if (sure && best != null) return SharedResult.Found(best.id)
        if (results.size == 1) return SharedResult.Found(results.first().id)
        val ordered = listOfNotNull(best) + results.filter { it.id != best?.id }
        return SharedResult.Choices(label, ordered.take(MAX_CHOICES))
    }

    /** La page Letterboxd donne l'identifiant TMDB du film (Letterboxd s'appuie sur TMDB). */
    private suspend fun letterboxdTmdbId(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().let(SharedLinks::tmdbIdInLetterboxdPage)
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_CHOICES = 8
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) CineSwipe"
    }
}
