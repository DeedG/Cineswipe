package io.github.deedg.cineswipe.domain.reco

import kotlinx.serialization.Serializable

/** Mot-clé ou personne TMDB : identifiant et nom. */
@Serializable
data class TmdbTag(
    val id: Long,
    val name: String,
)

/** D'où vient une carte des recos. Le feed puise davantage dans les sources qui plaisent. */
enum class RecoSource {
    /** Films recommandés par TMDB à partir des films notés, agrégés (« Parce que tu as aimé… »). */
    FOR_YOU,

    /** Grands classiques (les films les plus votés) : idéal pour retrouver les films déjà vus. */
    CLASSICS,

    /** Pépites : très bien notées mais peu connues. */
    GEMS,

    /** Tendances de la semaine. */
    TRENDING,

    /** Films des réalisateurs et acteurs préférés. */
    PEOPLE,

    /** Films sur les thèmes (mots-clés TMDB) préférés. */
    THEMES,
}

/** Pourquoi une carte est proposée. */
enum class ReasonKind {
    /** Recommandé à partir d'un film bien classé : [RecoReason.subject] = son titre. */
    LIKED,

    /** Recommandé à partir d'un film vu ou à voir, sans plus : [RecoReason.subject] = son titre. */
    CLOSE_TO,
    CLASSIC,
    GEM,
    TRENDING,

    /** [RecoReason.subject] = nom du réalisateur. */
    DIRECTOR,

    /** [RecoReason.subject] = nom de l'acteur. */
    ACTOR,

    /** [RecoReason.subject] = mot-clé. */
    THEME,
}

data class RecoReason(
    val kind: ReasonKind,
    val source: RecoSource,
    val subject: String? = null,
)

/** Ce qui décrit un film pour le profil de goût. Une fiche partielle n'a que genres, année et langue. */
data class FilmFeatures(
    val genreIds: List<Int> = emptyList(),
    val keywords: List<TmdbTag> = emptyList(),
    val directors: List<TmdbTag> = emptyList(),
    val cast: List<TmdbTag> = emptyList(),
    val year: Int? = null,
    val language: String? = null,
)

/**
 * Un film qui renseigne sur les goûts, avec son poids : positif s'il plaît (film vu bien classé,
 * film à voir), négatif s'il déplaît (film vu mal classé, film écarté).
 */
data class TasteFilm(
    val tmdbId: Long,
    val title: String,
    val weight: Double,
    val features: FilmFeatures,
)
