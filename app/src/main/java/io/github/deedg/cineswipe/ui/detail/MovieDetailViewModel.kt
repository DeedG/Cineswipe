package io.github.deedg.cineswipe.ui.detail

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import io.github.deedg.cineswipe.data.repository.WatchProviders
import io.github.deedg.cineswipe.data.homelab.WatchLink
import io.github.deedg.cineswipe.data.db.Availability
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.model.MovieDetails
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MovieDetailUiState(
    val loading: Boolean = true,
    val details: MovieDetails? = null,
    val showAvailability: Boolean = false,
    /** Service qui reçoit les demandes (Seerr ou Radarr), ou null. */
    val requestService: HomelabService? = null,
    /** Serveurs média où regarder le film (boutons Plex, Jellyfin). */
    val watchOn: List<HomelabService> = emptyList(),
    /** Où le regarder ailleurs, quand il n'est ni sur le serveur ni demandé (ou sans homelab). */
    val providers: WatchProviders? = null,
)

sealed interface WatchLinkResult {
    data class Open(val link: WatchLink) : WatchLinkResult
    data class Missing(val service: HomelabService) : WatchLinkResult
}

class MovieDetailViewModel(
    private val tmdbId: Long,
    private val library: LibraryRepository,
    private val tmdb: TmdbRepository,
    private val homelab: HomelabRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(true)

    /** Serveurs qui ont le film (null : pas encore lu, ou injoignable ; le bouton reste alors). */
    private val serverFilms = MutableStateFlow<Map<HomelabService, Set<Long>?>>(emptyMap())
    private val providers = MutableStateFlow<WatchProviders?>(null)
    private val linkChannel = Channel<WatchLinkResult>(Channel.BUFFERED)
    val links: Flow<WatchLinkResult> = linkChannel.receiveAsFlow()

    val state: StateFlow<MovieDetailUiState> = combine(
        library.observeDetails(tmdbId),
        refreshing,
        settings.homelabConfig,
        combine(homelab.mediaServers, serverFilms) { servers, films -> servers.filter { films[it]?.contains(tmdbId) ?: true } },
        providers,
    ) { details, isRefreshing, config, servers, providers ->
        val availability = details?.user?.availability ?: Availability.NONE
        val onServer = config.anyReady && availability == Availability.AVAILABLE
        val requested = config.anyReady && availability == Availability.REQUESTED
        MovieDetailUiState(
            loading = details == null && isRefreshing,
            details = details,
            showAvailability = config.anyReady,
            requestService = config.requestService,
            watchOn = if (onServer) servers else emptyList(),
            providers = providers.takeIf { !onServer && !requested },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovieDetailUiState())

    init {
        // Fiche complétée depuis TMDB si elle est partielle ou ancienne (synopsis, casting, bande-annonce).
        viewModelScope.launch {
            try {
                tmdb.ensureDetails(tmdbId)
            } catch (error: TmdbException) {
                // Pas de clé ou pas de réseau : on garde la fiche du cache.
            }
            refreshing.value = false
        }
        viewModelScope.launch {
            providers.value = try {
                tmdb.watchProviders(tmdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
        }
        viewModelScope.launch { serverFilms.value = homelab.mediaServersHaving(listOf(tmdbId)) }
    }

    /** Ouvre le film sur [service] (Plex, Jellyfin). */
    fun watch(service: HomelabService) {
        viewModelScope.launch {
            val link = homelab.watchLink(service, tmdbId)
            linkChannel.send(if (link != null) WatchLinkResult.Open(link) else WatchLinkResult.Missing(service))
        }
    }

    fun markWatched() {
        viewModelScope.launch { library.markWatched(tmdbId) }
    }

    fun unmarkWatched() {
        viewModelScope.launch { library.unmarkWatched(tmdbId) }
    }

    fun setStatus(status: WatchStatus) {
        viewModelScope.launch { library.setStatus(tmdbId, status) }
    }

    /** Demande le film au homelab ; [onRequested] reçoit le service choisi (pour le toast). */
    fun request(onRequested: (HomelabService) -> Unit) {
        val movie = state.value.details?.movie ?: return
        viewModelScope.launch {
            homelab.requestMovie(movie)?.let { onRequested(it.service) }
        }
    }
}
