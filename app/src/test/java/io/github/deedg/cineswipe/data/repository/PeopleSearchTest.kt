package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.tmdb.TmdbKnownForDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonHitDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonPageDto
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleSearchTest {

    private fun movie(title: String) = TmdbKnownForDto(title = title, mediaType = "movie")

    @Test
    fun hitsKeepFilmsOnlyAndPutPeopleWithFilmsFirst() {
        val page = TmdbPersonPageDto(
            results = listOf(
                TmdbPersonHitDto(id = 1, name = "Inconnu"),
                TmdbPersonHitDto(id = 2, name = "Adulte", adult = true, works = listOf(movie("X"))),
                TmdbPersonHitDto(
                    id = 3,
                    name = "Christopher Nolan",
                    profilePath = "/n.jpg",
                    knownFor = "Directing",
                    works = listOf(movie("Inception"), TmdbKnownForDto(name = "Une série", mediaType = "tv"), movie("Interstellar"), movie("Inception")),
                ),
                TmdbPersonHitDto(id = 4, name = " "),
            ),
        )
        val hits = PeopleRepository.toHits(page)
        assertEquals(listOf(3L, 1L), hits.map { it.id })
        assertEquals(listOf("Inception", "Interstellar"), hits.first().works)
        assertEquals("Directing", hits.first().knownFor)
    }

    @Test
    fun atMostSixPeople() {
        val page = TmdbPersonPageDto(results = (1L..10L).map { TmdbPersonHitDto(id = it, name = "P$it", works = listOf(movie("F$it"))) })
        assertEquals((1L..6L).toList(), PeopleRepository.toHits(page).map { it.id })
    }
}
