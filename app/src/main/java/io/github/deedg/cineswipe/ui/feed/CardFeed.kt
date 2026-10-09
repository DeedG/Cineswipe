package io.github.deedg.cineswipe.ui.feed

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.repository.ActionRecord
import io.github.deedg.cineswipe.data.repository.CardAction
import io.github.deedg.cineswipe.data.repository.FeedCursor
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.domain.reco.RecoReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Une carte du feed : la fiche et le statut à jour du film, et pourquoi il est proposé (recos). */
data class FeedCardUi(
    val movie: MovieEntity,
    val status: WatchStatus,
    val availability: Availability,
    val reason: RecoReason? = null,
) {
    val tmdbId: Long get() = movie.tmdbId
}

enum class FeedPhase { IDLE, NEEDS_KEY, LOADING, READY, EMPTY, ERROR }

enum class FeedError { NETWORK, INVALID_KEY, OTHER }

data class CardFeedState(
    /** Change à chaque nouveau feed (nouvelle recherche, nouveaux filtres) : on repart de la première carte. */
    val session: Int = 0,
    val phase: FeedPhase = FeedPhase.IDLE,
    val cards: List<FeedCardUi> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** Erreur du premier chargement (phase ERROR) ou du chargement de la suite. */
    val error: FeedError? = null,
    val canUndo: Boolean = false,
    val showAvailability: Boolean = false,
    /** Service qui reçoit les demandes (swipe droit = Demander), ou null : swipe droit = À voir. */
    val requestService: HomelabService? = null,
)

sealed interface FeedEvent {
    /** [service] : service qui recevra la demande, pour l'action [CardAction.REQUEST]. */
    data class Acted(val action: CardAction, val service: HomelabService? = null) : FeedEvent
    data class Restored(val tmdbId: Long) : FeedEvent
}

/**
 * État d'un feed de cartes, partagé par les Recos, l'Éclair, la Recherche et la Découverte.
 * Vit dans le ViewModel de l'écran ([scope] = viewModelScope).
 *
 * - Les cartes sont chargées par lots, la suite quand on approche de la fin.
 * - Une action retire la carte puis l'enregistre ; « Annuler » rejoue les trois dernières
 *   à l'identique et remet la carte à sa place.
 * - Les badges suivent la base : un film marqué vu ailleurs s'affiche « Vu » ici aussi.
 */
class CardFeed(
    private val scope: CoroutineScope,
    private val repository: FeedRepository,
    settings: SettingsRepository,
    /** Vrai si les cartes affichées comptent pour la mémoire du feed (recos, Éclair, découverte). */
    private val rememberShown: Boolean,
) {
    private data class Content(
        val session: Int = 0,
        val phase: FeedPhase = FeedPhase.IDLE,
        val movies: List<MovieEntity> = emptyList(),
        val reasons: Map<Long, RecoReason> = emptyMap(),
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        val error: FeedError? = null,
        val undoCount: Int = 0,
    )

    private class UndoEntry(val index: Int, val record: ActionRecord, val reason: RecoReason?)

    private val content = MutableStateFlow(Content())
    private val _events = Channel<FeedEvent>(Channel.BUFFERED)
    val events: Flow<FeedEvent> = _events.receiveAsFlow()

    private data class HomelabUi(val connected: Boolean, val requestService: HomelabService?)

    val state: StateFlow<CardFeedState> = combine(
        content,
        repository.observeCardStatuses(),
        settings.homelabConfig.map { HomelabUi(it.anyReady, it.requestService) }.distinctUntilChanged(),
    ) { current, statuses, homelab ->
        CardFeedState(
            session = current.session,
            phase = current.phase,
            cards = current.movies.map { movie ->
                val row = statuses[movie.tmdbId]
                FeedCardUi(movie, row?.status ?: WatchStatus.NONE, row?.availability ?: Availability.NONE, current.reasons[movie.tmdbId])
            },
            loadingMore = current.loadingMore,
            endReached = current.endReached,
            error = current.error,
            canUndo = current.undoCount > 0,
            showAvailability = homelab.connected,
            requestService = homelab.requestService,
        )
    }.stateIn(scope, SharingStarted.Eagerly, CardFeedState())

    private var request: FeedRequest? = null
    private var cursor: FeedCursor = FeedCursor.START
    private val sessionIds = HashSet<Long>()
    private val shownIds = HashSet<Long>()
    private val undoStack = ArrayDeque<UndoEntry>()
    private var loadJob: Job? = null

    init {
        // Clé ajoutée dans les réglages pendant que l'écran la réclamait : on relance.
        scope.launch {
            settings.tmdbApiKey.map { it != null }.distinctUntilChanged().collect { present ->
                val waiting = request
                if (present && waiting != null && content.value.phase == FeedPhase.NEEDS_KEY) start(waiting)
            }
        }
    }

    fun start(newRequest: FeedRequest) {
        request = newRequest
        loadJob?.cancel()
        cursor = FeedCursor.START
        sessionIds.clear()
        shownIds.clear()
        undoStack.clear()
        content.value = Content(session = content.value.session + 1, phase = FeedPhase.LOADING)
        loadJob = scope.launch { load() }
    }

    /** Quitte le feed (retour aux filtres ou à la saisie de la recherche). */
    fun reset() {
        request = null
        loadJob?.cancel()
        cursor = FeedCursor.START
        sessionIds.clear()
        shownIds.clear()
        undoStack.clear()
        content.value = Content(session = content.value.session + 1)
    }

    fun retry() {
        val current = request ?: return
        if (content.value.movies.isEmpty()) start(current) else loadMore()
    }

    fun loadMore() {
        val current = content.value
        if (request == null || current.phase != FeedPhase.READY) return
        if (current.loadingMore || current.endReached || loadJob?.isActive == true) return
        content.update { it.copy(loadingMore = true, error = null) }
        loadJob = scope.launch { load() }
    }

    private suspend fun load() {
        val current = request ?: return
        try {
            val batch = repository.load(current, cursor, sessionIds)
            cursor = batch.next
            val fresh = batch.movies.filter { sessionIds.add(it.tmdbId) }
            content.update { state ->
                val movies = state.movies + fresh
                state.copy(
                    phase = if (movies.isEmpty()) FeedPhase.EMPTY else FeedPhase.READY,
                    movies = movies,
                    reasons = state.reasons + batch.reasons.filterKeys { id -> fresh.any { it.tmdbId == id } },
                    loadingMore = false,
                    endReached = batch.endReached,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TmdbException) {
            content.update { state ->
                when {
                    error is TmdbException.MissingKey -> state.copy(phase = FeedPhase.NEEDS_KEY, loadingMore = false)
                    state.movies.isEmpty() -> state.copy(phase = FeedPhase.ERROR, error = error.toFeedError(), loadingMore = false)
                    else -> state.copy(loadingMore = false, error = error.toFeedError())
                }
            }
        }
    }

    /** La carte est sortie de l'écran (animation terminée) : on l'enlève et on enregistre l'action. */
    fun act(card: FeedCardUi, action: CardAction) {
        val index = content.value.movies.indexOfFirst { it.tmdbId == card.tmdbId }
        if (index < 0) return
        content.update { state -> state.copy(movies = state.movies.filterNot { it.tmdbId == card.tmdbId }) }
        scope.launch {
            val record = repository.apply(card.movie, action)
            card.reason?.let { repository.recordFeedback(it, record.action) }
            undoStack.addLast(UndoEntry(index, record, card.reason))
            while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            content.update { it.copy(undoCount = undoStack.size) }
            _events.send(FeedEvent.Acted(record.action, record.request?.service))
        }
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        content.update { it.copy(undoCount = undoStack.size) }
        scope.launch {
            repository.undo(entry.record)
            entry.reason?.let { repository.recordFeedback(it, entry.record.action, undo = true) }
            val movie = entry.record.movie
            content.update { state ->
                val movies = state.movies.filterNot { it.tmdbId == movie.tmdbId }.toMutableList()
                movies.add(entry.index.coerceIn(0, movies.size), movie)
                state.copy(movies = movies, phase = FeedPhase.READY)
            }
            _events.send(FeedEvent.Restored(movie.tmdbId))
        }
    }

    /** Comme en V4, la pile d'annulation est vidée quand on quitte l'écran. */
    fun clearUndo() {
        undoStack.clear()
        content.update { it.copy(undoCount = 0) }
    }

    fun onCardShown(card: FeedCardUi) {
        if (!rememberShown || !shownIds.add(card.tmdbId)) return
        scope.launch { repository.markShown(card.tmdbId, card.reason) }
    }

    private fun TmdbException.toFeedError(): FeedError = when (this) {
        is TmdbException.InvalidKey -> FeedError.INVALID_KEY
        is TmdbException.Network -> FeedError.NETWORK
        else -> FeedError.OTHER
    }

    private companion object {
        const val MAX_UNDO = 3
    }
}
