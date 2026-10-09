package io.github.deedg.cineswipe.ui.arena

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.model.ArenaMatch
import io.github.deedg.cineswipe.data.model.MovieDetails
import io.github.deedg.cineswipe.data.model.RankingReliability
import io.github.deedg.cineswipe.data.repository.ArenaRepository
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.domain.Matchmaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Side { A, B }

sealed interface ArenaUiState {
    data object Loading : ArenaUiState
    data class NotEnough(val watched: Int) : ArenaUiState

    /**
     * [round] change à chaque nouveau match et déclenche l'animation d'entrée. Après le vote :
     * [winner] = le film choisi, ou [draw] = égalité.
     */
    data class Ready(
        val match: ArenaMatch,
        val round: Int,
        val winner: Side? = null,
        val draw: Boolean = false,
    ) : ArenaUiState {
        val decided: Boolean get() = winner != null || draw
    }
}

sealed interface ArenaEvent {
    data object Undone : ArenaEvent

    /** Le film en placement a joué ses duels. */
    data class Placed(val title: String) : ArenaEvent
}

/** Bandeau du placement en cours : le film, et le numéro du duel. */
data class FocusBanner(val title: String, val duel: Int, val total: Int)

/**
 * Le vote (ou l'égalité) est enregistré tout de suite, dans une transaction qui recalcule tout le
 * classement ; « Annuler » reste proposé 3,5 s, comme en V4, et rejoue le duel annulé.
 */
class ArenaViewModel(
    private val arena: ArenaRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ArenaUiState>(ArenaUiState.Loading)
    val state: StateFlow<ArenaUiState> = _state.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _info = MutableStateFlow<MovieDetails?>(null)
    val info: StateFlow<MovieDetails?> = _info.asStateFlow()

    private val _events = Channel<ArenaEvent>(Channel.BUFFERED)
    val events: Flow<ArenaEvent> = _events.receiveAsFlow()

    /** Placement en cours (après un film vu sur le serveur), avec le film du match affiché. */
    val focusBanner: StateFlow<FocusBanner?> = combine(arena.focus, _state) { focus, state ->
        val id = focus?.current ?: return@combine null
        val match = (state as? ArenaUiState.Ready)?.match ?: return@combine null
        val fighter = listOf(match.a, match.b).firstOrNull { it.tmdbId == id } ?: return@combine null
        FocusBanner(fighter.title, (focus.played + 1).coerceAtMost(focus.perFilm), focus.perFilm)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Fiabilité du classement, affichée sous le titre. */
    val reliability: StateFlow<RankingReliability?> =
        arena.observeReliability().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var round = 0
    private val recentIds = ArrayDeque<Long>()
    private val recentPairs = ArrayDeque<Pair<Long, Long>>()
    private var votedMatch: ArenaMatch? = null

    /** Le duel que « Annuler » retire : celui du dernier vote enregistré, et lui seul. */
    private var votedDuelId: Long? = null
    private var voteJob: Job? = null
    private var undoTimer: Job? = null
    private var loadJob: Job? = null

    init {
        // Classement recalculé à l'ouverture : des films ont pu être ajoutés aux vus ou en être retirés.
        loadJob = viewModelScope.launch {
            arena.refresh()
            showNextMatch()
        }
        // Un placement demandé pendant que l'Arène est ouverte (notification) : son film arrive tout de suite.
        viewModelScope.launch {
            arena.focus.map { it?.current }.distinctUntilChanged().drop(1).collect { id ->
                val shown = (_state.value as? ArenaUiState.Ready)?.match
                val included = shown != null && (shown.a.tmdbId == id || shown.b.tmdbId == id)
                if (id != null && !included && voteJob?.isActive != true) loadNext()
            }
        }
    }

    /** Arrête le placement en cours (les duels joués restent). */
    fun stopFocus() {
        arena.clearFocus()
    }

    /** Rappelé quand l'écran revient au premier plan (ex. après avoir marqué des films vus). */
    fun refreshIfNeeded() {
        if (_state.value is ArenaUiState.NotEnough) loadNext()
    }

    fun skip() {
        if (voteJob?.isActive == true) return
        (_state.value as? ArenaUiState.Ready)?.let { rememberMatch(it.match) }
        loadNext()
    }

    fun vote(side: Side) {
        val current = _state.value as? ArenaUiState.Ready ?: return
        if (current.decided || voteJob?.isActive == true) return
        val (winner, loser) = when (side) {
            Side.A -> current.match.a to current.match.b
            Side.B -> current.match.b to current.match.a
        }
        record(current, current.copy(winner = side), winner.tmdbId, loser.tmdbId, draw = false)
    }

    /** Égalité : les deux films se valent. */
    fun draw() {
        val current = _state.value as? ArenaUiState.Ready ?: return
        if (current.decided || voteJob?.isActive == true) return
        record(current, current.copy(draw = true), current.match.a.tmdbId, current.match.b.tmdbId, draw = true)
    }

    private fun record(current: ArenaUiState.Ready, decided: ArenaUiState.Ready, winnerId: Long, loserId: Long, draw: Boolean) {
        _state.value = decided
        // Un nouveau vote part : l'annulation du précédent n'est plus proposée.
        undoTimer?.cancel()
        _canUndo.value = false
        votedMatch = null
        votedDuelId = null
        voteJob = viewModelScope.launch {
            // Le résultat reste affiché pendant que le classement se recalcule.
            val display = launch { delay(RESULT_DISPLAY_MS) }
            val duel = arena.recordDuel(winnerId, loserId, draw)
            rememberMatch(current.match)
            if (duel != null) {
                arena.focusPlayed(winnerId, loserId)?.let { placed ->
                    val title = listOf(current.match.a, current.match.b).first { it.tmdbId == placed }.title
                    _events.send(ArenaEvent.Placed(title))
                }
                votedMatch = current.match
                votedDuelId = duel.id
                _canUndo.value = true
            }
            undoTimer?.cancel()
            undoTimer = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                _canUndo.value = false
                votedMatch = null
            }
            display.join()
            showNextMatch()
        }
    }

    fun undo() {
        val match = votedMatch ?: return
        val duelId = votedDuelId ?: return
        votedMatch = null
        votedDuelId = null
        undoTimer?.cancel()
        _canUndo.value = false
        voteJob?.cancel()
        loadJob?.cancel()
        viewModelScope.launch {
            arena.undoDuel(duelId)
            val refreshed = arena.reloadMatch(match.a.tmdbId, match.b.tmdbId) ?: match
            round++
            _state.value = ArenaUiState.Ready(refreshed, round)
            _events.send(ArenaEvent.Undone)
        }
    }

    fun showInfo(tmdbId: Long) {
        viewModelScope.launch { _info.value = library.details(tmdbId) }
    }

    fun hideInfo() {
        _info.value = null
    }

    private fun loadNext() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { showNextMatch() }
    }

    private suspend fun showNextMatch() {
        var focusId = arena.focus.value?.current
        var match = arena.pickMatch(recentIds.toSet(), recentPairs.toSet(), focusId)
        // Film en placement introuvable parmi les vus : on passe au suivant, puis au tirage normal.
        while (focusId != null && match != null && match.a.tmdbId != focusId && match.b.tmdbId != focusId) {
            arena.skipFocus(focusId)
            focusId = arena.focus.value?.current
            match = arena.pickMatch(recentIds.toSet(), recentPairs.toSet(), focusId)
        }
        _state.value = if (match == null) {
            ArenaUiState.NotEnough(arena.watchedCount())
        } else {
            round++
            ArenaUiState.Ready(match, round)
        }
    }

    private fun rememberMatch(match: ArenaMatch) {
        recentIds.addLast(match.a.tmdbId)
        recentIds.addLast(match.b.tmdbId)
        while (recentIds.size > RECENT_FILMS) recentIds.removeFirst()
        recentPairs.addLast(Matchmaker.pairKey(match.a.tmdbId, match.b.tmdbId))
        while (recentPairs.size > RECENT_PAIRS) recentPairs.removeFirst()
    }

    private companion object {
        const val UNDO_WINDOW_MS = 3_500L
        const val RESULT_DISPLAY_MS = 600L
        const val RECENT_FILMS = 6
        const val RECENT_PAIRS = 40
    }
}
