package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.model.TmdbGenre
import io.github.deedg.cineswipe.data.tmdb.TmdbApi
import io.github.deedg.cineswipe.data.tmdb.TmdbAuth
import io.github.deedg.cineswipe.data.tmdb.TmdbCollectionDto
import io.github.deedg.cineswipe.data.tmdb.TmdbEpisodeDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieDetailsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPageDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonPageDto
import io.github.deedg.cineswipe.data.tmdb.TmdbProviderDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap


/** Dernier épisode diffusé et prochain annoncé d'une série. */
data class ShowEpisodes(val last: TmdbEpisodeDto?, val next: TmdbEpisodeDto?)

/** Erreurs TMDB, traduites en messages par l'interface. */
sealed class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : TmdbException("Clé TMDB absente")
    class InvalidKey(cause: Throwable? = null) : TmdbException("Clé TMDB refusée", cause)
    class Network(cause: Throwable) : TmdbException("TMDB injoignable", cause)
    class Http(val code: Int, cause: Throwable) : TmdbException("Réponse TMDB inattendue ($code)", cause)
}

/** Les offres de streaming changent peu dans la journée. */
private const val PROVIDERS_TTL_MS = 6L * 60 * 60 * 1000

/** Service de streaming, location ou achat (données JustWatch, via TMDB). */
data class WatchProvider(val id: Int, val name: String, val logoPath: String?)

/** Où regarder un film dans un pays. [link] : page TMDB qui renvoie vers chaque service. */
data class WatchProviders(
    val country: String,
    val link: String?,
    val stream: List<WatchProvider>,
    val rent: List<WatchProvider>,
    val buy: List<WatchProvider>,
) {
    val isEmpty: Boolean get() = stream.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** Résultat de la vérification d'une clé avant son enregistrement. */
enum class KeyCheck { VALID, REJECTED, UNREACHABLE }

/** Paramètres de `/discover/movie`. */
data class DiscoverQuery(
    val genreId: Int? = null,
    val minRating: Double? = null,
    val minVotes: Int? = null,
    val maxVotes: Int? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val sortBy: String = "popularity.desc",
    /** Films d'un réalisateur (ou autre membre de l'équipe). */
    val crewId: Long? = null,
    /** Films d'un acteur. */
    val castId: Long? = null,
    /** Films d'un mot-clé TMDB. */
    val keywordId: Long? = null,
    /** Langue originale (« fr »). */
    val originalLanguage: String? = null,
    /** Durée en minutes (bornes incluses). */
    val runtimeMin: Int? = null,
    val runtimeMax: Int? = null,
) {
    fun toParams(language: String, page: Int): Map<String, String> = buildMap {
        put("language", language)
        put("page", page.toString())
        put("sort_by", sortBy)
        put("include_adult", "false")
        genreId?.let { put("with_genres", it.toString()) }
        crewId?.let { put("with_crew", it.toString()) }
        castId?.let { put("with_cast", it.toString()) }
        keywordId?.let { put("with_keywords", it.toString()) }
        originalLanguage?.let { put("with_original_language", it) }
        minRating?.let { put("vote_average.gte", it.toString()) }
        minVotes?.let { put("vote_count.gte", it.toString()) }
        maxVotes?.let { put("vote_count.lte", it.toString()) }
        yearMin?.let { put("primary_release_date.gte", "$it-01-01") }
        yearMax?.let { put("primary_release_date.lte", "$it-12-31") }
        runtimeMin?.let { put("with_runtime.gte", it.toString()) }
        runtimeMax?.let { put("with_runtime.lte", it.toString()) }
    }

    companion object {
        /** Pépites : très bien notées (≥ 7,2) mais peu connues (150 à 2 500 votes), d'un genre ou non. */
        fun gems(genreId: Int? = null) =
            DiscoverQuery(genreId = genreId, minRating = 7.2, minVotes = 150, maxVotes = 2_500, sortBy = "vote_average.desc")

        /** Grands classiques : les films les plus votés (ceux qu'on a le plus de chances d'avoir vus). */
        fun classics(genreId: Int? = null) = DiscoverQuery(genreId = genreId, minVotes = 1_500, sortBy = "vote_count.desc")
    }
}

/**
 * Accès à TMDB avec la clé et la langue des réglages, et cache des fiches dans la table `movie`.
 * La V4 figeait une fiche incomplète à la moindre erreur réseau ; ici une fiche n'est écrite
 * qu'une fois chargée en entier, et rafraîchie au bout de 30 jours.
 */
class TmdbRepository(
    private val api: TmdbApi,
    private val database: CineSwipeDatabase,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val genreCache = ConcurrentHashMap<String, List<TmdbGenre>>()
    private val genreMutex = Mutex()

    /** Teste une clé sans l'enregistrer. */
    suspend fun validateKey(key: String): KeyCheck {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return KeyCheck.REJECTED
        return try {
            if (api.authentication(TmdbAuth(trimmed)).success) KeyCheck.VALID else KeyCheck.REJECTED
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401 || error.code() == 403) KeyCheck.REJECTED else KeyCheck.UNREACHABLE
        } catch (error: IOException) {
            KeyCheck.UNREACHABLE
        } catch (error: SerializationException) {
            KeyCheck.UNREACHABLE
        }
    }

    /** Genres de films dans la langue des fiches (gardés en mémoire). */
    suspend fun genres(): List<TmdbGenre> {
        val language = settings.language.first()
        genreCache[language]?.let { return it }
        return genreMutex.withLock {
            genreCache[language] ?: call { auth, lang ->
                api.genres(lang, auth).genres.mapNotNull { genre ->
                    TmdbMapper.cleanText(genre.name)?.let { TmdbGenre(genre.id, it) }
                }
            }.also { genreCache[language] = it }
        }
    }

    suspend fun recommendations(tmdbId: Long, page: Int): TmdbPageDto =
        call { auth, language -> api.recommendations(tmdbId, language, page, auth) }

    suspend fun similar(tmdbId: Long, page: Int): TmdbPageDto =
        call { auth, language -> api.similar(tmdbId, language, page, auth) }

    suspend fun trending(page: Int): TmdbPageDto =
        call { auth, language -> api.trending(language, page, auth) }

    suspend fun discover(query: DiscoverQuery, page: Int): TmdbPageDto =
        call { auth, language -> api.discover(query.toParams(language, page), auth) }

    /** Films d'une saga. */
    suspend fun collection(id: Long): TmdbCollectionDto =
        call { auth, language -> api.collection(id, language, auth) }

    suspend fun search(query: String, page: Int, year: Int? = null): TmdbPageDto =
        call { auth, language -> api.search(query, language, page, false, auth, year) }

    /**
     * Où regarder un film dans le pays du téléphone (sinon celui de la langue des fiches) :
     * abonnements (et gratuit), location, achat. Gardé en mémoire 6 h.
     */
    suspend fun watchProviders(tmdbId: Long): WatchProviders {
        val now = clock()
        providerCache[tmdbId]?.takeIf { now - it.first < PROVIDERS_TTL_MS }?.let { return it.second }
        val dto = call { auth, _ -> api.watchProviders(tmdbId, auth) }
        val language = settings.language.first()
        val country = Locale.getDefault().country.takeIf { it.length == 2 && it in dto.results }
            ?: language.substringAfter('-', "").takeIf { it.length == 2 && it in dto.results }
            ?: Locale.getDefault().country.ifBlank { language.substringAfter('-', "FR") }
        val offers = dto.results[country]
        fun List<TmdbProviderDto>.toProviders() = sortedBy { it.priority }.mapNotNull { p ->
            TmdbMapper.cleanText(p.name)?.let { WatchProvider(p.id, it, TmdbMapper.imagePath(p.logoPath)) }
        }
        val result = WatchProviders(
            country = country,
            link = offers?.link,
            stream = (offers?.flatrate.orEmpty() + offers?.free.orEmpty() + offers?.ads.orEmpty()).distinctBy { it.id }.toProviders(),
            rent = offers?.rent.orEmpty().toProviders(),
            buy = offers?.buy.orEmpty().toProviders(),
        )
        providerCache[tmdbId] = now to result
        return result
    }

    /** Une personne et ses films (rôles et postes). */
    suspend fun searchPeople(query: String): TmdbPersonPageDto =
        call { auth, language -> api.searchPeople(query, language, 1, false, auth) }

    suspend fun person(personId: Long): TmdbPersonDto =
        call { auth, language -> api.person(personId, language, "movie_credits", auth) }

    private val providerCache = ConcurrentHashMap<Long, Pair<Long, WatchProviders>>()

    /** Identifiant TVDB d'une série (pour Sonarr), ou null. */
    suspend fun tvdbId(tmdbId: Long): Long? =
        call { auth, _ -> api.externalIds(tmdbId, auth).tvdbId?.takeIf { it > 0 } }

    /** Série d'après son identifiant TVDB (séries de Sonarr v3, qui ne donne pas l'identifiant TMDB). */
    suspend fun findByTvdb(tvdbId: Long): Long? =
        call { auth, language -> api.find(tvdbId.toString(), "tvdb_id", language, auth).movieResults.firstOrNull()?.id }

    /** Films correspondant à un identifiant IMDb (« tt0111161 »). */
    suspend fun findByImdb(imdbId: String): List<TmdbMovieSummaryDto> =
        call { auth, language -> api.find(imdbId, "imdb_id", language, auth).movieResults }

    /**
     * Fiche complète d'un film : celle du cache si elle est à jour, sinon celle de TMDB (mise
     * en cache au passage). Sans clé ou sans réseau, la version du cache est renvoyée telle
     * quelle ; l'erreur n'est levée que s'il n'y en a aucune.
     */
    suspend fun ensureDetails(tmdbId: Long, force: Boolean = false): MovieEntity {
        val cached = database.movieDao().get(tmdbId)
        val language = settings.language.first()
        if (cached != null && !force && !TmdbMapper.needsRefresh(cached, language, clock())) return cached
        return try {
            fetchDetails(tmdbId)
        } catch (error: TmdbException) {
            cached ?: throw error
        }
    }

    /** Recharge la fiche depuis TMDB, sans repli sur le cache : l'erreur est levée telle quelle. */
    suspend fun refreshDetails(tmdbId: Long): MovieEntity = fetchDetails(tmdbId)

    /** Série rechargée depuis TMDB, avec son dernier épisode diffusé et le prochain annoncé. */
    suspend fun refreshShow(tmdbId: Long): Pair<MovieEntity, ShowEpisodes> {
        var episodes = ShowEpisodes(null, null)
        val entity = fetchDetails(tmdbId) { dto -> episodes = ShowEpisodes(dto.lastEpisode, dto.nextEpisode) }
        return entity to episodes
    }

    private suspend fun fetchDetails(tmdbId: Long, onDto: (TmdbMovieDetailsDto) -> Unit = {}): MovieEntity {
        val entity = call { auth, language ->
            val primary = TmdbMapper.primaryLanguage(language)
            val dto = api.movie(tmdbId, language, TmdbMapper.APPEND_TO_RESPONSE, TmdbMapper.videoLanguages(language), auth)
            onDto(dto)
            var overview = TmdbMapper.cleanText(dto.overview)
            var overviewLanguage = primary
            if (overview == null && primary != "en") {
                // Synopsis absent dans la langue des fiches : repli sur l'anglais (153 fiches en V4).
                overview = englishOverview(tmdbId, auth)
                overviewLanguage = "en"
            }
            TmdbMapper.toEntity(
                dto = dto,
                overview = overview,
                overviewLanguage = if (overview == null) TmdbMapper.NO_OVERVIEW else overviewLanguage,
                language = language,
                now = clock(),
            )
        }
        database.movieDao().upsert(entity)
        return entity
    }

    private suspend fun englishOverview(tmdbId: Long, auth: TmdbAuth): String? = try {
        TmdbMapper.cleanText(api.movie(tmdbId, TmdbMapper.FALLBACK_LANGUAGE, null, null, auth).overview)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    /** Appel avec la clé et la langue des réglages ; les erreurs deviennent des [TmdbException]. */
    private suspend fun <T> call(block: suspend (auth: TmdbAuth, language: String) -> T): T {
        val key = settings.tmdbApiKey.first() ?: throw TmdbException.MissingKey()
        val language = settings.language.first()
        return try {
            block(TmdbAuth(key), language)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            throw if (error.code() == 401) TmdbException.InvalidKey(error) else TmdbException.Http(error.code(), error)
        } catch (error: IOException) {
            throw TmdbException.Network(error)
        } catch (error: SerializationException) {
            throw TmdbException.Http(0, error)
        }
    }
}
