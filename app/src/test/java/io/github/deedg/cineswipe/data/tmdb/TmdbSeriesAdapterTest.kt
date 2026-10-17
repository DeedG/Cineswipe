package io.github.deedg.cineswipe.data.tmdb

import io.github.deedg.cineswipe.data.db.TvSeason
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Traduction des requêtes et réponses TMDB pour le monde des séries. */
class TmdbSeriesAdapterTest {

    private fun translate(url: String) = TmdbSeriesAdapter.translateUrl(url.toHttpUrl()).toString()

    @Test
    fun movieEndpointsBecomeTvEndpoints() {
        val base = "https://api.themoviedb.org/3"
        assertEquals("$base/tv/1399?language=fr-FR&append_to_response=aggregate_credits%2Cvideos%2Ckeywords", translate("$base/movie/1399?language=fr-FR&append_to_response=credits,videos,keywords"))
        assertEquals("$base/tv/1399/recommendations?page=2", translate("$base/movie/1399/recommendations?page=2"))
        assertEquals("$base/tv/1399/watch/providers", translate("$base/movie/1399/watch/providers"))
        assertEquals("$base/genre/tv/list?language=fr", translate("$base/genre/movie/list?language=fr"))
        assertEquals("$base/trending/tv/week", translate("$base/trending/movie/week"))
        assertEquals("$base/search/tv?query=lost&first_air_date_year=2004", translate("$base/search/movie?query=lost&year=2004"))
        assertEquals(
            "$base/discover/tv?sort_by=first_air_date.desc&first_air_date.gte=1990-01-01&with_runtime.lte=60",
            translate("$base/discover/movie?sort_by=primary_release_date.desc&primary_release_date.gte=1990-01-01&with_runtime.lte=60&with_release_type=3"),
        )
        // Une personne garde son adresse, mais demande ses séries.
        assertEquals("$base/person/7?append_to_response=tv_credits", translate("$base/person/7?append_to_response=movie_credits"))
        // Les points d'accès sans équivalent restent tels quels.
        assertEquals("$base/authentication", translate("$base/authentication"))
    }

    @Test
    fun showDetailsLookLikeAMovie() {
        val body = """{"id":1399,"name":"Game of Thrones","original_name":"Game of Thrones","first_air_date":"2011-04-17",
            "last_air_date":"2019-05-19","origin_country":["US"],"episode_run_time":[],"last_episode_to_air":{"runtime":80},
            "number_of_seasons":8,"status":"Ended","created_by":[{"id":9813,"name":"David Benioff"},{"id":228068,"name":"D. B. Weiss"}],
            "aggregate_credits":{"cast":[{"id":22970,"name":"Peter Dinklage","original_name":"Peter Dinklage","profile_path":"/p.jpg","order":0,"roles":[]}],"crew":[]},
            "keywords":{"results":[{"id":6091,"name":"war"}]},
            "seasons":[{"season_number":0,"episode_count":20},{"season_number":1,"name":"Saison 1","episode_count":10,"air_date":"2011-04-17"},{"season_number":2,"episode_count":10,"air_date":"2012-04-01"}]}"""
        val url = "https://api.themoviedb.org/3/tv/1399?language=fr-FR".toHttpUrl()
        val dto = TmdbApi.json.decodeFromString(TmdbMovieDetailsDto.serializer(), TmdbSeriesAdapter.translateBody(url, body))
        val entity = TmdbMapper.toEntity(dto, overview = "Synopsis", overviewLanguage = "fr", language = "fr-FR", now = 1)
        assertEquals("Game of Thrones", entity.title)
        assertEquals(2011, entity.year)
        assertEquals(2019, entity.endYear)
        assertEquals(80, entity.runtimeMinutes)
        assertEquals(8, entity.seasonCount)
        assertEquals("Ended", entity.airStatus)
        assertEquals("David Benioff, D. B. Weiss", entity.director)
        assertEquals(listOf(9813L, 228068L), entity.directors.map { it.id })
        assertEquals("Peter Dinklage", entity.cast.single().name)
        assertEquals(listOf("war"), entity.keywords.map { it.name })
        // Sans les épisodes spéciaux.
        assertEquals(listOf(TvSeason(1, "Saison 1", 10, 2011), TvSeason(2, null, 10, 2012)), entity.seasons)
        assertNull(entity.collectionId)
    }

    @Test
    fun episodeRuntimeIgnoresFinales() {
        fun runtime(json: String) = TmdbSeriesAdapter.episodeRuntime(TmdbApi.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
        assertEquals(50, runtime("""{"episode_run_time":[60,42,50]}"""))
        assertNull(runtime("""{"episode_run_time":[],"last_episode_to_air":{"runtime":129,"episode_type":"finale"}}"""))
        assertEquals(48, runtime("""{"episode_run_time":[],"last_episode_to_air":{"runtime":129,"episode_type":"finale"},"next_episode_to_air":{"runtime":48,"episode_type":"standard"}}"""))
        assertEquals(22, runtime("""{"last_episode_to_air":{"runtime":22}}"""))
        // Un final court garde une durée d'épisode (Breaking Bad : 55 min).
        assertEquals(55, runtime("""{"episode_run_time":[],"last_episode_to_air":{"runtime":55,"episode_type":"finale"}}"""))
    }

    @Test
    fun listsAndPeopleUseMovieFields() {
        val page = """{"page":1,"results":[{"id":1,"name":"Dark","original_name":"Dark","first_air_date":"2017-12-01","origin_country":["DE"],"genre_ids":[18]}]}"""
        val dto = TmdbApi.json.decodeFromString(
            TmdbPageDto.serializer(),
            TmdbSeriesAdapter.translateBody("https://api.themoviedb.org/3/discover/tv".toHttpUrl(), page),
        )
        assertEquals("Dark", dto.results.single().title)
        assertEquals("2017-12-01", dto.results.single().releaseDate)

        val person = """{"id":7,"name":"Créatrice","tv_credits":{
            "cast":[{"id":1,"name":"Dark","first_air_date":"2017-12-01","origin_country":["DE"],"character":"Martha"},
                    {"id":2,"name":"Talk Show","first_air_date":"2000-01-01","origin_country":["US"],"genre_ids":[10767],"character":"Guest"},
                    {"id":3,"name":"Docu","first_air_date":"2001-01-01","origin_country":["US"],"character":"Herself"}],
            "crew":[{"id":4,"name":"1899","first_air_date":"2022-11-17","origin_country":["DE"],"job":"Creator"}]}}"""
        val credits = TmdbApi.json.decodeFromString(
            TmdbPersonDto.serializer(),
            TmdbSeriesAdapter.translateBody("https://api.themoviedb.org/3/person/7".toHttpUrl(), person),
        ).credits
        // Les plateaux télé et les apparitions dans son propre rôle disparaissent.
        assertEquals(listOf("Dark"), credits.cast.map { it.title })
        assertEquals("Director", credits.crew.single().job)
        assertEquals("2022-11-17", credits.crew.single().releaseDate)

        // Une personne sans séries n'a aucune œuvre (pas ses films).
        val actor = """{"id":8,"name":"Acteur","movie_credits":{"cast":[{"id":5,"title":"Un film"}],"crew":[]}}"""
        val none = TmdbApi.json.decodeFromString(
            TmdbPersonDto.serializer(),
            TmdbSeriesAdapter.translateBody("https://api.themoviedb.org/3/person/8".toHttpUrl(), actor),
        ).credits
        assertTrue(none.cast.isEmpty())
    }

    @Test
    fun discoverByPersonGoesThroughTheirCredits() {
        val url = "https://api.themoviedb.org/3/discover/movie?with_crew=7&sort_by=vote_count.desc&page=1&language=fr-FR&api_key=k".toHttpUrl()
        val discover = checkNotNull(TmdbSeriesAdapter.personDiscover(url))
        assertEquals(7L, discover.personId)
        assertTrue(discover.asCrew)
        assertEquals(
            "https://api.themoviedb.org/3/person/7/tv_credits?language=fr-FR&api_key=k",
            TmdbSeriesAdapter.personCreditsUrl(url, discover).toString(),
        )
        val credits = """{"cast":[],"crew":[
            {"id":1,"name":"Peu vue","first_air_date":"2020-01-01","origin_country":["FR"],"job":"Creator","vote_count":10},
            {"id":2,"name":"Très vue","first_air_date":"2010-01-01","origin_country":["FR"],"job":"Creator","vote_count":5000},
            {"id":3,"name":"Produite","first_air_date":"2012-01-01","origin_country":["FR"],"job":"Producer","vote_count":9000}]}"""
        val page = TmdbApi.json.decodeFromString(TmdbPageDto.serializer(), TmdbSeriesAdapter.personPage(credits, discover))
        assertEquals(listOf("Très vue", "Peu vue"), page.results.map { it.title })
        assertNull(TmdbSeriesAdapter.personDiscover("https://api.themoviedb.org/3/discover/movie?with_genres=18".toHttpUrl()))
    }
}
