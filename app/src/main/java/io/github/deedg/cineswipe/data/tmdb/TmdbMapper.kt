package io.github.deedg.cineswipe.data.tmdb

import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.TvSeason
import io.github.deedg.cineswipe.domain.reco.TmdbTag

/** Conversion des réponses TMDB vers le cache local, sans dépendance Android (testée en local). */
object TmdbMapper {
    /** Marqueur de `overviewLanguage` : TMDB n'a de synopsis dans aucune langue utile. */
    const val NO_OVERVIEW = "-"
    const val FALLBACK_LANGUAGE = "en-US"

    /** Une fiche est rafraîchie au bout de 30 jours (notes, bandes-annonces, traductions). */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Version du chargement complet ; une fiche plus ancienne est rechargée.
     * - 1 (0.1.15) : mots-clés, identifiants des personnes, langue originale et popularité ;
     * - 2 : saga du film (recensement).
     */
    const val DETAILS_VERSION = 2

    /** Version des fiches de séries : 4 = durée d'un épisode (finals courts acceptés, 0.1.65). */
    const val SERIES_DETAILS_VERSION = 5

    /** Première version dont les fiches ont mots-clés et personnes (profil de goût). */
    const val TASTE_DETAILS_VERSION = 1

    /** Compléments demandés avec la fiche. */
    const val APPEND_TO_RESPONSE = "credits,videos,keywords"
    private const val MAX_KEYWORDS = 30

    private const val MAX_CAST = 8
    private const val MAX_DIRECTORS = 2
    private const val UNTITLED = "?"

    /** « fr-FR » → « fr ». */
    fun primaryLanguage(language: String): String = language.substringBefore('-').lowercase()

    /** Langues des vidéos à demander : celle des fiches, puis l'anglais, puis les vidéos sans langue. */
    fun videoLanguages(language: String): String =
        listOf(primaryLanguage(language), "en", "null").distinct().joinToString(",")

    fun year(releaseDate: String?): Int? = releaseDate?.trim()?.take(4)?.toIntOrNull()

    fun cleanText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun title(title: String?, originalTitle: String?): String =
        cleanText(title) ?: cleanText(originalTitle) ?: UNTITLED

    fun director(crew: List<TmdbCrewDto>): String? =
        crew.asSequence()
            .filter { it.job == "Director" }
            .mapNotNull { cleanText(it.name) }
            .distinct()
            .take(MAX_DIRECTORS)
            .joinToString(", ")
            .ifEmpty { null }

    /** Réalisateurs avec leur identifiant TMDB (pour le profil de goût). */
    fun directors(crew: List<TmdbCrewDto>): List<TmdbTag> =
        crew.asSequence()
            .filter { it.job == "Director" }
            .mapNotNull { member -> member.id?.let { id -> cleanText(member.name)?.let { TmdbTag(id, it) } } }
            .distinctBy { it.id }
            .take(MAX_DIRECTORS)
            .toList()

    fun cast(cast: List<TmdbCastDto>): List<CastMember> =
        cast.sortedBy { it.order ?: Int.MAX_VALUE }
            .mapNotNull { member ->
                cleanText(member.name)?.let { name -> CastMember(name = name, profilePath = imagePath(member.profilePath), id = member.id) }
            }
            .distinctBy { it.name }
            .take(MAX_CAST)

    fun keywords(dto: TmdbKeywordsDto?): List<TmdbTag> =
        dto?.keywords.orEmpty()
            .mapNotNull { keyword -> cleanText(keyword.name)?.let { TmdbTag(keyword.id, it) } }
            .distinctBy { it.id }
            .take(MAX_KEYWORDS)

    /** « en », « fr »… (TMDB renvoie parfois « xx » pour « sans langue »). */
    fun language(value: String?): String? = cleanText(value)?.lowercase()?.takeIf { it.length in 2..3 && it != "xx" }

    /**
     * Bande-annonce YouTube : d'abord dans la langue des fiches, puis en anglais, puis dans
     * n'importe quelle langue ; à langue égale, Trailer avant Teaser et officielle d'abord.
     */
    fun trailerKey(videos: List<TmdbVideoDto>, language: String): String? {
        val primary = primaryLanguage(language)
        return videos
            .filter { video ->
                video.site.equals("YouTube", ignoreCase = true) &&
                    !video.key.isNullOrBlank() &&
                    (video.type == "Trailer" || video.type == "Teaser")
            }
            .minByOrNull { video ->
                val languageRank = when (video.language?.lowercase()) {
                    primary -> 0
                    "en" -> 1
                    else -> 2
                }
                val typeRank = if (video.type == "Trailer") 0 else 1
                val officialRank = if (video.official == true) 0 else 1
                languageRank * 4 + typeRank * 2 + officialRank
            }
            ?.key
    }

    /** Chemin d'image TMDB valide (« /abc.jpg »), sinon null. */
    fun imagePath(value: String?): String? = cleanText(value)?.takeIf { it.startsWith("/") }

    /**
     * Fiche complète. [overview] est déjà choisi par l'appelant (langue des fiches, sinon
     * anglais) et [overviewLanguage] vaut sa langue, ou [NO_OVERVIEW].
     */
    fun toEntity(
        dto: TmdbMovieDetailsDto,
        overview: String?,
        overviewLanguage: String,
        language: String,
        now: Long,
    ): MovieEntity = MovieEntity(
        tmdbId = dto.id,
        title = title(dto.title, dto.originalTitle),
        originalTitle = cleanText(dto.originalTitle),
        year = year(dto.releaseDate),
        posterPath = imagePath(dto.posterPath),
        backdropPath = imagePath(dto.backdropPath),
        overview = cleanText(overview),
        overviewLanguage = overviewLanguage,
        director = director(dto.credits?.crew.orEmpty()),
        cast = cast(dto.credits?.cast.orEmpty()),
        trailerKey = trailerKey(dto.videos?.results.orEmpty(), language),
        genreIds = dto.genres.map { it.id },
        genres = dto.genres.mapNotNull { cleanText(it.name) },
        tmdbRating = dto.voteAverage?.takeIf { (dto.voteCount ?: 0) > 0 },
        voteCount = dto.voteCount ?: 0,
        runtimeMinutes = dto.runtime?.takeIf { it > 0 },
        fetchedAt = now,
        keywords = keywords(dto.keywords),
        directors = directors(dto.credits?.crew.orEmpty()),
        originalLanguage = language(dto.originalLanguage),
        popularity = dto.popularity,
        detailsVersion = if (dto.numberOfSeasons != null) SERIES_DETAILS_VERSION else DETAILS_VERSION,
        collectionId = dto.collection?.id,
        seasonCount = dto.numberOfSeasons?.takeIf { it > 0 },
        airStatus = cleanText(dto.status)?.takeIf { dto.numberOfSeasons != null },
        endYear = year(dto.lastAirDate),
        seasons = seasons(dto.seasons),
    )

    /** Saisons d'une série, sans la saison 0 (épisodes spéciaux) ni les saisons sans épisode. */
    fun seasons(seasons: List<TmdbSeasonDto>): List<TvSeason> =
        seasons.asSequence()
            .filter { it.number > 0 && (it.episodeCount ?: 0) > 0 }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .map { TvSeason(number = it.number, name = cleanText(it.name), episodeCount = it.episodeCount, year = year(it.airDate), poster = imagePath(it.posterPath)) }
            .toList()

    /**
     * Fiche partielle tirée d'une liste, quand la fiche complète n'a pas pu être chargée.
     * [MovieEntity.fetchedAt] reste null : elle sera complétée à la prochaine occasion.
     */
    fun summaryToEntity(
        dto: TmdbMovieSummaryDto,
        genreNames: Map<Int, String>,
        language: String,
    ): MovieEntity {
        val overview = cleanText(dto.overview)
        return MovieEntity(
            tmdbId = dto.id,
            title = title(dto.title, dto.originalTitle),
            originalTitle = cleanText(dto.originalTitle),
            year = year(dto.releaseDate),
            posterPath = imagePath(dto.posterPath),
            backdropPath = imagePath(dto.backdropPath),
            overview = overview,
            overviewLanguage = overview?.let { primaryLanguage(language) },
            genreIds = dto.genreIds,
            genres = dto.genreIds.mapNotNull { genreNames[it] },
            tmdbRating = dto.voteAverage?.takeIf { (dto.voteCount ?: 0) > 0 },
            voteCount = dto.voteCount,
            fetchedAt = null,
            originalLanguage = language(dto.originalLanguage),
            popularity = dto.popularity,
        )
    }

    /**
     * Vrai s'il faut (re)demander la fiche à TMDB :
     * - jamais chargée en entier par la V5 (fiche partielle ou importée de la V4, sans nombre de votes) ;
     * - chargée par une version plus ancienne ([DETAILS_VERSION]) ;
     * - plus vieille que [MAX_AGE_MS] ;
     * - synopsis dans une autre langue que celle des fiches ou l'anglais (langue changée dans les réglages).
     */
    fun needsRefresh(movie: MovieEntity?, language: String, now: Long): Boolean {
        if (movie == null) return true
        val fetchedAt = movie.fetchedAt ?: return true
        if (movie.voteCount == null) return true
        if (movie.detailsVersion < DETAILS_VERSION) return true
        if (movie.seasonCount != null && movie.detailsVersion < SERIES_DETAILS_VERSION) return true
        if (now - fetchedAt > MAX_AGE_MS) return true
        val overviewLanguage = movie.overviewLanguage ?: return movie.overview == null
        return overviewLanguage != primaryLanguage(language) && overviewLanguage != "en" && overviewLanguage != NO_OVERVIEW
    }
}
