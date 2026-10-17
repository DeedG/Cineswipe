package io.github.deedg.cineswipe.ui.census

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.repository.CensusCommit
import io.github.deedg.cineswipe.data.repository.CensusItem
import io.github.deedg.cineswipe.data.repository.CensusProgress
import io.github.deedg.cineswipe.data.repository.CensusRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusFilter
import io.github.deedg.cineswipe.ui.components.SeasonRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CensusPhase { LOADING, READY, EMPTY, NEEDS_KEY, ERROR }

data class CensusUiState(
    val phase: CensusPhase = CensusPhase.LOADING,
    /** Grille : la page affichée. Cartes : la file, la carte affichée en tête. */
    val items: List<CensusItem> = emptyList(),
    val choices: Map<Long, CensusChoice> = emptyMap(),
    val filter: CensusFilter = CensusFilter.ALL,
    val cardsMode: Boolean = false,
    val progress: CensusProgress? = null,
    val canUndo: Boolean = false,
    val busy: Boolean = false,
    /** Depuis l'ouverture de l'écran : films passés en revue, dont vus. */
    val sessionAnswered: Int = 0,
    val sessionSeen: Int = 0,
    /** Séries mises à voir, en attente du choix des saisons à demander (une à la fois). */
    val seasonQueue: List<CensusSeasonAsk> = emptyList(),
    /** Nombre de séries passées dans cette file depuis qu'elle s'est remplie (« 2 / 5 »). */
    val seasonQueueTotal: Int = 0,
) {
    val unanswered: Int get() = items.count { choiceOf(it) == CensusChoice.NONE }

    fun choiceOf(item: CensusItem): CensusChoice = choices[item.movie.tmdbId] ?: CensusChoice.NONE
}

/** Série mise à voir au recensement : quelles saisons demander à [service] ? */
data class CensusSeasonAsk(val movie: MovieEntity, val request: SeasonRequest, val service: HomelabService)

sealed interface CensusEvent {
    data class PageDone(val answered: Int, val seen: Int) : CensusEvent
    data object Undone : CensusEvent
}

/**
 * Recensement : en grille, les réponses d'une page partent ensemble (les films sans réponse
 * comptent « pas vu ») ; en cartes, chaque réponse part tout de suite. « Annuler » remonte les
 * cinq dernières pages ou cartes. En changeant de mode ou de filtre, ou en quittant, seules les
 * réponses données sont enregistrées : rien ne passe en « pas vu » par surprise.
 */
class CensusViewModel(
    private val census: CensusRepository,
    private val settings: SettingsRepository,
    /** Monde des séries : l'avancement se donne au toucher (finie, en cours, par épisodes, décrochée). */
    val series: Boolean = false,
    /** Séries mises à voir : demandes au homelab (Seerr ou Sonarr), saisons au choix. */
    private val homelab: HomelabRepository? = null,
    private val tmdb: TmdbRepository? = null,
) : ViewModel() {

    private val content = MutableStateFlow(CensusUiState())
    val state: StateFlow<CensusUiState> = combine(content, census.observeProgress()) { current, progress ->
        current.copy(progress = progress)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CensusUiState())

    private val _events = Channel<CensusEvent>(Channel.BUFFERED)
    val events: Flow<CensusEvent> = _events.receiveAsFlow()

    private val undoStack = ArrayDeque<CensusCommit>()
    private var job: Job? = null

    /** Réponses en cartes pas encore enregistrées : la page suivante attend la dernière. */
    private var queuedAnswers = 0

    init {
        job = viewModelScope.launch {
            content.update { it.copy(cardsMode = settings.cardsModeOrDefault()) }
            load()
        }
        // Les autres films des sagas déjà vues rejoignent le catalogue, sans bloquer l'écran.
        viewModelScope.launch { census.exploreWatchedSagas() }
    }

    /** Grille : vu → adoré → bof → rien (séries : finie → en cours → par épisodes → décrochée → rien). */
    fun tap(tmdbId: Long) {
        content.update { state ->
            val next = (state.choices[tmdbId] ?: CensusChoice.NONE).next(series)
            state.copy(choices = state.choices + (tmdbId to next))
        }
    }

    /** Grille : réponse choisie dans le menu (à voir, pas pour moi, effacer…). */
    fun choose(tmdbId: Long, choice: CensusChoice) {
        content.update { it.copy(choices = it.choices + (tmdbId to choice)) }
    }

    /** Grille : la page part, les films sans réponse comptent « pas vu ». */
    fun nextPage() {
        val current = content.value
        if (current.cardsMode || current.items.isEmpty() || job?.isActive == true) return
        job = viewModelScope.launch {
            content.update { it.copy(busy = true) }
            val commit = census.commit(current.items, current.choices)
            remember(commit)
            _events.send(CensusEvent.PageDone(commit.answers.size, commit.answers.count { it.value.seen }))
            load()
        }
    }

    /**
     * Cartes : réponse à la carte affichée. La carte part tout de suite ; si la réponse précédente
     * s'enregistre encore, celle-ci attend son tour (deux gestes rapides ne se perdent pas).
     */
    fun answer(choice: CensusChoice) {
        val current = content.value
        val item = current.items.firstOrNull() ?: return
        if (!current.cardsMode) return
        content.update { it.copy(items = it.items.drop(1)) }
        val previous = job
        queuedAnswers++
        job = viewModelScope.launch {
            previous?.join()
            try {
                val commit = census.commit(listOf(item), mapOf(item.movie.tmdbId to choice))
                remember(commit)
            } finally {
                queuedAnswers--
            }
            // Seule la dernière réponse en attente charge la suite : sinon une carte pas encore
            // enregistrée pourrait revenir dans la page suivante.
            if (queuedAnswers == 0 && content.value.items.isEmpty()) load()
        }
    }

    fun undo() {
        if (job?.isActive == true) return
        val commit = undoStack.removeLastOrNull() ?: return
        job = viewModelScope.launch {
            census.undo(commit)
            content.update { state ->
                val answered = commit.answers.size
                val seen = commit.answers.count { it.value.seen }
                val restored = if (state.cardsMode) {
                    state.copy(items = commit.items + state.items.filterNot { item -> commit.items.any { it.movie.tmdbId == item.movie.tmdbId } })
                } else {
                    state.copy(items = commit.items, choices = commit.answers.filterValues { it != CensusChoice.NONE })
                }
                restored.copy(
                    phase = CensusPhase.READY,
                    canUndo = undoStack.isNotEmpty(),
                    sessionAnswered = (state.sessionAnswered - answered).coerceAtLeast(0),
                    sessionSeen = (state.sessionSeen - seen).coerceAtLeast(0),
                )
            }
            _events.send(CensusEvent.Undone)
        }
    }

    fun setFilter(filter: CensusFilter) {
        if (filter == content.value.filter || job?.isActive == true) return
        job = viewModelScope.launch {
            commitDecided()
            content.update { it.copy(filter = filter, items = emptyList(), choices = emptyMap(), phase = CensusPhase.LOADING) }
            load()
        }
    }

    /** Grille ↔ cartes : les réponses déjà données partent, le reste de la page continue dans l'autre mode. */
    fun setCardsMode(cards: Boolean) {
        if (cards == content.value.cardsMode || job?.isActive == true) return
        job = viewModelScope.launch {
            commitDecided()
            settings.setCensusCardsMode(cards)
            content.update { it.copy(cardsMode = cards) }
            if (content.value.items.isEmpty()) load()
        }
    }

    fun retry() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { load() }
    }

    override fun onCleared() {
        val current = content.value
        census.commitLater(current.items, current.choices)
        super.onCleared()
    }

    /** Enregistre seulement les réponses données ; les films sans réponse restent dans la page. */
    private suspend fun commitDecided() {
        val current = content.value
        val decided = current.items.filter { current.choiceOf(it) != CensusChoice.NONE }
        if (decided.isEmpty()) return
        val commit = census.commit(decided, current.choices)
        remember(commit)
        content.update { state -> state.copy(items = state.items.filterNot { it in decided }, choices = emptyMap()) }
    }

    private fun remember(commit: CensusCommit) {
        askSeasons(commit)
        undoStack.addLast(commit)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        content.update { state ->
            state.copy(
                canUndo = true,
                sessionAnswered = state.sessionAnswered + commit.answers.size,
                sessionSeen = state.sessionSeen + commit.answers.count { it.value.seen },
            )
        }
    }

    /** Séries tout juste mises à voir : la feuille des saisons à demander s'ouvre pour chacune. */
    private fun askSeasons(commit: CensusCommit) {
        val homelab = homelab ?: return
        if (!series) return
        val wanted = commit.items.filter { commit.answers[it.movie.tmdbId] == CensusChoice.WATCHLIST }.map { it.movie }
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            val service = settings.homelabConfig.first().requestService ?: return@launch
            val asks = wanted.map { movie ->
                val seasons = movie.seasonCount ?: runCatching { tmdb?.ensureDetails(movie.tmdbId)?.seasonCount }.getOrNull()
                CensusSeasonAsk(movie, SeasonRequest(movie.tmdbId, movie.title, seasons), service)
            }
            content.update { state ->
                val total = if (state.seasonQueue.isEmpty()) asks.size else state.seasonQueueTotal + asks.size
                state.copy(seasonQueue = state.seasonQueue + asks, seasonQueueTotal = total)
            }
        }
    }

    /** Réponse de la feuille des saisons : demande (null = toute la série), ou juste à voir ([seasons] ignoré). */
    fun answerSeasons(request: Boolean, seasons: List<Int>?) {
        val ask = content.value.seasonQueue.firstOrNull() ?: return
        content.update { it.copy(seasonQueue = it.seasonQueue.drop(1)) }
        if (request) viewModelScope.launch { homelab?.requestMovie(ask.movie, seasons) }
    }

    private suspend fun load() {
        content.update { it.copy(busy = true, phase = if (it.items.isEmpty()) CensusPhase.LOADING else it.phase) }
        try {
            val items = census.nextPage(content.value.filter, CensusRepository.PAGE_SIZE)
            content.update {
                it.copy(
                    items = items,
                    choices = emptyMap(),
                    phase = if (items.isEmpty()) CensusPhase.EMPTY else CensusPhase.READY,
                    busy = false,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TmdbException) {
            content.update {
                it.copy(
                    busy = false,
                    phase = if (error is TmdbException.MissingKey || error is TmdbException.InvalidKey) CensusPhase.NEEDS_KEY else CensusPhase.ERROR,
                )
            }
        }
    }

    private suspend fun SettingsRepository.cardsModeOrDefault(): Boolean = censusCardsMode.first()

    private companion object {
        const val MAX_UNDO = 5
    }
}
