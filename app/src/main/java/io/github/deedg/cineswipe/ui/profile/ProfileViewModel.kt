package io.github.deedg.cineswipe.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.model.LibraryItem
import io.github.deedg.cineswipe.data.model.LibraryStats
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.domain.StarScale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

data class HallOfFameEntry(
    val rank: Int,
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val rating: Int,
    val stars: Float,
    val provisional: Boolean,
)

data class ProfileUiState(
    val loading: Boolean = true,
    val stats: LibraryStats = LibraryStats(watched = 0, watchlist = 0, available = 0, duels = 0),
    val hallOfFame: List<HallOfFameEntry> = emptyList(),
    /** Le compteur « Dispo sur ton serveur » n'apparaît qu'avec un serveur média connecté. */
    val showAvailability: Boolean = false,
)

class ProfileViewModel(library: LibraryRepository, settings: SettingsRepository) : ViewModel() {

    val state: StateFlow<ProfileUiState> = combine(
        library.observeStats(),
        library.observeLibrary(),
        settings.homelabConnected,
    ) { stats, items, homelab ->
        ProfileUiState(loading = false, stats = stats, hallOfFame = hallOfFame(items), showAvailability = homelab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private fun hallOfFame(items: List<LibraryItem>): List<HallOfFameEntry> {
        val watched = items.filter { it.status == WatchStatus.WATCHED }
        val stars = StarScale.computeStars(watched.associate { it.tmdbId to it.rating })
        return watched
            .sortedByDescending { it.rating }
            .take(HALL_OF_FAME_SIZE)
            .mapIndexed { index, item ->
                HallOfFameEntry(
                    rank = index + 1,
                    tmdbId = item.tmdbId,
                    title = item.title,
                    year = item.year,
                    posterPath = item.posterPath,
                    rating = item.rating.roundToInt(),
                    stars = stars[item.tmdbId] ?: 1f,
                    provisional = StarScale.isProvisional(item.confidence),
                )
            }
    }

    private companion object {
        const val HALL_OF_FAME_SIZE = 3
    }
}
