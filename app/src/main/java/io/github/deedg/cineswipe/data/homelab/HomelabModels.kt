package io.github.deedg.cineswipe.data.homelab

import io.github.deedg.cineswipe.data.db.UserMovieEntity
import kotlinx.serialization.Serializable

/** Services du homelab, chacun optionnel et indépendant. */
enum class HomelabService(
    val displayName: String,
    /** Port habituel, proposé en exemple : chaque homelab peut utiliser le sien. */
    val defaultPort: Int,
    val kind: Kind,
) {
    JELLYFIN("Jellyfin", 8096, Kind.MEDIA_SERVER),
    PLEX("Plex", 32400, Kind.MEDIA_SERVER),
    SEERR("Seerr", 5055, Kind.REQUESTS),
    RADARR("Radarr", 7878, Kind.REQUESTS),
    ;

    enum class Kind { MEDIA_SERVER, REQUESTS }
}

/** Connexion à Jellyfin : clé API (admin, puis choix de l'utilisateur) ou identifiant et mot de passe. */
enum class JellyfinAuthMode { API_KEY, LOGIN }

/**
 * Connexion à Seerr : clé API (les demandes partent au nom de l'administrateur) ou compte d'un
 * utilisateur, pour que chacun ait ses propres demandes : compte Seerr (e-mail), Jellyfin ou Plex.
 */
enum class SeerrAuthMode { API_KEY, LOCAL, JELLYFIN, PLEX }

/** Service qui reçoit les demandes quand Seerr et Radarr sont configurés tous les deux. */
enum class RequestTarget { AUTO, SEERR, RADARR }

/**
 * Réglages d'un service. [url] est l'adresse complète choisie par l'utilisateur, port compris
 * (IP Tailscale, IP locale ou nom de domaine).
 */
data class ServiceConfig(
    val service: HomelabService,
    val enabled: Boolean = false,
    val url: String = "",
    /** Clé API, jeton Plex, jeton de session Jellyfin, ou cookies de session Seerr. */
    val apiKey: String? = null,
    /** En-têtes supplémentaires (ex. Cloudflare Access), un par ligne au format « Nom: valeur ». */
    val headers: String = "",
    val jellyfinAuthMode: JellyfinAuthMode = JellyfinAuthMode.API_KEY,
    val seerrAuthMode: SeerrAuthMode = SeerrAuthMode.API_KEY,
    /** Utilisateur Jellyfin dont on lit les films vus, ou compte Seerr connecté. */
    val userId: String? = null,
    val userName: String? = null,
    /** Bibliothèques Plex lues (clés de section) ; vide = toutes les bibliothèques de films. */
    val plexSections: Set<String> = emptySet(),
    /** Profil de qualité et dossier racine : ceux de Radarr, ou ceux demandés via Seerr (null = défaut de Seerr). */
    val qualityProfileId: Int? = null,
    val rootFolder: String? = null,
    /** Radarr : lancer la recherche dès l'ajout. */
    val searchOnAdd: Boolean = true,
    /** Seerr : serveur Radarr qui reçoit les demandes (null = celui par défaut dans Seerr). */
    val targetServerId: Int? = null,
    val targetServer4k: Boolean = false,
) {
    /** Prêt à servir : activé, adresse et clé valides, et les choix propres au service faits. */
    val isReady: Boolean
        get() = enabled && HomelabUrls.parse(url) != null && !apiKey.isNullOrBlank() && when (service) {
            HomelabService.JELLYFIN -> !userId.isNullOrBlank()
            HomelabService.RADARR -> qualityProfileId != null && !rootFolder.isNullOrBlank()
            HomelabService.PLEX, HomelabService.SEERR -> true
        }

    /** Seerr connecté avec le compte d'un utilisateur (cookies de session au lieu de la clé API). */
    val usesSeerrSession: Boolean get() = service == HomelabService.SEERR && seerrAuthMode != SeerrAuthMode.API_KEY

    fun headerMap(): Map<String, String> = HomelabUrls.parseHeaders(headers)
}

data class HomelabConfig(
    val jellyfin: ServiceConfig = ServiceConfig(HomelabService.JELLYFIN),
    val plex: ServiceConfig = ServiceConfig(HomelabService.PLEX),
    val seerr: ServiceConfig = ServiceConfig(HomelabService.SEERR),
    val radarr: ServiceConfig = ServiceConfig(HomelabService.RADARR),
    val requestTarget: RequestTarget = RequestTarget.AUTO,
) {
    val all: List<ServiceConfig> get() = listOf(jellyfin, plex, seerr, radarr)

    fun of(service: HomelabService): ServiceConfig = when (service) {
        HomelabService.JELLYFIN -> jellyfin
        HomelabService.PLEX -> plex
        HomelabService.SEERR -> seerr
        HomelabService.RADARR -> radarr
    }

    /** Au moins un service prêt : la disponibilité des films a un sens. */
    val anyReady: Boolean get() = all.any { it.isReady }

    /**
     * Service qui reçoit les demandes du swipe droit, ou null (swipe droit = À voir). Avec les
     * deux, Seerr est préféré sauf si l'utilisateur choisit Radarr.
     */
    val requestService: HomelabService?
        get() {
            val seerrReady = HomelabService.SEERR.takeIf { seerr.isReady }
            val radarrReady = HomelabService.RADARR.takeIf { radarr.isReady }
            return when (requestTarget) {
                RequestTarget.AUTO, RequestTarget.SEERR -> seerrReady ?: radarrReady
                RequestTarget.RADARR -> radarrReady ?: seerrReady
            }
        }
}

/** Film vu dans la bibliothèque d'un serveur média. */
data class ServerMovie(
    val tmdbId: Long?,
    val title: String,
    val year: Int?,
    val watched: Boolean,
    /** Date du dernier visionnage (ms), si le serveur la donne. */
    val lastWatchedAt: Long?,
    /** Identifiant du film sur le serveur (ratingKey Plex, Id Jellyfin), pour l'y ouvrir. */
    val itemId: String? = null,
)

/**
 * Où regarder un film du serveur. On essaie d'abord l'appli [appPackage] avec [appUri] (ou
 * [webUrl]), puis [webUrl] dans le navigateur.
 */
data class WatchLink(
    val service: HomelabService,
    val appUri: String?,
    val appPackage: String?,
    val webUrl: String,
    /** Démo : pas de vrai serveur, l'appli dit seulement ce qui se passerait. */
    val demo: Boolean = false,
)

/** Service qui n'a pas répondu pendant une synchro. */
@Serializable
data class SyncFailure(
    val service: HomelabService,
    val kind: HomelabException.Kind,
    val detail: String? = null,
)

/** Compte rendu de la dernière synchronisation, affiché dans l'écran Homelab. */
@Serializable
data class SyncReport(
    val finishedAt: Long,
    /** Films vus sur les serveurs médias. */
    val watched: Int = 0,
    /** Films ajoutés aux vus par cette synchro. */
    val newlyWatched: Int = 0,
    val available: Int = 0,
    val requested: Int = 0,
    /** Films des serveurs sans identifiant TMDB, donc ignorés. */
    val unmatched: Int = 0,
    val failures: List<SyncFailure> = emptyList(),
)

/** Résultat d'un test de connexion, avec les choix à proposer dans les réglages du service. */
data class ConnectionInfo(
    val serverName: String? = null,
    val version: String? = null,
    /** Compte utilisé (utilisateur Jellyfin, compte Seerr). */
    val account: String? = null,
    val jellyfinUsers: List<JellyfinUserDto> = emptyList(),
    val plexSections: List<PlexSection> = emptyList(),
    val radarrProfiles: List<RadarrQualityProfileDto> = emptyList(),
    val radarrRootFolders: List<RadarrRootFolderDto> = emptyList(),
    /** Seerr : serveurs Radarr qui peuvent recevoir les demandes, avec leurs profils et dossiers. */
    val seerrTargets: List<SeerrTarget> = emptyList(),
    /**
     * Plex : adresse sécurisée (plex.direct) trouvée pendant le test, quand le serveur refuse
     * le http ; elle remplace l'adresse saisie.
     */
    val resolvedUrl: String? = null,
)

/** Serveur Radarr déclaré dans Seerr. */
data class SeerrTarget(
    val id: Int,
    val name: String,
    val is4k: Boolean,
    val isDefault: Boolean,
    val activeProfileId: Int?,
    val activeDirectory: String?,
    val profiles: List<RadarrQualityProfileDto>,
    val rootFolders: List<RadarrRootFolderDto>,
)

/** Session Seerr d'un utilisateur : cookies à renvoyer à chaque requête. */
data class SeerrSession(val cookies: String, val userId: Long, val displayName: String?)

/** Code de connexion plex.tv : l'utilisateur l'approuve dans son navigateur, à l'adresse [url]. */
data class PlexPin(val id: Long, val code: String, val url: String)

/** Serveur Plex d'un compte (le sien ou partagé), avec les adresses connues de plex.tv. */
data class PlexServer(
    val name: String,
    val accessToken: String?,
    val owned: Boolean,
    val connections: List<PlexConnection>,
)

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

/** Demande enregistrée depuis une carte ou une fiche : de quoi l'annuler à l'identique. */
data class RequestTicket(
    val outboxId: Long,
    val service: HomelabService,
    /** Ligne du film avant la demande (null s'il n'y en avait pas). */
    val previous: UserMovieEntity?,
)

/** Suites d'une demande, signalées après coup par un toast (l'envoi part quelques secondes plus tard). */
sealed interface HomelabEvent {
    val service: HomelabService
    val title: String

    /** Le film était déjà demandé, ou déjà présent sur le service. */
    data class AlreadyRequested(override val service: HomelabService, override val title: String) : HomelabEvent

    /** Service injoignable : la demande partira dès qu'il répondra. */
    data class Queued(override val service: HomelabService, override val title: String) : HomelabEvent

    /** Demande refusée : elle reste dans l'écran Homelab, où on peut la relancer. */
    data class Failed(
        override val service: HomelabService,
        override val title: String,
        val kind: HomelabException.Kind,
        /** Raison donnée par le service (quota atteint, permission manquante…). */
        val detail: String? = null,
    ) : HomelabEvent

    /** L'annulation n'a pas pu être envoyée : la demande reste sur le service. */
    data class CancelFailed(override val service: HomelabService, override val title: String) : HomelabEvent
}
