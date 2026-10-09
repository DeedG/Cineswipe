package io.github.deedg.cineswipe.ui.library

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySectionsTest {

    private fun item(
        id: Long,
        title: String,
        rating: Double = 1500.0,
        status: WatchStatus = WatchStatus.WATCHED,
        availability: Availability = Availability.NONE,
    ) = LibraryItem(
        tmdbId = id,
        title = title,
        year = 2000,
        posterPath = null,
        status = status,
        rating = rating,
        duels = 5,
        confidence = 0.9,
        availability = availability,
        watchedAt = null,
    )

    private val items = listOf(
        item(1, "Élémentaire", rating = 1550.0),
        item(2, "Avatar", rating = 1620.0),
        item(3, "2001 : l'odyssée de l'espace", rating = 1490.0),
        item(4, "Babylon", status = WatchStatus.NONE, availability = Availability.AVAILABLE),
        item(5, "alien", rating = 1610.0),
    )

    @Test
    fun alphabeticalSectionsIgnoreAccentsAndPutDigitsLast() {
        val sections = LibrarySections.build(items, query = "", sort = LibrarySort.AZ, filter = LibraryFilter.ALL)
        assertEquals(listOf("A", "B", "E", "#"), sections.map { it.label })
        assertEquals(listOf("alien", "Avatar"), sections.first().rows.map { it.title })
    }

    @Test
    fun eloSectionsGoFromBestToWorstWithUnrankedLast() {
        val sections = LibrarySections.build(items, query = "", sort = LibrarySort.ELO, filter = LibraryFilter.ALL)
        assertEquals(listOf(1600, 1500, 1400, null), sections.map { it.eloFloor })
        assertEquals(listOf("Avatar", "alien"), sections.first().rows.map { it.title })
        assertEquals(LibrarySections.UNRANKED_KEY, sections.last().key)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val sections = LibrarySections.build(items, query = "ELEM", sort = LibrarySort.AZ, filter = LibraryFilter.ALL)
        assertEquals(listOf("Élémentaire"), sections.flatMap { it.rows }.map { it.title })
    }

    @Test
    fun filtersKeepOnlyMatchingFilms() {
        val available = LibrarySections.build(items, query = "", sort = LibrarySort.AZ, filter = LibraryFilter.AVAILABLE)
        assertEquals(listOf("Babylon"), available.flatMap { it.rows }.map { it.title })
        val watched = LibrarySections.build(items, query = "", sort = LibrarySort.AZ, filter = LibraryFilter.WATCHED)
        assertEquals(4, watched.sumOf { it.rows.size })
    }

    @Test
    fun unwatchedFilmsHaveNoRatingOrStars() {
        val rows = LibrarySections.build(items, query = "baby", sort = LibrarySort.AZ, filter = LibraryFilter.ALL).flatMap { it.rows }
        assertNull(rows.single().rating)
        assertNull(rows.single().stars)
    }

    @Test
    fun initialsAreNormalized() {
        assertEquals("E", LibrarySections.initialOf("Été meurtrier"))
        assertEquals("#", LibrarySections.initialOf("300"))
        assertEquals("#", LibrarySections.initialOf(""))
    }

    @Test
    fun yearSectionsGoByDecadeFromNewestWithUnknownYearLast() {
        val dated = listOf(
            item(1, "Dune").copy(year = 2021),
            item(2, "Arrival").copy(year = 2016),
            item(3, "Blade Runner 2049").copy(year = 2017),
            item(4, "Léon").copy(year = 1994),
            item(5, "Sans date").copy(year = null),
        )
        val sections = LibrarySections.build(dated, query = "", sort = LibrarySort.YEAR, filter = LibraryFilter.ALL)
        assertEquals(listOf(2020, 2010, 1990, null), sections.map { it.decade })
        assertEquals(listOf("’20", "’10", "’90", "?"), sections.map { it.indexLabel })
        assertEquals(listOf("Blade Runner 2049", "Arrival"), sections[1].rows.map { it.title })
        assertEquals(LibrarySections.UNKNOWN_YEAR_KEY, sections.last().key)
    }

    @Test
    fun sortButtonCyclesThroughAllSorts() {
        assertEquals(LibrarySort.ELO, LibrarySort.AZ.next())
        assertEquals(LibrarySort.YEAR, LibrarySort.ELO.next())
        assertEquals(LibrarySort.AZ, LibrarySort.YEAR.next())
    }

    @Test
    fun withoutMediaServerAvailabilityDisappears() {
        val withServerStatus = items + item(6, "Demandé", status = WatchStatus.WATCHLIST, availability = Availability.REQUESTED)
        val sections = LibrarySections.build(
            withServerStatus,
            query = "",
            sort = LibrarySort.AZ,
            filter = LibraryFilter.AVAILABLE,
            showAvailability = false,
        )
        val rows = sections.flatMap { it.rows }
        // « Babylon » n'était que dispo sur le serveur : il disparaît, et le filtre Dispo revient à « Tous ».
        assertEquals(setOf("Élémentaire", "Avatar", "2001 : l'odyssée de l'espace", "alien", "Demandé"), rows.map { it.title }.toSet())
        assertTrue(rows.none { it.available || it.requested })
        assertTrue(rows.single { it.title == "Demandé" }.watchlist)
    }

    @Test
    fun decadeHelpers() {
        assertEquals(1990, LibrarySections.decadeOf(1999))
        assertEquals(2000, LibrarySections.decadeOf(2000))
        assertEquals("’00", LibrarySections.decadeIndexLabel(2000))
        assertEquals("’20", LibrarySections.decadeIndexLabel(1920))
    }
}
