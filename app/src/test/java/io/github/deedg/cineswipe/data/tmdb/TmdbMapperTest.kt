package io.github.deedg.cineswipe.data.tmdb

import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMapperTest {

    private val now = 1_790_000_000_000L

    private fun video(key: String, type: String = "Trailer", language: String? = "fr", site: String = "YouTube", official: Boolean = true) =
        TmdbVideoDto(key = key, site = site, type = type, language = language, official = official)

    @Test
    fun directorsAreTakenFromTheCrewAndJoined() {
        val crew = listOf(
            TmdbCrewDto(name = "Hans Zimmer", job = "Original Music Composer"),
            TmdbCrewDto(name = "Joel Coen", job = "Director"),
            TmdbCrewDto(name = "Ethan Coen", job = "Director"),
            TmdbCrewDto(name = "Joel Coen", job = "Director"),
        )
        assertEquals("Joel Coen, Ethan Coen", TmdbMapper.director(crew))
        assertNull(TmdbMapper.director(listOf(TmdbCrewDto(name = "Quelqu’un", job = "Producer"))))
    }

    @Test
    fun directorsKeepTheirTmdbIdsForTheTasteProfile() {
        val crew = listOf(
            TmdbCrewDto(id = 1223, name = "Joel Coen", job = "Director"),
            TmdbCrewDto(id = 1224, name = "Ethan Coen", job = "Director"),
            TmdbCrewDto(id = 1223, name = "Joel Coen", job = "Director"),
            TmdbCrewDto(id = null, name = "Sans identifiant", job = "Director"),
            TmdbCrewDto(id = 947, name = "Hans Zimmer", job = "Original Music Composer"),
        )
        assertEquals(listOf(TmdbTag(1223, "Joel Coen"), TmdbTag(1224, "Ethan Coen")), TmdbMapper.directors(crew))
    }

    @Test
    fun castIsSortedByBillingAndLimitedToEight() {
        val cast = (10 downTo 0).map {
            TmdbCastDto(id = 100L + it, name = "Acteur $it", profilePath = if (it % 2 == 0) "/p$it.jpg" else null, order = it)
        } + TmdbCastDto(name = "  ", order = -1)
        val mapped = TmdbMapper.cast(cast)
        assertEquals(8, mapped.size)
        assertEquals(CastMember("Acteur 0", "/p0.jpg", id = 100), mapped.first())
        assertEquals(CastMember("Acteur 1", null, id = 101), mapped[1])
    }

    @Test
    fun trailerPrefersTheFicheLanguageThenEnglish() {
        val videos = listOf(
            video("en-trailer", language = "en"),
            video("fr-teaser", type = "Teaser", language = "fr"),
            video("vimeo", site = "Vimeo"),
            video("fr-clip", type = "Clip"),
        )
        assertEquals("fr-teaser", TmdbMapper.trailerKey(videos, "fr-FR"))
        assertEquals("en-trailer", TmdbMapper.trailerKey(videos, "en-US"))
        assertEquals("en-trailer", TmdbMapper.trailerKey(videos.filterNot { it.language == "fr" }, "fr-FR"))
        assertNull(TmdbMapper.trailerKey(listOf(video("vimeo", site = "Vimeo")), "fr-FR"))
    }

    @Test
    fun officialTrailerWinsAtEqualLanguage() {
        val videos = listOf(video("fan", official = false), video("officielle", official = true))
        assertEquals("officielle", TmdbMapper.trailerKey(videos, "fr-FR"))
    }

    @Test
    fun detailsBecomeACompleteCacheEntry() {
        val dto = TmdbMovieDetailsDto(
            id = 329865,
            title = "",
            originalTitle = "Arrival",
            releaseDate = "2016-11-10",
            posterPath = "/poster.jpg",
            backdropPath = "backdrop-sans-slash.jpg",
            voteAverage = 7.6,
            voteCount = 18_000,
            runtime = 116,
            genres = listOf(TmdbGenreDto(18, "Drame"), TmdbGenreDto(878, "Science-Fiction")),
            credits = TmdbCreditsDto(crew = listOf(TmdbCrewDto(id = 137427, name = "Denis Villeneuve", job = "Director"))),
            videos = TmdbVideosDto(listOf(video("abc"))),
            keywords = TmdbKeywordsDto(listOf(TmdbKeywordDto(4379, "voyage dans le temps"), TmdbKeywordDto(9882, " "))),
            originalLanguage = "EN",
            popularity = 61.2,
        )
        val entity = TmdbMapper.toEntity(dto, overview = "Synopsis.", overviewLanguage = "fr", language = "fr-FR", now = now)
        assertEquals("Arrival", entity.title)
        assertEquals(2016, entity.year)
        assertEquals("/poster.jpg", entity.posterPath)
        assertNull(entity.backdropPath)
        assertEquals("Denis Villeneuve", entity.director)
        assertEquals("abc", entity.trailerKey)
        assertEquals(listOf(18, 878), entity.genreIds)
        assertEquals(listOf("Drame", "Science-Fiction"), entity.genres)
        assertEquals(7.6, entity.tmdbRating!!, 0.0)
        assertEquals(116, entity.runtimeMinutes)
        assertEquals(now, entity.fetchedAt)
        assertEquals(listOf(TmdbTag(137427, "Denis Villeneuve")), entity.directors)
        assertEquals(listOf(TmdbTag(4379, "voyage dans le temps")), entity.keywords)
        assertEquals("en", entity.originalLanguage)
        assertEquals(61.2, entity.popularity!!, 0.0)
        assertEquals(TmdbMapper.DETAILS_VERSION, entity.detailsVersion)
        assertFalse(TmdbMapper.needsRefresh(entity, "fr-FR", now))
    }

    @Test
    fun unratedFilmHasNoTmdbRating() {
        val dto = TmdbMovieDetailsDto(id = 1, title = "Inédit", voteAverage = 0.0, voteCount = 0)
        val entity = TmdbMapper.toEntity(dto, overview = null, overviewLanguage = TmdbMapper.NO_OVERVIEW, language = "fr-FR", now = now)
        assertNull(entity.tmdbRating)
        assertEquals(0, entity.voteCount)
        assertNull(entity.year)
    }

    @Test
    fun summaryGivesAPartialEntryToCompleteLater() {
        val dto = TmdbMovieSummaryDto(
            id = 5,
            title = "Dune",
            releaseDate = "2021-09-15",
            posterPath = "/dune.jpg",
            overview = " ",
            genreIds = listOf(878, 12),
            voteAverage = 7.8,
            voteCount = 12_000,
            originalLanguage = "xx",
            popularity = 80.0,
        )
        val entity = TmdbMapper.summaryToEntity(dto, mapOf(878 to "Science-Fiction"), "fr-FR")
        assertEquals(listOf("Science-Fiction"), entity.genres)
        // « xx » = sans langue chez TMDB.
        assertNull(entity.originalLanguage)
        assertEquals(80.0, entity.popularity!!, 0.0)
        assertEquals(0, entity.detailsVersion)
        assertNull(entity.overview)
        assertNull(entity.fetchedAt)
        assertTrue(TmdbMapper.needsRefresh(entity, "fr-FR", now))
    }

    @Test
    fun refreshRules() {
        val fresh = MovieEntity(
            tmdbId = 1,
            title = "Film",
            overview = "Synopsis",
            overviewLanguage = "fr",
            voteCount = 10,
            fetchedAt = now,
            detailsVersion = TmdbMapper.DETAILS_VERSION,
        )
        assertTrue(TmdbMapper.needsRefresh(null, "fr-FR", now))
        assertFalse(TmdbMapper.needsRefresh(fresh, "fr-FR", now))
        // Fiche d'une version précédente (sans mots-clés ni identifiants) : rechargée.
        assertTrue(TmdbMapper.needsRefresh(fresh.copy(detailsVersion = 0), "fr-FR", now))
        // Fiche ancienne.
        assertTrue(TmdbMapper.needsRefresh(fresh, "fr-FR", now + TmdbMapper.MAX_AGE_MS + 1))
        // Fiche importée de la V4 : jamais chargée en entier par la V5.
        assertTrue(TmdbMapper.needsRefresh(fresh.copy(voteCount = null), "fr-FR", now))
        // Synopsis anglais de repli, ou aucun synopsis nulle part : pas de nouvel essai avant 30 jours.
        assertFalse(TmdbMapper.needsRefresh(fresh.copy(overviewLanguage = "en"), "fr-FR", now))
        assertFalse(TmdbMapper.needsRefresh(fresh.copy(overview = null, overviewLanguage = TmdbMapper.NO_OVERVIEW), "fr-FR", now))
        // Langue des fiches passée à l'anglais : les synopsis français sont rechargés.
        assertTrue(TmdbMapper.needsRefresh(fresh, "en-US", now))
    }

    @Test
    fun languageHelpers() {
        assertEquals("fr", TmdbMapper.primaryLanguage("fr-FR"))
        assertEquals("fr,en,null", TmdbMapper.videoLanguages("fr-FR"))
        assertEquals("en,null", TmdbMapper.videoLanguages("en-US"))
        assertEquals(1999, TmdbMapper.year("1999-03-31"))
        assertNull(TmdbMapper.year(""))
    }
}
