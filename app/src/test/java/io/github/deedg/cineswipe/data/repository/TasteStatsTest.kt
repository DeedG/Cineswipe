package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonCreditDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonCreditsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonDto
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteStatsTest {

    private fun row(
        id: Long,
        rating: Double,
        genres: List<String>,
        year: Int,
        director: TmdbTag? = null,
        tmdb: Double = 7.0,
        runtime: Int = 120,
        cast: List<CastMember> = emptyList(),
    ) = StatsRow(
        tmdbId = id,
        title = "Film $id",
        year = year,
        posterPath = "/$id.jpg",
        runtimeMinutes = runtime,
        genres = genres,
        keywords = emptyList(),
        directors = listOfNotNull(director),
        cast = cast,
        originalLanguage = if (id % 2 == 0L) "fr" else "en",
        tmdbRating = tmdb,
        voteCount = 1_000,
        rating = rating,
    )

    @Test
    fun genresDirectorsAndDecadesFollowTheRanking() {
        val nolan = TmdbTag(525, "Christopher Nolan")
        val other = TmdbTag(1, "Quelqu'un")
        val actor = CastMember("Acteur Fétiche", "/a.jpg", 42)
        val rows = buildList {
            // Science-fiction en haut du classement, comédies en bas.
            repeat(8) { add(row(it.toLong() + 1, 1800.0 + it * 10, listOf("Science-Fiction"), 1990 + it, nolan, cast = listOf(actor))) }
            repeat(8) { add(row(it.toLong() + 100, 1200.0 + it * 10, listOf("Comédie"), 2010 + it, other)) }
            // Un seul western excellent : pas assez de films pour compter comme genre.
            add(row(500, 2000.0, listOf("Western"), 1965, tmdb = 5.5))
            // Un drame encensé sur TMDB mais tout en bas du classement.
            add(row(600, 1000.0, listOf("Drame"), 2001, tmdb = 8.4))
        }
        val stats = TasteStats.compute(rows, duels = 42)

        assertEquals(18, stats.watched)
        assertEquals(18 * 2, stats.hours)
        assertEquals(42, stats.duels)
        assertEquals(listOf("Science-Fiction", "Comédie"), stats.genres.map { it.name })
        assertTrue(stats.genres.first().averageStars > stats.genres.last().averageStars)
        // Prudence : le score est plus proche de la moyenne que la moyenne brute.
        assertTrue(stats.genres.first().score < stats.genres.first().averageStars)
        assertEquals("Christopher Nolan", stats.directors.first().name)
        assertEquals(525L, stats.directors.first().personId)
        assertEquals(8, stats.mostWatchedDirector?.films)
        assertEquals("Acteur Fétiche", stats.actors.single().name)
        assertEquals("/a.jpg", stats.actors.single().imagePath)
        assertEquals(listOf("1960", "1990", "2000", "2010"), stats.decades.map { it.key })
        assertEquals("1990", stats.favoriteDecade?.key)
        assertEquals(listOf(500L), stats.lovedAgainst.map { it.tmdbId })
        assertEquals(listOf(600L), stats.dislikedAgainst.map { it.tmdbId })
        assertEquals(setOf("fr", "en"), stats.languages.map { it.key }.toSet())
    }

    @Test
    fun emptyLibrary() {
        val stats = TasteStats.compute(emptyList(), duels = 0)
        assertEquals(0, stats.watched)
        assertTrue(stats.genres.isEmpty())
        assertNull(stats.favoriteDecade)
    }

    @Test
    fun filmographyMergesRolesAndKeepsDirectorsOnly() {
        fun credit(id: Long, character: String? = null, job: String? = null, votes: Int = 10) =
            TmdbPersonCreditDto(id = id, title = "Film $id", releaseDate = "2001-02-03", voteCount = votes, character = character, job = job)
        val page = PeopleRepository.toPage(
            TmdbPersonDto(
                id = 9,
                name = "Clint",
                knownFor = "Acting",
                birthday = "1930-05-31",
                credits = TmdbPersonCreditsDto(
                    cast = listOf(credit(1, character = "Blondin"), credit(2, character = "Harry")),
                    crew = listOf(credit(2, job = "Director"), credit(3, job = "Director"), credit(4, job = "Producer")),
                ),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), page.films.map { it.tmdbId })
        val both = page.films.single { it.tmdbId == 2L }
        assertTrue(both.acted && both.directed)
        assertEquals("Harry", both.character)
        assertFalse(page.films.single { it.tmdbId == 3L }.acted)
        assertTrue(page.actsAndDirects)
        assertEquals(1930, page.person.birthYear)
        assertEquals(2001, page.films.first().year)
    }
}
