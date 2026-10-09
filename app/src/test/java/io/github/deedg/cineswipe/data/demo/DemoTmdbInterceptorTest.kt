package io.github.deedg.cineswipe.data.demo

import io.github.deedg.cineswipe.data.tmdb.TmdbApi
import io.github.deedg.cineswipe.data.tmdb.TmdbCollectionDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieDetailsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPageDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonPageDto
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** TMDB simulé de la démo : chaque requête de l'appli trouve sa réponse dans le catalogue embarqué. */
class DemoTmdbInterceptorTest {

    private val catalog = DemoCatalog.parse(File("src/main/assets/${DemoCatalog.ASSET}").readText())
    private val client = OkHttpClient.Builder().addInterceptor(DemoTmdbInterceptor(catalog)).build()

    private fun get(path: String): Pair<Int, String> =
        client.newCall(Request.Builder().url("https://api.themoviedb.org/3/$path").build()).execute().use { it.code to it.body.string() }

    private fun page(path: String): TmdbPageDto = TmdbApi.json.decodeFromString(TmdbPageDto.serializer(), get(path).second)

    @Test
    fun theCatalogHoldsTheHundredMostVotedFilms() {
        assertEquals(100, catalog.films.size)
        assertTrue(catalog.films.all { it.poster != null && !it.overview.isNullOrBlank() && it.genres.isNotEmpty() })
        val votes = catalog.films.map { it.votes ?: 0 }
        assertEquals(votes.sortedDescending(), votes)
        val known = catalog.films.map { it.id }.toSet()
        assertTrue(DemoSeeder.WATCHED.count { it in known } >= 15)
        assertTrue(DemoSeeder.WATCHLIST.all { it in known })
    }

    @Test
    fun peopleAreFoundByNameAndOpenTheirFilms() {
        val people = TmdbApi.json.decodeFromString(TmdbPersonPageDto.serializer(), get("search/person?query=nolan").second)
        val nolan = people.results.single()
        assertEquals("Christopher Nolan", nolan.name)
        assertEquals("Directing", nolan.knownFor)
        assertEquals(3, nolan.works.size)
        val (code, body) = get("person/${nolan.id}?append_to_response=movie_credits")
        assertEquals(200, code)
        val person = TmdbApi.json.decodeFromString(TmdbPersonDto.serializer(), body)
        assertEquals(6, person.credits.crew.count { it.job == "Director" })
        assertEquals(404, get("person/1").first)
    }

    @Test
    fun detailsAreCompleteFiches() {
        val (code, body) = get("movie/155?language=fr-FR&append_to_response=credits,videos,keywords")
        assertEquals(200, code)
        val dto = TmdbApi.json.decodeFromString(TmdbMovieDetailsDto.serializer(), body)
        assertEquals("Christopher Nolan", dto.credits!!.crew.single { it.job == "Director" }.name)
        assertTrue(dto.credits!!.cast.isNotEmpty())
        assertTrue(dto.videos!!.results.isNotEmpty())

        val entity = TmdbMapper.toEntity(dto, dto.overview, overviewLanguage = "fr", language = "fr-FR", now = 0)
        assertEquals("Christopher Nolan", entity.director)
        assertEquals(263L, entity.collectionId)
        assertEquals(DemoCatalog.personId("Christopher Nolan"), entity.directors.single().id)
        assertNotNull(entity.trailerKey)
        // Fiche à jour : l'appli ne la redemande pas.
        assertFalse(TmdbMapper.needsRefresh(entity, "fr-FR", now = 0))
    }

    @Test
    fun recommendationsStayCloseToTheFilm() {
        val dto = page("movie/155/recommendations?language=fr-FR&page=1")
        assertTrue(dto.results.isNotEmpty())
        assertTrue(dto.results.none { it.id == 155L })
        // Même saga et même réalisateur : Batman Begins et The Dark Knight Rises en tête.
        assertEquals(setOf(272L, 49026L), dto.results.take(2).map { it.id }.toSet())
        assertTrue(page("movie/155/recommendations?page=2").results.isEmpty())
        assertTrue(page("movie/155/similar?page=1").results.isNotEmpty())
    }

    @Test
    fun discoverFollowsItsFilters() {
        val animation = page("discover/movie?with_genres=16&sort_by=vote_count.desc")
        assertTrue(animation.results.isNotEmpty())
        assertTrue(animation.results.all { 16 in it.genreIds })

        val nolan = page("discover/movie?with_crew=${DemoCatalog.personId("Christopher Nolan")}&sort_by=vote_count.desc")
        assertTrue(nolan.results.size >= 4)
        assertEquals(157336L, nolan.results.first().id)

        val nineties = page("discover/movie?primary_release_date.gte=1990-01-01&primary_release_date.lte=1999-12-31")
        assertTrue(nineties.results.isNotEmpty())
        assertTrue(nineties.results.all { it.releaseDate!!.take(4).toInt() in 1990..1999 })

        val french = page("discover/movie?with_original_language=fr")
        assertEquals(listOf(77338L), french.results.map { it.id })

        val all = page("discover/movie?vote_count.gte=200&sort_by=vote_count.desc&page=2")
        assertEquals(100, all.totalResults)
        assertEquals(5, all.totalPages)
        assertEquals(20, all.results.size)
        assertEquals(2, all.page)

        // Pas de mots-clés ni de pépites peu votées dans la démo.
        assertTrue(page("discover/movie?with_keywords=4379").results.isEmpty())
        assertTrue(page("discover/movie?vote_average.gte=7.2&vote_count.gte=150&vote_count.lte=2500").results.isEmpty())
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        assertEquals(3, page("search/movie?query=SEIGNEUR&page=1").results.size)
        assertEquals(listOf(11L), page("search/movie?query=la%20guerre%20des%20etoiles").results.map { it.id })
        assertTrue(page("search/movie?query=zzzz").results.isEmpty())
    }

    @Test
    fun sagasGenresAuthenticationAndUnknownPaths() {
        val saga = TmdbApi.json.decodeFromString(TmdbCollectionDto.serializer(), get("collection/263?language=fr-FR").second)
        assertEquals(listOf(272L, 155L, 49026L), saga.parts.map { it.id })
        assertTrue(get("genre/movie/list?language=fr-FR").second.contains("Science-Fiction"))
        assertTrue(get("authentication").second.contains("true"))
        assertTrue(page("trending/movie/week?page=1").results.isNotEmpty())
        assertEquals(404, get("movie/155/keywords").first)
        assertEquals(404, get("movie/999999999").first)
        assertEquals(404, get("collection/999999999").first)
    }
}
