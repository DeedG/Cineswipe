package io.github.deedg.cineswipe.data.tmdb

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Tag
import java.util.concurrent.TimeUnit

/**
 * Clé TMDB d'une requête. Chaque appel la reçoit en paramètre (`@Tag`) : on peut ainsi tester
 * une nouvelle clé avant de l'enregistrer, et aucune requête ne part avec une clé périmée.
 */
class TmdbAuth(val key: String) {
    /** Jeton de lecture v4 (« eyJ… ») envoyé en en-tête, sinon clé v3 en paramètre d'URL. */
    val isBearerToken: Boolean get() = key.startsWith("eyJ")

    override fun toString(): String = "TmdbAuth(••••)"
}

/** Ajoute la clé de la requête : `api_key=` pour une clé v3, `Authorization: Bearer` pour un jeton v4. */
class TmdbAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val auth = request.tag(TmdbAuth::class.java) ?: return chain.proceed(request)
        val authorized = if (auth.isBearerToken) {
            request.newBuilder().header("Authorization", "Bearer ${auth.key}").build()
        } else {
            val url = request.url.newBuilder().addQueryParameter("api_key", auth.key).build()
            request.newBuilder().url(url).build()
        }
        return chain.proceed(authorized)
    }
}

/**
 * TMDB répond 429 quand on va trop vite (imports, recos qui chargent beaucoup de fiches d'un coup) :
 * on attend le délai demandé (Retry-After, sinon 1 s, 2 s, 4 s, 8 s) et on réessaie, pour toutes
 * les requêtes de l'appli. Après 4 essais, le 429 remonte comme une erreur ordinaire.
 */
class TmdbRateLimitInterceptor(private val sleep: (Long) -> Unit = { Thread.sleep(it) }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0
        while (response.code == TOO_MANY_REQUESTS && attempt < MAX_RETRIES && !chain.call().isCanceled()) {
            val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000)?.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
                ?: (BASE_DELAY_MS shl attempt)
            response.close()
            try {
                sleep(wait)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Requête TMDB annulée")
            }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    companion object {
        const val TOO_MANY_REQUESTS = 429
        const val MAX_RETRIES = 4
        const val BASE_DELAY_MS = 1_000L
        const val MIN_WAIT_MS = 500L
        const val MAX_WAIT_MS = 10_000L
    }
}

/** API TMDB v3 (https://developer.themoviedb.org/reference). */
interface TmdbApi {

    @GET("authentication")
    suspend fun authentication(@Tag auth: TmdbAuth): TmdbAuthenticationDto

    @GET("genre/movie/list")
    suspend fun genres(
        @Query("language") language: String,
        @Tag auth: TmdbAuth,
    ): TmdbGenresDto

    @GET("movie/{id}")
    suspend fun movie(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String?,
        @Query("include_video_language") includeVideoLanguage: String?,
        @Tag auth: TmdbAuth,
    ): TmdbMovieDetailsDto

    @GET("movie/{id}/recommendations")
    suspend fun recommendations(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Tag auth: TmdbAuth,
    ): TmdbPageDto

    @GET("movie/{id}/similar")
    suspend fun similar(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Tag auth: TmdbAuth,
    ): TmdbPageDto

    @GET("trending/movie/week")
    suspend fun trending(
        @Query("language") language: String,
        @Query("page") page: Int,
        @Tag auth: TmdbAuth,
    ): TmdbPageDto

    @GET("collection/{id}")
    suspend fun collection(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Tag auth: TmdbAuth,
    ): TmdbCollectionDto

    @GET("discover/movie")
    suspend fun discover(
        @QueryMap params: Map<String, String>,
        @Tag auth: TmdbAuth,
    ): TmdbPageDto

    @GET("search/movie")
    suspend fun search(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Query("include_adult") includeAdult: Boolean,
        @Tag auth: TmdbAuth,
        /** Année de sortie (toutes dates de sortie confondues), pour départager les homonymes. */
        @Query("year") year: Int?,
    ): TmdbPageDto

    /** Personnes (acteurs, réalisateurs…) d'après leur nom. */
    @GET("search/person")
    suspend fun searchPeople(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Query("include_adult") includeAdult: Boolean,
        @Tag auth: TmdbAuth,
    ): TmdbPersonPageDto

    /** Une personne (acteur, réalisateur) et ses films. */
    @GET("person/{id}")
    suspend fun person(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String,
        @Tag auth: TmdbAuth,
    ): TmdbPersonDto

    /** Où regarder un film (abonnement, location, achat), par pays. */
    @GET("movie/{id}/watch/providers")
    suspend fun watchProviders(
        @Path("id") id: Long,
        @Tag auth: TmdbAuth,
    ): TmdbWatchProvidersDto

    /** Identifiants externes (IMDb, TVDB pour une série). */
    @GET("movie/{id}/external_ids")
    suspend fun externalIds(
        @Path("id") id: Long,
        @Tag auth: TmdbAuth,
    ): TmdbExternalIdsDto

    /** Film d'après un identifiant externe (IMDb « tt… »). */
    @GET("find/{id}")
    suspend fun find(
        @Path("id") id: String,
        @Query("external_source") externalSource: String,
        @Query("language") language: String,
        @Tag auth: TmdbAuth,
    ): TmdbFindDto

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3/"

        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(TmdbRateLimitInterceptor())
            .addInterceptor(TmdbAuthInterceptor())
            .build()

        fun create(baseUrl: String, client: OkHttpClient): TmdbApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }
}
