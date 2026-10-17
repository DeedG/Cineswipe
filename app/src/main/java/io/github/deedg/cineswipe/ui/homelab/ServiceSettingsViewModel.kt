package io.github.deedg.cineswipe.ui.homelab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.homelab.ConnectionInfo
import io.github.deedg.cineswipe.data.homelab.HomelabException
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.PlexServer
import io.github.deedg.cineswipe.data.homelab.SeerrAuthMode
import io.github.deedg.cineswipe.data.homelab.SeerrSession
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Test de connexion d'un service. */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Success : TestState
    data class Failed(val kind: HomelabException.Kind, val code: Int?, val detail: String? = null) : TestState
}

data class ServiceSettingsUiState(
    val loaded: Boolean = false,
    /** Réglages en cours de saisie, enregistrés seulement avec « Enregistrer ». */
    val draft: ServiceConfig,
    /** Le service avait déjà des réglages (bouton « Effacer »). */
    val configured: Boolean = false,
    val test: TestState = TestState.Idle,
    /** Résultat du dernier test réussi : utilisateurs, bibliothèques, profils… */
    val info: ConnectionInfo? = null,
    val saving: Boolean = false,
    /** Connexion plex.tv en cours : adresse à valider dans le navigateur. */
    val plexLoginUrl: String? = null,
    /** Serveurs du compte Plex connecté, pour choisir le bon. */
    val plexServers: List<PlexServer> = emptyList(),
    /** Plex exige le https : l'adresse plex.direct a remplacé l'adresse saisie. */
    val secureUrlApplied: Boolean = false,
)

sealed interface ServiceSettingsEvent {
    /** [syncing] : le service est prêt, une synchro est partie. */
    data class Saved(val syncing: Boolean) : ServiceSettingsEvent
    data object Cleared : ServiceSettingsEvent

    /** Page de connexion plex.tv à ouvrir dans le navigateur. */
    data class OpenUrl(val url: String) : ServiceSettingsEvent
}

class ServiceSettingsViewModel(
    private val service: HomelabService,
    private val homelab: HomelabRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServiceSettingsUiState(draft = ServiceConfig(service)))
    val state: StateFlow<ServiceSettingsUiState> = _state.asStateFlow()

    private val _events = Channel<ServiceSettingsEvent>(Channel.BUFFERED)
    val events: Flow<ServiceSettingsEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val saved = homelab.config.first().of(service)
            val configured = saved.enabled || saved.url.isNotBlank()
            // Un service jamais configuré est activé d'office : il ne reste qu'à saisir l'adresse et la clé.
            _state.update { it.copy(loaded = true, configured = configured, draft = if (configured) saved else saved.copy(enabled = true)) }
        }
    }

    /** Modifie la saisie ; changer d'adresse, de clé, d'en-têtes ou de mode de connexion oblige à refaire le test. */
    fun edit(transform: (ServiceConfig) -> ServiceConfig) {
        _state.update { current ->
            val draft = transform(current.draft)
            val connectionChanged = draft.url != current.draft.url ||
                draft.apiKey != current.draft.apiKey ||
                draft.headers != current.draft.headers ||
                draft.jellyfinAuthMode != current.draft.jellyfinAuthMode ||
                draft.seerrAuthMode != current.draft.seerrAuthMode
            if (connectionChanged) {
                job?.cancel()
                current.copy(draft = draft, test = TestState.Idle, info = null, plexLoginUrl = null, secureUrlApplied = false)
            } else {
                current.copy(draft = draft)
            }
        }
    }

    fun test() {
        val draft = _state.value.draft
        launchTest {
            val info = homelab.testConnection(draft)
            applyInfo(draft, info)
        }
    }

    /** Jellyfin, connexion par identifiant : le jeton obtenu remplace la clé API. */
    fun login(username: String, password: String) {
        val draft = _state.value.draft
        launchTest {
            val login = homelab.jellyfinLogin(draft.url, draft.headers, username, password)
            val loggedIn = draft.copy(apiKey = login.token, userId = login.userId, userName = login.userName ?: username.trim())
            applyInfo(loggedIn, homelab.testConnection(loggedIn))
        }
    }

    /** Seerr, connexion avec un compte Seerr ou Jellyfin : la session remplace la clé API. */
    fun seerrLogin(identifier: String, password: String) {
        val draft = _state.value.draft
        launchTest {
            applySession(draft, homelab.seerrLogin(draft.url, draft.headers, draft.seerrAuthMode, identifier, password))
        }
    }

    /**
     * Connexion avec un compte Plex : la page plex.tv s'ouvre dans le navigateur, puis l'appli
     * attend la validation. Pour Plex, on récupère les serveurs du compte ; pour Seerr, on ouvre
     * la session de cet utilisateur.
     */
    fun plexSignIn() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val pin = homelab.plexCreatePin()
                _state.update { it.copy(plexLoginUrl = pin.url, test = TestState.Idle) }
                _events.send(ServiceSettingsEvent.OpenUrl(pin.url))
                val token = homelab.plexAwaitToken(pin)
                _state.update { it.copy(plexLoginUrl = null, test = TestState.Running) }
                when (service) {
                    HomelabService.PLEX -> {
                        val servers = homelab.plexServers(token)
                        _state.update { it.copy(plexServers = servers, test = TestState.Idle, draft = it.draft.copy(apiKey = token)) }
                        servers.singleOrNull()?.let { chooseServer(it) }
                    }
                    HomelabService.SEERR -> {
                        val draft = _state.value.draft
                        applySession(draft, homelab.seerrLogin(draft.url, draft.headers, SeerrAuthMode.PLEX, "", token))
                    }
                    HomelabService.JELLYFIN, HomelabService.RADARR, HomelabService.SONARR -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HomelabException) {
                _state.update { it.copy(plexLoginUrl = null, test = TestState.Failed(error.kind, error.code, error.serverMessage)) }
            }
        }
    }

    /** Rouvre la page de connexion Plex (navigateur fermé trop tôt). */
    fun reopenPlexLogin() {
        val url = _state.value.plexLoginUrl ?: return
        viewModelScope.launch { _events.send(ServiceSettingsEvent.OpenUrl(url)) }
    }

    /**
     * Serveur Plex choisi : son jeton d'accès propre (indispensable pour un serveur partagé),
     * et une adresse connue de plex.tv si aucune n'est saisie. Le test part ensuite.
     */
    fun chooseServer(server: PlexServer) {
        val current = _state.value.draft
        val address = current.url.ifBlank {
            val usable = server.connections.filterNot { it.relay }
            (usable.firstOrNull { it.local } ?: usable.firstOrNull() ?: server.connections.firstOrNull())?.uri.orEmpty()
        }
        val draft = current.copy(apiKey = server.accessToken ?: current.apiKey, url = address)
        _state.update { it.copy(draft = draft, info = null, test = TestState.Idle) }
        if (address.isNotBlank()) test()
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val draft = current.draft.copy(url = current.draft.url.trim(), apiKey = current.draft.apiKey?.trim()?.ifEmpty { null })
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            homelab.saveService(draft)
            _state.update { it.copy(saving = false, configured = true) }
            _events.send(ServiceSettingsEvent.Saved(syncing = draft.isReady))
        }
    }

    fun clear() {
        viewModelScope.launch {
            homelab.clearService(service)
            _events.send(ServiceSettingsEvent.Cleared)
        }
    }

    private suspend fun applySession(draft: ServiceConfig, session: SeerrSession) {
        val loggedIn = draft.copy(apiKey = session.cookies, userId = session.userId.toString(), userName = session.displayName)
        applyInfo(loggedIn, homelab.testConnection(loggedIn))
    }

    private fun applyInfo(draft: ServiceConfig, info: ConnectionInfo) {
        val resolved = info.resolvedUrl
        val withUrl = if (resolved != null) draft.copy(url = resolved) else draft
        _state.update {
            it.copy(
                draft = withChoices(withUrl, info),
                test = TestState.Success,
                info = info,
                secureUrlApplied = resolved != null || (it.secureUrlApplied && withUrl.url == it.draft.url),
            )
        }
    }

    private fun launchTest(block: suspend () -> Unit) {
        job?.cancel()
        _state.update { it.copy(test = TestState.Running, plexLoginUrl = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: HomelabException) {
                _state.update { it.copy(test = TestState.Failed(error.kind, error.code, error.serverMessage)) }
            }
        }
    }

    /** Après un test : choix gardés s'ils existent toujours, sinon le premier proposé quand il n'y a pas d'ambiguïté. */
    private fun withChoices(draft: ServiceConfig, info: ConnectionInfo): ServiceConfig = when (draft.service) {
        HomelabService.JELLYFIN -> {
            val users = info.jellyfinUsers
            val current = users.firstOrNull { it.id == draft.userId }
            when {
                current != null -> draft.copy(userName = current.name ?: draft.userName)
                users.size == 1 -> draft.copy(userId = users[0].id, userName = users[0].name)
                else -> draft.copy(userId = null, userName = null)
            }
        }
        HomelabService.PLEX -> draft.copy(
            plexSections = draft.plexSections.filterTo(LinkedHashSet()) { key -> info.plexSections.any { it.key == key } },
        )
        HomelabService.RADARR, HomelabService.SONARR -> draft.copy(
            qualityProfileId = draft.qualityProfileId?.takeIf { id -> info.radarrProfiles.any { it.id == id } }
                ?: info.radarrProfiles.firstOrNull()?.id,
            rootFolder = draft.rootFolder?.takeIf { path -> info.radarrRootFolders.any { it.path == path } }
                ?: info.radarrRootFolders.firstOrNull()?.path,
        )
        HomelabService.SEERR -> {
            val targets = info.seerrTargets
            if (targets.isEmpty()) {
                draft
            } else {
                // Serveur, profil et dossier gardés seulement s'ils existent encore ; sinon les valeurs par défaut de Seerr.
                val target = targets.firstOrNull { it.id == draft.targetServerId }
                val options = target ?: targets.firstOrNull { it.isDefault && !it.is4k } ?: targets.first()
                draft.copy(
                    targetServerId = target?.id,
                    targetServer4k = target?.is4k ?: false,
                    qualityProfileId = draft.qualityProfileId?.takeIf { id -> options.profiles.any { it.id == id } },
                    rootFolder = draft.rootFolder?.takeIf { path -> options.rootFolders.any { it.path == path } },
                )
            }
        }
    }
}
