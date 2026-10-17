package io.github.deedg.cineswipe.data.homelab

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

@Serializable
data class SonarrSeriesDto(
    val id: Long = 0,
    val tvdbId: Long = 0,
    /** Donné par Sonarr v4 ; absent en v3 (on passe alors par TVDB). */
    val tmdbId: Long = 0,
    val title: String? = null,
    val year: Int? = null,
    val monitored: Boolean = false,
    val statistics: SonarrStatisticsDto? = null,
)

@Serializable
data class SonarrStatisticsDto(
    val episodeFileCount: Int = 0,
    val episodeCount: Int = 0,
)

@Serializable
data class SonarrLanguageProfileDto(val id: Int)

/** Résultat d'un ajout : nouvelle série, ou série déjà présente dans Sonarr. */
data class SonarrAddResult(val sonarrId: Long, val alreadyPresent: Boolean)

/**
 * Sonarr v3 et v4 (`/api/v3`, en-tête `X-Api-Key`) : mêmes profils de qualité et dossiers que
 * Radarr. Les séries y sont repérées par leur identifiant TVDB.
 */
class SonarrClient(private val http: HomelabHttp) {
    private val service = HomelabService.SONARR

    private fun headers(extra: Map<String, String>, key: String): Map<String, String> = extra + ("X-Api-Key" to key)

    suspend fun status(url: HttpUrl, extraHeaders: Map<String, String>, key: String): RadarrStatusDto =
        http.decode(service, RadarrStatusDto.serializer(), http.get(service, url, "api/v3/system/status", headers(extraHeaders, key)))

    suspend fun qualityProfiles(url: HttpUrl, extraHeaders: Map<String, String>, key: String): List<RadarrQualityProfileDto> =
        http.decode(service, ListSerializer(RadarrQualityProfileDto.serializer()), http.get(service, url, "api/v3/qualityprofile", headers(extraHeaders, key)))

    suspend fun rootFolders(url: HttpUrl, extraHeaders: Map<String, String>, key: String): List<RadarrRootFolderDto> =
        http.decode(service, ListSerializer(RadarrRootFolderDto.serializer()), http.get(service, url, "api/v3/rootfolder", headers(extraHeaders, key)))

    /** Toutes les séries de Sonarr : avec des épisodes téléchargés = disponible, surveillée sans fichier = demandée. */
    suspend fun series(config: ServiceConfig): List<SonarrSeriesDto> {
        val (url, key) = endpoint(config)
        return http.decode(service, ListSerializer(SonarrSeriesDto.serializer()), http.get(service, url, "api/v3/series", headers(config.headerMap(), key)))
    }

    /**
     * Ajoute une série, toutes saisons surveillées (recherche des épisodes selon les réglages).
     * La fiche vient de la recherche de Sonarr (`series/lookup?term=tvdb:…`).
     */
    suspend fun addSeries(config: ServiceConfig, tvdbId: Long, seasons: List<Int>? = null): SonarrAddResult {
        val (url, key) = endpoint(config)
        val profile = config.qualityProfileId
            ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Profil de qualité absent")
        val root = config.rootFolder
            ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Dossier racine absent")
        val headers = headers(config.headerMap(), key)

        val existing = series(config).firstOrNull { it.tvdbId == tvdbId }
        if (existing != null) return SonarrAddResult(existing.id, alreadyPresent = true)

        val lookup = http.decode(
            service,
            JsonElement.serializer(),
            http.get(service, url, "api/v3/series/lookup", headers, mapOf("term" to "tvdb:$tvdbId")),
        )
        val found = ((lookup as? JsonArray)?.firstOrNull() ?: lookup as? JsonObject) as? JsonObject
            ?: throw HomelabException(service, HomelabException.Kind.NOT_FOUND, "Série $tvdbId introuvable dans Sonarr")
        // Sonarr v3 exige un profil de langue (supprimé en v4) : le premier, s'il y en a.
        val languageProfile = runCatching {
            http.decode(service, ListSerializer(SonarrLanguageProfileDto.serializer()), http.get(service, url, "api/v3/languageprofile", headers)).firstOrNull()?.id
        }.getOrNull()
        val chosen = seasons?.takeIf { it.isNotEmpty() }?.toSet()
        val body = buildJsonObject {
            found.forEach { (name, value) ->
                when {
                    name == "id" -> Unit
                    // Saisons choisies : seules elles sont surveillées (et cherchées).
                    name == "seasons" && chosen != null && value is JsonArray -> put(
                        name,
                        JsonArray(
                            value.map { season ->
                                val obj = season as? JsonObject ?: return@map season
                                val number = obj["seasonNumber"]?.jsonPrimitive?.content?.toIntOrNull()
                                JsonObject(obj + ("monitored" to JsonPrimitive(number != null && number in chosen)))
                            },
                        ),
                    )
                    else -> put(name, value)
                }
            }
            put("qualityProfileId", JsonPrimitive(profile))
            languageProfile?.let { put("languageProfileId", JsonPrimitive(it)) }
            put("rootFolderPath", JsonPrimitive(root))
            put("monitored", JsonPrimitive(true))
            put("seasonFolder", JsonPrimitive(true))
            put(
                "addOptions",
                buildJsonObject {
                    // « unknown » : Sonarr garde la surveillance saison par saison donnée ci-dessus.
                    put("monitor", JsonPrimitive(if (chosen == null) "all" else "unknown"))
                    put("searchForMissingEpisodes", JsonPrimitive(config.searchOnAdd))
                },
            )
        }
        return try {
            val created = http.decode(service, JsonElement.serializer(), http.post(service, url, "api/v3/series", headers, body.toString()))
            SonarrAddResult(created.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: 0L, alreadyPresent = false)
        } catch (error: HomelabException) {
            // « This series has already been added » si elle vient d'être ajoutée.
            if (error.code == 400 && error.message.orEmpty().contains("already", ignoreCase = true)) {
                SonarrAddResult(0L, alreadyPresent = true)
            } else {
                throw error
            }
        }
    }

    /** Annule un ajout : retire la série de Sonarr sans toucher aux fichiers. */
    suspend fun deleteSeries(config: ServiceConfig, sonarrId: Long) {
        val (url, key) = endpoint(config)
        http.delete(
            service,
            url,
            "api/v3/series/$sonarrId",
            headers(config.headerMap(), key),
            mapOf("deleteFiles" to "false", "addImportListExclusion" to "false"),
        )
    }

    private fun endpoint(config: ServiceConfig): Pair<HttpUrl, String> {
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val key = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé API absente")
        return url to key
    }
}
