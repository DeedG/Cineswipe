package io.github.deedg.cineswipe.data.demo

import android.content.Context
import io.github.deedg.cineswipe.data.tmdb.TmdbCastDto
import io.github.deedg.cineswipe.data.tmdb.TmdbCollectionRefDto
import io.github.deedg.cineswipe.data.tmdb.TmdbCreditsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbCrewDto
import io.github.deedg.cineswipe.data.tmdb.TmdbGenreDto
import io.github.deedg.cineswipe.data.tmdb.TmdbKeywordsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieDetailsDto
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.data.tmdb.TmdbVideoDto
import io.github.deedg.cineswipe.data.tmdb.TmdbVideosDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DemoGenre(
    val id: Int,
    val name: String,
)

/** Film de la démo : l'essentiel d'une fiche TMDB, en français. */
@Serializable
data class DemoFilm(
    val id: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    /** Réalisateurs, séparés par des virgules. */
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val genres: List<Int> = emptyList(),
    val rating: Double? = null,
    val votes: Int? = null,
    val runtime: Int? = null,
    val language: String? = null,
    val popularity: Double? = null,
    val trailer: String? = null,
    val collection: Long? = null,
) {
    val directors: List<String> get() = director?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * Catalogue de la démo : les 100 films les plus votés sur TMDB, embarqués dans l'appli
 * (`assets/demo/films.json`). La démo marche ainsi sans clé TMDB ni réseau ; seules les affiches
 * viennent du site d'images de TMDB.
 */
@Serializable
data class DemoCatalog(
    val genres: List<DemoGenre>,
    val films: List<DemoFilm>,
) {
    fun genreName(id: Int): String? = genres.firstOrNull { it.id == id }?.name

    /** Fiche complète, comme la renverrait `/movie/{id}`. */
    fun details(film: DemoFilm): TmdbMovieDetailsDto = TmdbMovieDetailsDto(
        id = film.id,
        title = film.title,
        originalTitle = film.originalTitle,
        releaseDate = film.releaseDate,
        posterPath = film.poster,
        backdropPath = film.backdrop,
        overview = film.overview,
        voteAverage = film.rating,
        voteCount = film.votes,
        runtime = film.runtime,
        genres = film.genres.map { TmdbGenreDto(it, genreName(it)) },
        credits = TmdbCreditsDto(
            cast = film.cast.mapIndexed { index, name -> TmdbCastDto(id = personId(name), name = name, order = index) },
            crew = film.directors.map { TmdbCrewDto(id = personId(it), name = it, job = "Director") },
        ),
        videos = film.trailer?.let { TmdbVideosDto(listOf(TmdbVideoDto(key = it, site = "YouTube", type = "Trailer", language = "fr", official = true))) },
        keywords = TmdbKeywordsDto(),
        originalLanguage = film.language,
        popularity = film.popularity,
        collection = film.collection?.let { TmdbCollectionRefDto(it) },
    )

    /** Film dans une liste, comme dans les recos ou la découverte. */
    fun summary(film: DemoFilm): TmdbMovieSummaryDto = TmdbMovieSummaryDto(
        id = film.id,
        title = film.title,
        originalTitle = film.originalTitle,
        releaseDate = film.releaseDate,
        posterPath = film.poster,
        backdropPath = film.backdrop,
        overview = film.overview,
        voteAverage = film.rating,
        voteCount = film.votes,
        genreIds = film.genres,
        originalLanguage = film.language,
        popularity = film.popularity,
    )

    private val DemoFilm.releaseDate: String? get() = year?.let { "$it-01-01" }

    companion object {
        const val ASSET = "demo/films.json"

        /** Les identifiants de personnes inventés pour la démo commencent ici (loin de ceux de TMDB). */
        private const val PERSON_ID_BASE = 9_000_000_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DemoCatalog = json.decodeFromString(serializer(), text)

        fun load(context: Context): DemoCatalog = context.assets.open(ASSET).use { parse(it.readBytes().decodeToString()) }

        /** Identifiant stable d'un réalisateur ou d'un acteur de la démo, tiré de son nom. */
        fun personId(name: String): Long = PERSON_ID_BASE + (name.trim().lowercase().hashCode().toLong() and 0x7fffffffL)
    }
}
