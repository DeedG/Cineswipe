package io.github.deedg.cineswipe.data.importer

import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.TvSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesImportTest {
    private fun file(name: String, text: String) = ImportFile(name, text.toByteArray())

    private val watchedShows = file(
        "watched-shows.json",
        """
        [
          {"plays": 20, "last_watched_at": "2024-03-01T20:00:00.000Z",
           "show": {"title": "Breaking Bad", "year": 2008, "ids": {"tmdb": 1396, "imdb": "tt0903747"}},
           "seasons": [{"number": 1, "episodes": [{"number": 1}, {"number": 2}]}, {"number": 3, "episodes": [{"number": 1}]}]},
          {"plays": 2, "last_watched_at": "2024-03-02T20:00:00.000Z",
           "movie": {"title": "Dune", "year": 2021, "ids": {"tmdb": 438631}}}
        ]
        """.trimIndent(),
    )

    @Test
    fun traktShowsAreReadInTheSeriesWorldOnly() {
        val series = ImportFormats.parse(listOf(watchedShows), series = true)
        val show = series.items.single()
        assertEquals(1396L, show.tmdbId)
        assertTrue(show.watched)
        assertEquals(3, show.season)
        assertEquals(1, show.seasonEpisodes)
        // En mode films : le film seulement.
        assertEquals(listOf(438631L), ImportFormats.parse(listOf(watchedShows)).items.map { it.tmdbId })
    }

    @Test
    fun imdbSeriesAreReadInTheSeriesWorldOnly() {
        val csv = file(
            "ratings.csv",
            "Const,Your Rating,Date Rated,Title,Original Title,Title Type,Year\n" +
                "tt0903747,10,2024-01-01,Breaking Bad,Breaking Bad,TV Series,2008\n" +
                "tt1475582,9,2024-01-02,Sherlock,Sherlock,TV Mini Series,2010\n" +
                "tt1160419,8,2024-01-03,Dune,Dune,Movie,2021\n",
        )
        assertEquals(listOf("tt0903747", "tt1475582"), ImportFormats.parse(listOf(csv), series = true).items.map { it.imdbId })
        assertEquals(listOf("tt1160419"), ImportFormats.parse(listOf(csv)).items.map { it.imdbId })
    }

    @Test
    fun traktProgressFollowsTheLastSeasonStarted() {
        val show = MovieEntity(
            tmdbId = 1,
            title = "Show",
            seasonCount = 3,
            seasons = listOf(TvSeason(1, episodeCount = 10), TvSeason(2, episodeCount = 10), TvSeason(3, episodeCount = 8)),
        )
        assertEquals(SeriesProgress.IN_PROGRESS to 2, ImportProgress.of(2, 10, show))
        assertEquals(SeriesProgress.IN_PROGRESS to 3, ImportProgress.of(3, 5, show))
        assertEquals(SeriesProgress.FINISHED to null, ImportProgress.of(3, 8, show))
        assertEquals(SeriesProgress.IN_PROGRESS to 1, ImportProgress.of(1, 3, MovieEntity(tmdbId = 2, title = "?")))
    }
}
