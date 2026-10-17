package io.github.deedg.cineswipe.data.homelab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import java.time.Instant

@Serializable
data class JellyfinPublicInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class JellyfinAuthResultDto(
    @SerialName("User") val user: JellyfinUserDto? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
)

@Serializable
data class JellyfinItemsDto(
    @SerialName("Items") val items: List<JellyfinItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
data class JellyfinItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String?> = emptyMap(),
    @SerialName("UserData") val userData: JellyfinUserDataDto? = null,
)

@Serializable
data class JellyfinUserDataDto(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    /** Série : épisodes pas encore vus, et part vue (0 à 100). */
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
)

@Serializable
data class JellyfinEpisodeDto(
    @SerialName("ParentIndexNumber") val season: Int? = null,
    @SerialName("UserData") val userData: JellyfinUserDataDto? = null,
)

@Serializable
data class JellyfinEpisodesDto(
    @SerialName("Items") val items: List<JellyfinEpisodeDto> = emptyList(),
)

data class JellyfinLogin(val token: String, val userId: String, val userName: String?)

/** Jellyfin 10.8 et plus : en-tête `Authorization: MediaBrowser … Token="…"`. */
class JellyfinClient(
    private val http: HomelabHttp,
    private val deviceId: String,
    private val appVersion: String,
) {
    private val service = HomelabService.JELLYFIN

    fun authorization(token: String?): String = buildString {
        append("MediaBrowser Client=\"CineSwipe\", Device=\"Android\", DeviceId=\"")
        append(deviceId)
        append("\", Version=\"")
        append(appVersion)
        append('"')
        if (token != null) {
            append(", Token=\"")
            append(token)
            append('"')
        }
    }

    private fun headers(extra: Map<String, String>, token: String?): Map<String, String> =
        extra + ("Authorization" to authorization(token))

    /** Nom et version du serveur (sans authentification). */
    suspend fun publicInfo(url: HttpUrl, extraHeaders: Map<String, String>): JellyfinPublicInfoDto =
        http.decode(service, JellyfinPublicInfoDto.serializer(), http.get(service, url, "System/Info/Public", headers(extraHeaders, null)))

    /** Connexion par identifiant : renvoie un jeton propre à cet utilisateur. Le mot de passe n'est pas gardé. */
    suspend fun login(url: HttpUrl, extraHeaders: Map<String, String>, username: String, password: String): JellyfinLogin {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val result = http.decode(
            service,
            JellyfinAuthResultDto.serializer(),
            http.post(service, url, "Users/AuthenticateByName", headers(extraHeaders, null), body),
        )
        val token = result.accessToken ?: throw HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, "Jeton absent")
        val user = result.user ?: throw HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, "Utilisateur absent")
        return JellyfinLogin(token = token, userId = user.id, userName = user.name)
    }

    /** Utilisateur du jeton (connexion par identifiant). */
    suspend fun me(url: HttpUrl, extraHeaders: Map<String, String>, token: String): JellyfinUserDto =
        http.decode(service, JellyfinUserDto.serializer(), http.get(service, url, "Users/Me", headers(extraHeaders, token)))

    /** Utilisateurs du serveur (clé API d'administration). */
    suspend fun users(url: HttpUrl, extraHeaders: Map<String, String>, token: String): List<JellyfinUserDto> =
        http.decode(service, ListSerializer(JellyfinUserDto.serializer()), http.get(service, url, "Users", headers(extraHeaders, token)))

    /** Tous les films visibles par l'utilisateur choisi, avec l'état « vu ». */
    suspend fun movies(config: ServiceConfig): List<ServerMovie> = items(config, "Movie").map { it.toServerMovie() }

    /**
     * Séries visibles par l'utilisateur, avec leur avancement : finie si tout est vu, sinon la
     * saison en cours (lue épisode par épisode, seulement pour les séries commencées).
     */
    suspend fun series(config: ServiceConfig): List<ServerMovie> {
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val token = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé absente")
        val userId = config.userId ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Utilisateur absent")
        return items(config, "Series").map { item ->
            val data = item.userData ?: JellyfinUserDataDto()
            val percentage = data.playedPercentage ?: 0.0
            val finished = data.played || (data.unplayedItemCount == 0 && percentage > 0.0)
            val started = finished || percentage > 0.0 || data.lastPlayedDate != null
            val season = if (started && !finished && item.id != null) {
                runCatching { SeriesWatch.currentSeason(episodes(url, config.headerMap(), token, userId, item.id)) }.getOrNull()
            } else {
                null
            }
            item.toServerMovie().copy(watched = started, finished = finished, currentSeason = season)
        }
    }

    private suspend fun episodes(url: HttpUrl, extra: Map<String, String>, token: String, userId: String, seriesId: String): List<SeasonWatch> =
        http.decode(
            service,
            JellyfinEpisodesDto.serializer(),
            http.get(service, url, "Shows/$seriesId/Episodes", headers(extra, token), mapOf("userId" to userId, "EnableUserData" to "true")),
        ).items
            .groupBy { it.season ?: 0 }
            .map { (season, episodes) -> SeasonWatch(season, episodes.size, episodes.count { it.userData?.played == true }) }

    private suspend fun items(config: ServiceConfig, type: String): List<JellyfinItemDto> {
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val token = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé absente")
        val userId = config.userId ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Utilisateur absent")
        val movies = ArrayList<JellyfinItemDto>()
        var start = 0
        while (true) {
            val page = http.decode(
                service,
                JellyfinItemsDto.serializer(),
                http.get(
                    service,
                    url,
                    "Items",
                    headers(config.headerMap(), token),
                    mapOf(
                        "userId" to userId,
                        "IncludeItemTypes" to type,
                        "Recursive" to "true",
                        "Fields" to "ProviderIds",
                        "EnableUserData" to "true",
                        "StartIndex" to start.toString(),
                        "Limit" to PAGE_SIZE.toString(),
                    ),
                ),
            )
            movies += page.items
            start += page.items.size
            val total = page.totalRecordCount
            if (page.items.isEmpty() || (total != null && start >= total) || (total == null && page.items.size < PAGE_SIZE)) break
        }
        return movies
    }

    private fun JellyfinItemDto.toServerMovie(): ServerMovie {
        val tmdb = providerIds.entries.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.trim()?.toLongOrNull()
        return ServerMovie(
            tmdbId = tmdb?.takeIf { it > 0 },
            title = name?.trim().orEmpty(),
            year = productionYear,
            watched = userData?.played == true,
            lastWatchedAt = userData?.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            itemId = id,
        )
    }

    companion object {
        private const val PAGE_SIZE = 500
        /**
         * Fiche d'un film dans l'interface web de Jellyfin, à partir de l'adresse telle que saisie
         * (« 100.64.0.1:8096 » devient « http://100.64.0.1:8096/web/#/details?id=… »). Null si
         * l'adresse est invalide. L'appli Android officielle n'accepte aucun lien : la fiche s'ouvre
         * dans le navigateur.
         */
        fun webUrl(serverUrl: String, itemId: String): String? {
            val base = HomelabUrls.parse(serverUrl) ?: return null
            return base.toString().trimEnd('/') + "/web/#/details?id=$itemId"
        }
    }
}
