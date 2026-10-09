package io.github.deedg.cineswipe.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Réponses de l'API TMDB v3, réduites aux champs utilisés. Tout est optionnel : TMDB omet
// ou met à null bien des champs selon les films.

@Serializable
data class TmdbAuthenticationDto(
    val success: Boolean = false,
)

@Serializable
data class TmdbPageDto(
    val page: Int = 1,
    val results: List<TmdbMovieSummaryDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

/** `/search/person` : personnes trouvées par leur nom. */
@Serializable
data class TmdbPersonPageDto(
    val page: Int = 1,
    val results: List<TmdbPersonHitDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
data class TmdbPersonHitDto(
    val id: Long,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownFor: String? = null,
    val popularity: Double? = null,
    val adult: Boolean = false,
    /** Ses œuvres les plus connues (films ou séries). */
    @SerialName("known_for") val works: List<TmdbKnownForDto> = emptyList(),
)

@Serializable
data class TmdbKnownForDto(
    val id: Long = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

/** `/person/{id}?append_to_response=movie_credits` : une personne et ses films. */
@Serializable
data class TmdbPersonDto(
    val id: Long,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownFor: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("movie_credits") val credits: TmdbPersonCreditsDto = TmdbPersonCreditsDto(),
)

@Serializable
data class TmdbPersonCreditsDto(
    val cast: List<TmdbPersonCreditDto> = emptyList(),
    val crew: List<TmdbPersonCreditDto> = emptyList(),
)

/** Un film d'une personne : rôle joué ([character]) ou poste ([job], « Director »…). */
@Serializable
data class TmdbPersonCreditDto(
    val id: Long,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    val character: String? = null,
    val job: String? = null,
    val adult: Boolean = false,
)

/** `/movie/{id}/watch/providers` : offres par pays (données JustWatch). */
@Serializable
data class TmdbWatchProvidersDto(
    val results: Map<String, TmdbCountryProvidersDto> = emptyMap(),
)

@Serializable
data class TmdbCountryProvidersDto(
    /** Page TMDB « Où regarder », qui renvoie vers chaque service. */
    val link: String? = null,
    val flatrate: List<TmdbProviderDto> = emptyList(),
    val free: List<TmdbProviderDto> = emptyList(),
    val ads: List<TmdbProviderDto> = emptyList(),
    val rent: List<TmdbProviderDto> = emptyList(),
    val buy: List<TmdbProviderDto> = emptyList(),
)

@Serializable
data class TmdbProviderDto(
    @SerialName("provider_id") val id: Int,
    @SerialName("provider_name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val priority: Int = 999,
)

/** Réponse de `/find/{id}` : seuls les films nous intéressent. */
@Serializable
data class TmdbFindDto(
    @SerialName("movie_results") val movieResults: List<TmdbMovieSummaryDto> = emptyList(),
)

/** Film dans une liste (recos, recherche, découverte, tendances). */
@Serializable
data class TmdbMovieSummaryDto(
    val id: Long,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
    @SerialName("original_language") val originalLanguage: String? = null,
    val popularity: Double? = null,
)

/** Fiche complète (`/movie/{id}` avec `append_to_response=credits,videos,keywords`). */
@Serializable
data class TmdbMovieDetailsDto(
    val id: Long,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    val videos: TmdbVideosDto? = null,
    val keywords: TmdbKeywordsDto? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val popularity: Double? = null,
    @SerialName("belongs_to_collection") val collection: TmdbCollectionRefDto? = null,
)

@Serializable
data class TmdbCollectionRefDto(
    val id: Long,
    val name: String? = null,
)

/** Saga (`/collection/{id}`) : ses films, dans la langue des fiches. */
@Serializable
data class TmdbCollectionDto(
    val id: Long,
    val name: String? = null,
    val parts: List<TmdbMovieSummaryDto> = emptyList(),
)

@Serializable
data class TmdbKeywordsDto(
    val keywords: List<TmdbKeywordDto> = emptyList(),
)

@Serializable
data class TmdbKeywordDto(
    val id: Long,
    val name: String? = null,
)

@Serializable
data class TmdbGenreDto(
    val id: Int,
    val name: String? = null,
)

@Serializable
data class TmdbGenresDto(
    val genres: List<TmdbGenreDto> = emptyList(),
)

@Serializable
data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
    val crew: List<TmdbCrewDto> = emptyList(),
)

@Serializable
data class TmdbCastDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int? = null,
)

@Serializable
data class TmdbCrewDto(
    val id: Long? = null,
    val name: String? = null,
    val job: String? = null,
)

@Serializable
data class TmdbVideosDto(
    val results: List<TmdbVideoDto> = emptyList(),
)

@Serializable
data class TmdbVideoDto(
    val key: String? = null,
    val site: String? = null,
    val type: String? = null,
    @SerialName("iso_639_1") val language: String? = null,
    val official: Boolean? = null,
)
