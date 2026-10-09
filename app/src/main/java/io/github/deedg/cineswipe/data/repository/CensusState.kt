package io.github.deedg.cineswipe.data.repository

import kotlinx.serialization.Serializable

/** Où en est la lecture d'une liste TMDB du recensement. */
@Serializable
data class CensusCursor(
    val nextPage: Int = 1,
    val totalPages: Int? = null,
) {
    val exhausted: Boolean get() = totalPages != null && nextPage > minOf(totalPages, MAX_TMDB_PAGE)

    companion object {
        /** TMDB ne sert pas au-delà de la page 500. */
        const val MAX_TMDB_PAGE = 500
    }
}

/**
 * Mémoire du recensement : modèle « déjà vu » appris, avancement dans chaque liste TMDB, nombre
 * de films notables (catalogue de référence) et sagas déjà explorées.
 */
@Serializable
data class CensusState(
    val weights: Map<String, Double> = emptyMap(),
    val updates: Int = 0,
    val cursors: Map<String, CensusCursor> = emptyMap(),
    /** Films ayant au moins 200 votes sur TMDB : le catalogue de référence de la progression. */
    val universe: Int? = null,
    val sagas: Set<Long> = emptySet(),
)
