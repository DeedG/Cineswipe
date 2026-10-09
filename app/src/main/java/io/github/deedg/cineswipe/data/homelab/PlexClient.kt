package io.github.deedg.cineswipe.data.homelab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

@Serializable
data class PlexRootDto(@SerialName("MediaContainer") val container: PlexRootContainerDto = PlexRootContainerDto())

@Serializable
data class PlexRootContainerDto(
    val friendlyName: String? = null,
    val version: String? = null,
    /** Identifiant du serveur, pour les liens vers un film. */
    val machineIdentifier: String? = null,
)

@Serializable
data class PlexSectionsDto(@SerialName("MediaContainer") val container: PlexSectionsContainerDto = PlexSectionsContainerDto())

@Serializable
data class PlexSectionsContainerDto(@SerialName("Directory") val directories: List<PlexDirectoryDto> = emptyList())

@Serializable
data class PlexDirectoryDto(
    val key: String,
    val title: String? = null,
    val type: String? = null,
)

@Serializable
data class PlexMetadataPageDto(@SerialName("MediaContainer") val container: PlexMetadataContainerDto = PlexMetadataContainerDto())

@Serializable
data class PlexMetadataContainerDto(
    val size: Int? = null,
    val totalSize: Int? = null,
    @SerialName("Metadata") val metadata: List<PlexMetadataDto> = emptyList(),
)

@Serializable
data class PlexMetadataDto(
    val ratingKey: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val viewCount: Int? = null,
    /** Secondes depuis 1970. */
    val lastViewedAt: Long? = null,
    /** Ancien format d'identifiant (agents historiques). */
    val guid: String? = null,
    @SerialName("Guid") val guids: List<PlexGuidDto> = emptyList(),
)

@Serializable
data class PlexGuidDto(val id: String)

data class PlexSection(val key: String, val title: String)

@Serializable
data class PlexMatchesDto(@SerialName("MediaContainer") val container: PlexMatchesContainerDto = PlexMatchesContainerDto())

@Serializable
data class PlexMatchesContainerDto(@SerialName("Metadata") val metadata: List<PlexMatchDto> = emptyList())

@Serializable
data class PlexMatchDto(val slug: String? = null)

@Serializable
data class PlexPinDto(
    val id: Long,
    val code: String,
    val authToken: String? = null,
)

@Serializable
data class PlexResourceDto(
    val name: String = "",
    val provides: String? = null,
    val owned: Boolean = false,
    val accessToken: String? = null,
    val connections: List<PlexConnectionDto> = emptyList(),
)

@Serializable
data class PlexConnectionDto(
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
)

/**
 * Serveur Plex local (port 32400 par défaut). L'API fonctionne sans Plex Pass ; seuls les
 * webhooks l'exigent, et on ne s'en sert pas. Les films « vus » sont ceux du compte du jeton.
 */
class PlexClient(
    private val http: HomelabHttp,
    private val clientId: String,
    /** Adresse de plex.tv (remplacée dans les tests). */
    private val plexTv: HttpUrl = PLEX_TV,
) {
    private val service = HomelabService.PLEX

    private fun headers(extra: Map<String, String>, token: String): Map<String, String> =
        extra + mapOf(
            "X-Plex-Token" to token,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to "CineSwipe",
        )

    /**
     * Serveur qui refuse le http (réglage « Connexions sécurisées : requises ») : son certificat
     * est établi pour « *.<empreinte>.plex.direct ». On en déduit l'adresse sécurisée de la même
     * IP, « https://100-64-1-2.<empreinte>.plex.direct:32400 », résolue sur le téléphone. Null si
     * l'adresse n'est pas une IPv4 ou si le serveur ne présente pas de certificat Plex.
     */
    suspend fun secureUrl(url: HttpUrl): HttpUrl? {
        val ip = url.host.takeIf { IPV4.matches(it) } ?: return null
        val hash = http.certificateNames(url).firstNotNullOfOrNull { PLEX_DIRECT_WILDCARD.matchEntire(it)?.groupValues?.get(1) } ?: return null
        return url.newBuilder()
            .scheme("https")
            .host("${ip.replace('.', '-')}.$hash.plex.direct")
            .port(url.port)
            .build()
    }

    /** Connexion avec un compte Plex, étape 1 : code à faire valider dans le navigateur. */
    suspend fun createPin(): PlexPin {
        val pin = http.decode(
            service,
            PlexPinDto.serializer(),
            http.post(service, plexTv, "api/v2/pins", accountHeaders(null), body = "{}", query = mapOf("strong" to "true")),
        )
        val product = URLEncoder.encode("CineSwipe", "UTF-8")
        return PlexPin(
            id = pin.id,
            code = pin.code,
            url = "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}&context%5Bdevice%5D%5Bproduct%5D=$product",
        )
    }

    /** Étape 2 : jeton du compte une fois le code validé, sinon null. */
    suspend fun pinToken(pinId: Long): String? =
        http.decode(service, PlexPinDto.serializer(), http.get(service, plexTv, "api/v2/pins/$pinId", accountHeaders(null)))
            .authToken?.takeIf { it.isNotBlank() }

    /** Serveurs du compte (les siens et ceux partagés avec lui), avec leur jeton d'accès propre. */
    suspend fun servers(accountToken: String): List<PlexServer> =
        http.decode(
            service,
            ListSerializer(PlexResourceDto.serializer()),
            http.get(service, plexTv, "api/v2/resources", accountHeaders(accountToken), mapOf("includeHttps" to "1", "includeRelay" to "1")),
        )
            .filter { it.provides.orEmpty().split(',').contains("server") }
            .map { resource ->
                PlexServer(
                    name = resource.name,
                    accessToken = resource.accessToken,
                    owned = resource.owned,
                    connections = resource.connections.filter { it.uri.isNotBlank() }.map { PlexConnection(it.uri, it.local, it.relay) },
                )
            }

    private fun accountHeaders(token: String?): Map<String, String> = buildMap {
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Product", "CineSwipe")
        if (token != null) put("X-Plex-Token", token)
    }

    /** Nom et version du serveur ; vérifie aussi le jeton. */
    suspend fun identity(url: HttpUrl, extraHeaders: Map<String, String>, token: String): PlexRootContainerDto =
        http.decode(service, PlexRootDto.serializer(), http.get(service, url, "", headers(extraHeaders, token))).container

    /** Bibliothèques de films. */
    suspend fun sections(url: HttpUrl, extraHeaders: Map<String, String>, token: String): List<PlexSection> =
        http.decode(service, PlexSectionsDto.serializer(), http.get(service, url, "library/sections", headers(extraHeaders, token)))
            .container.directories
            .filter { it.type == "movie" }
            .map { PlexSection(key = it.key, title = it.title ?: it.key) }

    /** Films des bibliothèques choisies (toutes les bibliothèques de films si aucune). */
    suspend fun movies(config: ServiceConfig): List<ServerMovie> {
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val token = config.apiKey ?: throw HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Jeton absent")
        val extra = config.headerMap()
        val keys = config.plexSections.ifEmpty { sections(url, extra, token).map { it.key }.toSet() }
        val movies = ArrayList<ServerMovie>()
        for (key in keys) {
            var start = 0
            while (true) {
                val page = http.decode(
                    service,
                    PlexMetadataPageDto.serializer(),
                    http.get(
                        service,
                        url,
                        "library/sections/$key/all",
                        headers(extra, token),
                        mapOf(
                            "type" to "1",
                            "includeGuids" to "1",
                            "X-Plex-Container-Start" to start.toString(),
                            "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                        ),
                    ),
                ).container
                page.metadata.mapTo(movies) { it.toServerMovie() }
                start += page.metadata.size
                val total = page.totalSize
                if (page.metadata.isEmpty() || (total != null && start >= total) || (total == null && page.metadata.size < PAGE_SIZE)) break
            }
        }
        return movies
    }

    private fun PlexMetadataDto.toServerMovie(): ServerMovie = ServerMovie(
        tmdbId = tmdbIdOf(this),
        title = title?.trim().orEmpty(),
        year = year,
        watched = (viewCount ?: 0) > 0,
        lastWatchedAt = lastViewedAt?.takeIf { it > 0 }?.times(1000),
        itemId = ratingKey,
    )

    /**
     * Identifiant « lisible » du film chez Plex (« dune-2021 »), pour son lien watch.plex.tv :
     * l'appli Plex ouvre ce lien sur la fiche du film, avec la lecture depuis le serveur.
     */
    suspend fun discoverSlug(tmdbId: Long, config: ServiceConfig): String? {
        val token = config.apiKey ?: return null
        val reply = http.get(
            service,
            METADATA_PROVIDER,
            "library/metadata/matches",
            accountHeaders(token) + ("Accept" to "application/json"),
            mapOf("type" to "1", "guid" to "tmdb://$tmdbId"),
        )
        return http.decode(service, PlexMatchesDto.serializer(), reply).container.metadata.firstNotNullOfOrNull { it.slug?.takeIf(String::isNotBlank) }
    }

    /** Identifiant du serveur (pour les liens vers ses films). */
    suspend fun machineIdentifier(config: ServiceConfig): String? {
        val url = HomelabUrls.parse(config.url) ?: return null
        val token = config.apiKey ?: return null
        return identity(url, config.headerMap(), token).machineIdentifier
    }

    companion object {
        val PLEX_TV = "https://plex.tv/".toHttpUrl()

        const val ANDROID_PACKAGE = "com.plexapp.android"
        val METADATA_PROVIDER = "https://metadata.provider.plex.tv/".toHttpUrl()

        /** Fiche d'un film dans l'appli Plex (lien universel watch.plex.tv, ouvert par l'appli Android). */
        fun watchUrl(slug: String): String = "https://watch.plex.tv/movie/$slug"

        /** Fiche d'un film dans Plex Web (app.plex.tv), si l'appli n'est pas installée. */
        fun webUrl(machineIdentifier: String, ratingKey: String): String =
            "https://app.plex.tv/desktop/#!/server/$machineIdentifier/details?key=%2Flibrary%2Fmetadata%2F$ratingKey"

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val PLEX_DIRECT_WILDCARD = Regex("^\\*\\.([0-9a-z]+)\\.plex\\.direct$", RegexOption.IGNORE_CASE)
        private const val PAGE_SIZE = 500
        private val NEW_AGENT = Regex("^tmdb://(\\d+)")
        private val LEGACY_AGENT = Regex("themoviedb://(\\d+)")

        /** « tmdb://329865 » (agent actuel) ou « com.plexapp.agents.themoviedb://329865?lang=fr » (ancien). */
        fun tmdbIdOf(item: PlexMetadataDto): Long? {
            item.guids.firstNotNullOfOrNull { NEW_AGENT.find(it.id)?.groupValues?.get(1)?.toLongOrNull() }?.let { return it }
            return item.guid?.let { LEGACY_AGENT.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        }
    }
}
