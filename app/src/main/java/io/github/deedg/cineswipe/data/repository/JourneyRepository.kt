package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.domain.journey.Journey
import io.github.deedg.cineswipe.domain.journey.JourneyState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Parcours Recenser → Arène → Recos, calculé à partir de ce que l'utilisateur a déjà fait.
 * [enabled] faux (démo) : parcours considéré terminé.
 */
class JourneyRepository(
    database: CineSwipeDatabase,
    private val settings: SettingsRepository,
    series: Boolean,
    enabled: Boolean,
) {
    val state: Flow<JourneyState> = if (!enabled) {
        flowOf(JourneyState.DONE)
    } else {
        // Les compteurs du profil : films vus, et duels (ceux hérités d'une ancienne version compris).
        combine(database.userMovieDao().observeStats(), settings.journeySkipped) { stats, skipped ->
            Journey.state(series, stats.watched, stats.duels, skipped)
        }.distinctUntilChanged()
    }

    /** « Voir les recos quand même » : elles s'ouvrent, le parcours reste affiché sur les autres onglets. */
    suspend fun skip() {
        settings.setJourneySkipped(true)
    }
}
