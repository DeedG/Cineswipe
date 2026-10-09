package io.github.deedg.cineswipe.data.importer

import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportMergeTest {

    private val movie = MovieEntity(tmdbId = 10, title = "Dune", year = 2021)

    private fun entry(watched: Boolean = true, watchlist: Boolean = false, rating: Double? = null, at: Long? = 100) =
        ImportEntry(movie, watched = watched, watchlist = watchlist, rating10 = rating, watchedAt = at)

    @Test
    fun newWatchedFilmKeepsImportDateAndRating() {
        val (row, change) = ImportMerger.merge(null, entry(rating = 8.0), now = 999)
        assertEquals(ImportChange.WATCHED, change)
        assertEquals(WatchStatus.WATCHED, row!!.status)
        assertEquals(100L, row.watchedAt)
        assertEquals(WatchSource.IMPORT, row.watchedSource)
        assertEquals(8.0, row.importedRating!!, 0.0)
    }

    @Test
    fun watchedFilmIsNeverDowngradedAndKeepsItsDate() {
        val current = UserMovieEntity(tmdbId = 10, status = WatchStatus.WATCHED, watchedAt = 50, watchedSource = WatchSource.MANUAL, updatedAt = 1)
        assertEquals(null to ImportChange.NONE, ImportMerger.merge(current, entry(watched = false, watchlist = true), now = 999))
        assertEquals(null to ImportChange.NONE, ImportMerger.merge(current, entry(), now = 999))
        val (rated, change) = ImportMerger.merge(current, entry(rating = 6.0), now = 999)
        assertEquals(ImportChange.RATED, change)
        assertEquals(50L, rated!!.watchedAt)
        assertEquals(WatchSource.MANUAL, rated.watchedSource)
    }

    @Test
    fun watchlistOnlyForUnknownFilms() {
        assertEquals(ImportChange.WATCHLIST, ImportMerger.merge(null, entry(watched = false, watchlist = true), now = 1).second)
        val dismissed = UserMovieEntity(tmdbId = 10, status = WatchStatus.DISMISSED, updatedAt = 1)
        assertEquals(ImportChange.NONE, ImportMerger.merge(dismissed, entry(watched = false, watchlist = true), now = 1).second)
        // Vu ailleurs : un film écarté ici passe dans les vus.
        assertEquals(ImportChange.WATCHED, ImportMerger.merge(dismissed, entry(), now = 1).second)
    }

    private fun dto(id: Long, title: String, year: Int?, original: String? = null) =
        TmdbMovieSummaryDto(id = id, title = title, originalTitle = original ?: title, releaseDate = year?.let { "$it-06-01" })

    @Test
    fun matcherPrefersSameTitleAndYear() {
        val results = listOf(dto(1, "Dune", 1984), dto(2, "Dune", 2021), dto(3, "Dune : Deuxième partie", 2024))
        assertEquals(2L, ImportMatcher.best("Dune", 2021, results)?.id)
        assertEquals(1L, ImportMatcher.best("Dune", 1984, results)?.id)
        // Sans année : le premier titre identique.
        assertEquals(1L, ImportMatcher.best("Dune", null, results)?.id)
    }

    @Test
    fun matcherUsesOriginalTitleArticlesAndToleratesOneYear() {
        val amelie = dto(194, "Le Fabuleux Destin d'Amélie Poulain", 2001)
        assertEquals(194L, ImportMatcher.best("Fabuleux destin d'Amelie Poulain", 2001, listOf(amelie))?.id)
        val intouchables = dto(77338, "Intouchables", 2011, original = "Intouchables")
        assertEquals(77338L, ImportMatcher.best("The Intouchables", 2011, listOf(intouchables))?.id)
        // Sortie en festival l'année d'avant.
        assertEquals(5L, ImportMatcher.best("Parasite", 2019, listOf(dto(5, "Parasite", 2018)))?.id)
        val translated = dto(9, "Le Voyage de Chihiro", 2001, original = "千と千尋の神隠し")
        assertEquals(9L, ImportMatcher.best("Spirited Away", 2001, listOf(translated))?.id)
    }

    @Test
    fun matcherRejectsWrongYearsAndUnrelatedTitles() {
        assertNull(ImportMatcher.best("Dune", 2021, listOf(dto(1, "Dune", 1984))))
        assertNull(ImportMatcher.best("It", 2017, listOf(dto(2, "Split", 2016))))
        // Titre différent : seul le premier résultat de la bonne année est retenu.
        assertNull(ImportMatcher.best("Spirited Away", 2001, listOf(dto(3, "Autre film", 2000), dto(9, "Le Voyage de Chihiro", 2001))))
    }

    @Test
    fun entriesForTheSameFilmAreCombined() {
        val combined = ImportMerger.combine(
            listOf(
                entry(watched = false, watchlist = true, rating = null, at = null),
                entry(watched = true, rating = 8.0, at = 300),
                entry(watched = true, rating = null, at = 200),
                ImportEntry(MovieEntity(tmdbId = 11, title = "Autre"), watched = false, watchlist = true, rating10 = null, watchedAt = null),
            ),
        )
        assertEquals(2, combined.size)
        val dune = combined.single { it.tmdbId == 10L }
        assertEquals(true, dune.watched)
        assertEquals(false, dune.watchlist)
        assertEquals(8.0, dune.rating10!!, 0.0)
        assertEquals(200L, dune.watchedAt)
        assertEquals(true, combined.single { it.tmdbId == 11L }.watchlist)
    }
}
