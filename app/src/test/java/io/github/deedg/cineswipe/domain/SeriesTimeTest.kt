package io.github.deedg.cineswipe.domain

import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.TvSeason
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesTimeTest {
    private val seasons = listOf(TvSeason(0, episodeCount = 5), TvSeason(1, episodeCount = 10), TvSeason(2, episodeCount = 8))

    @Test
    fun episodesFollowTheProgress() {
        assertEquals(18, SeriesTime.episodes(2, seasons, SeriesProgress.FINISHED, null))
        // Saison 2 en cours : la saison 1 et la moitié de la 2 (les épisodes spéciaux ne comptent pas).
        assertEquals(14, SeriesTime.episodes(2, seasons, SeriesProgress.IN_PROGRESS, 2))
        assertEquals(9, SeriesTime.episodes(2, seasons, SeriesProgress.ABANDONED, null))
        assertEquals(3, SeriesTime.episodes(2, seasons, SeriesProgress.EPISODES, null))
        // Sans détail des saisons : 8 épisodes par saison.
        assertEquals(24, SeriesTime.episodes(3, emptyList(), null, null))
    }

    @Test
    fun minutesOfAFilmOrASeries() {
        assertEquals(120, SeriesTime.minutes(120, null, emptyList(), null, null))
        assertEquals(18 * 50, SeriesTime.minutes(50, 2, seasons, SeriesProgress.FINISHED, null))
        assertEquals(18 * SeriesTime.EPISODE_MINUTES, SeriesTime.minutes(null, 2, seasons, null, null))
    }
}
