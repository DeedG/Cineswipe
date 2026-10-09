package io.github.deedg.cineswipe.data.homelab

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

@Serializable
data class RadarrStatusDto(
    val appName: String? = null,
    val instanceName: String? = null,
    val version: String? = null,
)

@Serializable
data class RadarrQualityProfileDto(val id: Int, val name: String? = null)

@Serializable
data class RadarrRootFolderDto(
    val id: Int? = null,
    val path: String,
    val accessible: Boolean = true,
    val freeSpace: Long? = null,
)

@Serializable
data class RadarrMovieDto(
    val id: Long = 0,
    val tmdbId: Long = 0,
    val title: String? = null,
    val year: Int? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
)

/** Résultat d'un ajout : nouveau film, ou film déjà présent dans Radarr. */
data class RadarrAddResult(val radarrId: Long, val alreadyPresent: Boolean)

/** Radarr v3 et plus (`/api/v3`, en-tête `X-Api-Key`). */
class RadarrClient(private val http: HomelabHttp) {
    private val service = HomelabService.RADARR

    private fun headers(extra: Map<String, String>, key: String): Map<String, String> = extra + ("X-Api-Key" to key)

    suspend fun status(url: HttpUrl, extraHeaders: Map<String, String>, key: String): RadarrStatusDto =
        http.decode(service, RadarrStatusDto.serializer(), http.get(service, url, "api/v3/system/status", headers(extraHeaders, key)))

    suspend fun qualityProfiles(url: HttpUrl, extraHeaders: Map<String, String>, key: String): List<RadarrQualityProfileDto> =
        http.decode(service, ListSerializer(RadarrQualityProfileDto.serializer()), http.get(service, url, "api/v3/qualityprofile", headers(extraHeaders, key)))

    suspend fun rootFolders(url: HttpUrl, extraHeaders: Map<String, String>, key: String): List<RadarrRootFolderDto> =
        http.decode(service, ListSerializer(RadarrRootFolderDto.serializer()), http.get(service, url, "api/v3/rootfolder", headers(extraHeaders, key)))

    /** Tous les films de Radarr : avec fichier = disponible, surveillé sans fichier = demandé. */
    suspend fun movies(config: ServiceConfig): List<RadarrMovieDto> {
        val (url, key) = endpoint(config)
        return http.decode(service, ListSerializer(RadarrMovieDto.serializer()), http.get(service, url, "api/v3/movie", headers(config.headerMap(), key)))
    }

    /**
     * Ajoute un film (recherche lancée selon les réglages). La fiche vient de la recherche de
     * Radarr lui-même (`movie/lookup/tmdb`), complétée du profil de qualité et du dossier choisis.
     */
    suspend fun addMovie(config: ServiceConfig, tmdbId: Long): RadarrAddResult {
        val (url, key) = endpoint(config)
        val profile = config.qualityProfileId
            ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Profil de qualité absent")
        val root = config.rootFolder
            ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Dossier racine absent")
        val headers = headers(config.headerMap(), key)

        val existing = http.decode(
            service,
            ListSerializer(RadarrMovieDto.serializer()),
            http.get(service, url, "api/v3/movie", headers, mapOf("tmdbId" to tmdbId.toString())),
        ).firstOrNull { it.tmdbId == tmdbId }
        if (existing != null) return RadarrAddResult(existing.id, alreadyPresent = true)

        val lookup = http.decode(
            service,
            JsonElement.serializer(),
            http.get(service, url, "api/v3/movie/lookup/tmdb", headers, mapOf("tmdbId" to tmdbId.toString())),
        )
        val movie = (lookup as? JsonObject)
            ?: throw HomelabException(service, HomelabException.Kind.NOT_FOUND, "Film $tmdbId introuvable dans Radarr")
        val body = buildJsonObject {
            movie.forEach { (name, value) -> if (name != "id") put(name, value) }
            put("qualityProfileId", JsonPrimitive(profile))
            put("rootFolderPath", JsonPrimitive(root))
            put("monitored", JsonPrimitive(true))
            put("minimumAvailability", JsonPrimitive("released"))
            put("addOptions", buildJsonObject { put("searchForMovie", JsonPrimitive(config.searchOnAdd)) })
        }
        return try {
            val created = http.decode(service, JsonElement.serializer(), http.post(service, url, "api/v3/movie", headers, body.toString()))
            val id = created.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: 0L
            RadarrAddResult(id, alreadyPresent = false)
        } catch (error: HomelabException) {
            // Radarr répond 400 « This movie has already been added » si le film vient d'être ajouté.
            if (error.code == 400 && error.message.orEmpty().contains("already", ignoreCase = true)) {
                RadarrAddResult(0L, alreadyPresent = true)
            } else {
                throw error
            }
        }
    }

    /** Annule un ajout : retire le film de Radarr sans toucher aux fichiers. */
    suspend fun deleteMovie(config: ServiceConfig, radarrId: Long) {
        val (url, key) = endpoint(config)
        http.delete(
            service,
            url,
            "api/v3/movie/$radarrId",
            headers(config.headerMap(), key),
            mapOf("deleteFiles" to "false", "addImportExclusion" to "false"),
        )
    }

    private fun endpoint(config: ServiceConfig): Pair<HttpUrl, String> {
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val key = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé API absente")
        return url to key
    }
}
