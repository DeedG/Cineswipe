package io.github.deedg.cineswipe.data.homelab

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import java.net.URLDecoder

@Serializable
data class SeerrStatusDto(val version: String? = null)

@Serializable
data class SeerrUserDto(
    val id: Long = 0,
    val displayName: String? = null,
    val email: String? = null,
    val username: String? = null,
    val plexUsername: String? = null,
    val jellyfinUsername: String? = null,
) {
    val label: String?
        get() = listOf(displayName, username, plexUsername, jellyfinUsername, email).firstOrNull { !it.isNullOrBlank() }
}

/** Réglages publics : modes de connexion proposés par ce Seerr. */
@Serializable
data class SeerrPublicSettingsDto(
    val applicationTitle: String? = null,
    val localLogin: Boolean = true,
    val mediaServerLogin: Boolean = true,
    /** 1 Plex, 2 Jellyfin, 3 Emby, 4 non configuré. */
    val mediaServerType: Int = 4,
)

@Serializable
data class SeerrPageInfoDto(
    val pages: Int = 1,
    val page: Int = 1,
    val results: Int = 0,
)

@Serializable
data class SeerrRequestsDto(
    val pageInfo: SeerrPageInfoDto = SeerrPageInfoDto(),
    val results: List<SeerrRequestDto> = emptyList(),
)

@Serializable
data class SeerrRequestDto(
    val id: Long = 0,
    /** 1 en attente d'approbation, 2 approuvée, 3 refusée, 4 échec, 5 terminée. */
    val status: Int = 0,
    val type: String? = null,
    val media: SeerrMediaDto? = null,
)

@Serializable
data class SeerrMediaDto(
    val tmdbId: Long? = null,
    val mediaType: String? = null,
    /** 1 inconnu, 2 en attente, 3 en cours, 4 partiellement disponible, 5 disponible. */
    val status: Int = 0,
)

@Serializable
data class SeerrMediaPageDto(
    val pageInfo: SeerrPageInfoDto = SeerrPageInfoDto(),
    val results: List<SeerrMediaDto> = emptyList(),
)

@Serializable
data class SeerrRadarrServerDto(
    val id: Int,
    val name: String? = null,
    val is4k: Boolean = false,
    val isDefault: Boolean = false,
    val activeProfileId: Int? = null,
    val activeDirectory: String? = null,
)

@Serializable
data class SeerrRadarrDetailsDto(
    val profiles: List<RadarrQualityProfileDto> = emptyList(),
    val rootFolders: List<RadarrRootFolderDto> = emptyList(),
)

/** Résultat d'une demande : nouvelle demande, ou film déjà demandé ou disponible. */
data class SeerrRequestResult(val requestId: Long, val alreadyRequested: Boolean)

/**
 * Seerr (successeur d'Overseerr et Jellyseerr), API `/api/v1`. Deux façons de s'identifier :
 * la clé API (en-tête `X-Api-Key`, compte administrateur) ou la session d'un utilisateur
 * (cookie `connect.sid` obtenu à la connexion), pour que ses demandes soient à son nom.
 */
class SeerrClient(private val http: HomelabHttp) {
    private val service = HomelabService.SEERR

    /** En-têtes d'authentification d'un service configuré. */
    fun headers(config: ServiceConfig): Map<String, String> {
        val secret = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé ou session absente")
        return if (config.usesSeerrSession) sessionHeaders(config.headerMap(), secret) else config.headerMap() + ("X-Api-Key" to secret)
    }

    private fun sessionHeaders(extra: Map<String, String>, cookies: String): Map<String, String> {
        val xsrf = cookieValue(cookies, XSRF_COOKIE)
        // Jeton anti-CSRF renvoyé en en-tête, si Seerr a cette protection activée.
        return extra + ("Cookie" to cookies) + (if (xsrf != null) mapOf("X-XSRF-TOKEN" to xsrf) else emptyMap())
    }

    suspend fun status(url: HttpUrl, extraHeaders: Map<String, String>): SeerrStatusDto =
        http.decode(service, SeerrStatusDto.serializer(), http.get(service, url, "api/v1/status", extraHeaders))

    /** Utilisateur de la clé ou de la session : vérifie l'accès. */
    suspend fun me(url: HttpUrl, authHeaders: Map<String, String>): SeerrUserDto =
        http.decode(service, SeerrUserDto.serializer(), http.get(service, url, "api/v1/auth/me", authHeaders))

    suspend fun publicSettings(url: HttpUrl, extraHeaders: Map<String, String>): SeerrPublicSettingsDto =
        http.decode(service, SeerrPublicSettingsDto.serializer(), http.get(service, url, "api/v1/settings/public", extraHeaders))

    /**
     * Connexion d'un utilisateur : [identifier] est l'e-mail (compte Seerr) ou l'identifiant
     * Jellyfin, [secret] le mot de passe ou, pour Plex, le jeton du compte. Le mot de passe n'est
     * pas gardé : seule la session l'est.
     */
    suspend fun login(url: HttpUrl, extraHeaders: Map<String, String>, mode: SeerrAuthMode, identifier: String, secret: String): SeerrSession {
        // Première requête : récupère les cookies anti-CSRF éventuels et vérifie le mode de connexion.
        val publicReply = http.exchange(service, "GET", url, "api/v1/settings/public", extraHeaders)
        val settings = http.decode(service, SeerrPublicSettingsDto.serializer(), publicReply.body)
        val enabled = when (mode) {
            SeerrAuthMode.LOCAL -> settings.localLogin
            SeerrAuthMode.JELLYFIN -> settings.mediaServerLogin && (settings.mediaServerType == JELLYFIN_TYPE || settings.mediaServerType == EMBY_TYPE)
            SeerrAuthMode.PLEX -> settings.mediaServerLogin && settings.mediaServerType == PLEX_TYPE
            SeerrAuthMode.API_KEY -> true
        }
        if (!enabled) throw HomelabException(service, HomelabException.Kind.LOGIN_DISABLED, "Mode de connexion désactivé : $mode")

        var cookies = mergeCookies("", publicReply.headers)
        val (path, body) = when (mode) {
            SeerrAuthMode.LOCAL -> "api/v1/auth/local" to buildJsonObject {
                put("email", identifier.trim())
                put("password", secret)
            }
            SeerrAuthMode.JELLYFIN -> "api/v1/auth/jellyfin" to buildJsonObject {
                put("username", identifier.trim())
                put("password", secret)
            }
            SeerrAuthMode.PLEX -> "api/v1/auth/plex" to buildJsonObject { put("authToken", secret) }
            SeerrAuthMode.API_KEY -> throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Pas de connexion avec une clé API")
        }
        val reply = http.exchange(service, "POST", url, path, sessionHeaders(extraHeaders, cookies), body = body.toString())
        cookies = mergeCookies(cookies, reply.headers)
        if (cookieValue(cookies, SESSION_COOKIE) == null) {
            throw HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, "Pas de cookie de session")
        }
        val user = http.decode(service, SeerrUserDto.serializer(), reply.body)
        return SeerrSession(cookies = cookies, userId = user.id, displayName = user.label)
    }

    /** Serveurs Radarr déclarés dans Seerr, avec leurs profils de qualité et dossiers. */
    suspend fun targets(url: HttpUrl, authHeaders: Map<String, String>): List<SeerrTarget> {
        val servers = http.decode(service, ListSerializer(SeerrRadarrServerDto.serializer()), http.get(service, url, "api/v1/service/radarr", authHeaders))
        return servers.map { server ->
            val details = runCatching {
                http.decode(service, SeerrRadarrDetailsDto.serializer(), http.get(service, url, "api/v1/service/radarr/${server.id}", authHeaders))
            }.getOrDefault(SeerrRadarrDetailsDto())
            SeerrTarget(
                id = server.id,
                name = server.name?.takeIf { it.isNotBlank() } ?: "Radarr ${server.id}",
                is4k = server.is4k,
                isDefault = server.isDefault,
                activeProfileId = server.activeProfileId,
                activeDirectory = server.activeDirectory,
                profiles = details.profiles,
                rootFolders = details.rootFolders,
            )
        }
    }

    suspend fun request(config: ServiceConfig, tmdbId: Long): SeerrRequestResult {
        val url = baseUrl(config)
        val body = buildJsonObject {
            put("mediaType", "movie")
            put("mediaId", tmdbId)
            // Options choisies dans les réglages ; sinon Seerr applique ses valeurs par défaut.
            config.targetServerId?.let {
                put("serverId", it)
                put("is4k", config.targetServer4k)
            }
            config.qualityProfileId?.let { put("profileId", it) }
            config.rootFolder?.let { put("rootFolder", it) }
        }.toString()
        return try {
            val created = http.decode(service, SeerrRequestDto.serializer(), http.post(service, url, "api/v1/request", headers(config), body))
            SeerrRequestResult(created.id, alreadyRequested = false)
        } catch (error: HomelabException) {
            // 409 : demande déjà faite, ou film déjà disponible.
            if (error.kind == HomelabException.Kind.CONFLICT) SeerrRequestResult(0L, alreadyRequested = true) else throw error
        }
    }

    suspend fun deleteRequest(config: ServiceConfig, requestId: Long) {
        http.delete(service, baseUrl(config), "api/v1/request/$requestId", headers(config))
    }

    /** Demandes de films (toutes avec la clé API, celles de l'utilisateur avec sa session). */
    suspend fun movieRequests(config: ServiceConfig): List<SeerrRequestDto> {
        val url = baseUrl(config)
        val all = ArrayList<SeerrRequestDto>()
        var skip = 0
        while (true) {
            val page = http.decode(
                service,
                SeerrRequestsDto.serializer(),
                http.get(
                    service,
                    url,
                    "api/v1/request",
                    headers(config),
                    mapOf("take" to PAGE_SIZE.toString(), "skip" to skip.toString(), "filter" to "all", "sort" to "added"),
                ),
            )
            page.results.filterTo(all) { it.type == null || it.type == "movie" }
            skip += page.results.size
            if (page.results.size < PAGE_SIZE || skip >= page.pageInfo.results) break
        }
        return all
    }

    /** Films que Seerr sait disponibles sur le serveur média. */
    suspend fun availableMovies(config: ServiceConfig): Set<Long> {
        val url = baseUrl(config)
        val ids = HashSet<Long>()
        var skip = 0
        while (true) {
            val page = http.decode(
                service,
                SeerrMediaPageDto.serializer(),
                http.get(
                    service,
                    url,
                    "api/v1/media",
                    headers(config),
                    mapOf("take" to PAGE_SIZE.toString(), "skip" to skip.toString(), "filter" to "available"),
                ),
            )
            page.results.forEach { media ->
                val id = media.tmdbId
                if (id != null && (media.mediaType == null || media.mediaType == "movie") && media.status == MEDIA_AVAILABLE) ids += id
            }
            skip += page.results.size
            if (page.results.size < PAGE_SIZE || skip >= page.pageInfo.results) break
        }
        return ids
    }

    private fun baseUrl(config: ServiceConfig): HttpUrl =
        HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)

    companion object {
        const val PAGE_SIZE = 100
        const val REQUEST_PENDING = 1
        const val REQUEST_APPROVED = 2
        const val MEDIA_PENDING = 2
        const val MEDIA_PROCESSING = 3
        const val MEDIA_PARTIAL = 4
        const val MEDIA_AVAILABLE = 5
        const val PLEX_TYPE = 1
        const val JELLYFIN_TYPE = 2
        const val EMBY_TYPE = 3
        private const val SESSION_COOKIE = "connect.sid"
        private const val XSRF_COOKIE = "XSRF-TOKEN"

        /** Ajoute (ou remplace) les cookies posés par une réponse ; format « nom=valeur; nom2=valeur2 ». */
        fun mergeCookies(current: String, headers: Headers): String {
            val cookies = LinkedHashMap<String, String>()
            current.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { cookies[it.substringBefore('=')] = it.substringAfter('=') }
            headers.values("Set-Cookie").forEach { header ->
                val pair = header.substringBefore(';').trim()
                if (pair.contains('=')) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun cookieValue(cookies: String, name: String): String? =
            cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotEmpty() }
    }
}
