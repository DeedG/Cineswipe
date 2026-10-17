package io.github.deedg.cineswipe.domain

import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.TvSeason

/**
 * Temps passé devant une série, estimé à partir de son avancement : finie = tous les épisodes,
 * en cours ou décrochée en saison N = les saisons d'avant et la moitié de la saison N, saison
 * inconnue = la moitié de la série, par épisodes = un épisode sur cinq.
 */
object SeriesTime {
    /** Épisodes par saison quand TMDB ne le dit pas. */
    const val EPISODES_PER_SEASON = 8

    /** Durée d'un épisode quand elle est inconnue. */
    const val EPISODE_MINUTES = 40

    /** Une ligne de série (et pas un film) : des saisons ou un avancement. */
    fun isSeries(seasonCount: Int?, seasons: List<TvSeason>, progress: SeriesProgress?): Boolean =
        seasonCount != null || seasons.isNotEmpty() || progress != null

    fun episodes(seasonCount: Int?, seasons: List<TvSeason>, progress: SeriesProgress?, currentSeason: Int?): Int {
        val regular = seasons.filter { it.number >= 1 }.sortedBy { it.number }
        val count = maxOf(seasonCount ?: 0, regular.size, 1)
        val perSeason = (1..count).associateWith { number ->
            regular.firstOrNull { it.number == number }?.episodeCount?.takeIf { it > 0 } ?: EPISODES_PER_SEASON
        }
        val total = perSeason.values.sum()
        return when (progress) {
            null, SeriesProgress.FINISHED -> total
            SeriesProgress.EPISODES -> maxOf(1, total / 5)
            SeriesProgress.IN_PROGRESS, SeriesProgress.ABANDONED -> {
                val season = currentSeason?.coerceIn(1, count) ?: return maxOf(1, total / 2)
                perSeason.filterKeys { it < season }.values.sum() + maxOf(1, perSeason.getValue(season) / 2)
            }
        }
    }

    /** Minutes : la durée d'un film, ou les épisodes vus d'une série fois la durée d'un épisode. */
    fun minutes(runtimeMinutes: Int?, seasonCount: Int?, seasons: List<TvSeason>, progress: SeriesProgress?, currentSeason: Int?): Int {
        if (!isSeries(seasonCount, seasons, progress)) return runtimeMinutes ?: 0
        return episodes(seasonCount, seasons, progress, currentSeason) * (runtimeMinutes?.takeIf { it > 0 } ?: EPISODE_MINUTES)
    }
}
