package io.github.deedg.cineswipe.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class LibraryUiState(
    val loading: Boolean = true,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.AZ,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sections: List<LibrarySection> = emptyList(),
    /** Vrai si la bibliothèque ne contient aucun film, avant tout filtre. */
    val libraryEmpty: Boolean = false,
    /** Faux sans serveur média : pas de filtre ni de badge « Dispo ». */
    val showAvailability: Boolean = false,
) {
    val visibleCount: Int get() = sections.sumOf { it.rows.size }
}

private data class LibraryControls(
    val query: String,
    val sort: LibrarySort,
    val filter: LibraryFilter,
)

class LibraryViewModel(
    library: LibraryRepository,
    settings: SettingsRepository,
    initialFilter: LibraryFilter = LibraryFilter.ALL,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(LibrarySort.AZ)
    private val filter = MutableStateFlow(initialFilter)

    /** Texte du champ de recherche, mis à jour sans attendre le recalcul de la liste. */
    val queryText: StateFlow<String> = query

    private val controls = combine(query, sort, filter) { q, s, f -> LibraryControls(q, s, f) }

    val state: StateFlow<LibraryUiState> = combine(
        library.observeLibrary(),
        controls,
        settings.homelabConnected,
    ) { items, current, homelab ->
        val sections = LibrarySections.build(items, current.query, current.sort, current.filter, showAvailability = homelab)
        LibraryUiState(
            loading = false,
            query = current.query,
            sort = current.sort,
            filter = if (!homelab && current.filter == LibraryFilter.AVAILABLE) LibraryFilter.ALL else current.filter,
            sections = sections,
            libraryEmpty = items.none { homelab || it.status == WatchStatus.WATCHED || it.status == WatchStatus.WATCHLIST },
            showAvailability = homelab,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: LibrarySort) {
        sort.value = value
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }
}
