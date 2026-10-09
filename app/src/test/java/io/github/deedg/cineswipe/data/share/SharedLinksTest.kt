package io.github.deedg.cineswipe.data.share

import io.github.deedg.cineswipe.data.repository.RecoRepository
import io.github.deedg.cineswipe.ui.tonight.formatRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinksTest {

    @Test
    fun tmdbAndImdbLinksGiveIds() {
        assertEquals(SharedQuery(tmdbId = 438631), SharedLinks.parse("https://www.themoviedb.org/movie/438631-dune?language=fr"))
        assertEquals("tt1160419", SharedLinks.parse("Dune (2021) - IMDb\nhttps://m.imdb.com/fr/title/tt1160419/?ref_=ext_shr").imdbId)
        assertEquals("tt0780504", SharedLinks.parse("https://www.imdb.com/title/tt0780504/").imdbId)
    }

    @Test
    fun letterboxdLinksKeepTheUrlAndATitle() {
        val film = SharedLinks.parse("https://letterboxd.com/film/dune-part-two/")
        assertEquals("https://letterboxd.com/film/dune-part-two/", film.letterboxdUrl)
        assertEquals("Dune Part Two", film.title)
        assertNull(film.year)

        val withYear = SharedLinks.parse("https://letterboxd.com/alex/film/dune-2021/")
        assertEquals("Dune", withYear.title)
        assertEquals(2021, withYear.year)

        val short = SharedLinks.parse("Dune: Part Two (2024) on Letterboxd https://boxd.it/AsPu")
        assertEquals("https://boxd.it/AsPu", short.letterboxdUrl)
        assertEquals("Dune: Part Two", short.title)
        assertEquals(2024, short.year)
    }

    @Test
    fun otherSitesAndPlainTitles() {
        val sens = SharedLinks.parse("https://www.senscritique.com/film/le_fabuleux_destin_d_amelie_poulain/363185")
        assertEquals("Le Fabuleux Destin D Amelie Poulain", sens.title)
        val justwatch = SharedLinks.parse("https://www.justwatch.com/fr/film/interstellar")
        assertEquals("Interstellar", justwatch.title)

        assertEquals(SharedQuery(title = "Interstellar", year = 2014), SharedLinks.parse("Interstellar (2014)"))
        assertEquals(SharedQuery(title = "Drive", year = 2011), SharedLinks.parse("Regarde « Drive » (2011) sur SensCritique"))
        assertEquals(SharedQuery(title = "Le Voyage de Chihiro"), SharedLinks.parse("  Le Voyage de Chihiro  "))
    }

    @Test
    fun slugsAndLetterboxdPages() {
        assertEquals("The Dark Knight" to 2008, SharedLinks.fromSlug("the-dark-knight-2008"))
        assertEquals(438631L, SharedLinks.tmdbIdInLetterboxdPage("""<body class="film" data-tmdb-id="438631" data-tmdb-type="movie">"""))
        assertNull(SharedLinks.tmdbIdInLetterboxdPage("<html></html>"))
    }

    @Test
    fun tonightDailyNoiseIsStableWithinADayAndBounded() {
        val values = (1L..500L).map { RecoRepository.dailyNoise(it, 20_000) }
        assertTrue(values.all { it in -1.0..1.0 })
        assertEquals(RecoRepository.dailyNoise(42, 20_000), RecoRepository.dailyNoise(42, 20_000), 0.0)
        assertTrue(values.distinct().size > 450)
        assertTrue((1L..50L).any { RecoRepository.dailyNoise(it, 20_000) != RecoRepository.dailyNoise(it, 20_001) })
        assertEquals("1 h 52", formatRuntime(112))
        assertEquals("45 min", formatRuntime(45))
        assertEquals("2 h 05", formatRuntime(125))
    }
}
