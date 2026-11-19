package io.github.deedg.cineswipe.domain

import kotlin.math.abs

/** Ce que TMDB dit d'un film vu, pour estimer sa place avant son premier duel. */
data class FilmTraits(
    val tmdbId: Long,
    val tmdbRating: Double?,
    val voteCount: Int?,
    val genres: List<Int>,
    val directors: List<Long>,
)

/**
 * Note de départ estimée d'un film qui entre dans l'Arène, apprise sur tes films déjà bien classés :
 * la note TMDB (prudente), puis l'écart moyen de tes genres et de tes réalisateurs. Le décalage est
 * modeste et borné ; les duels corrigent ensuite. Sans assez de films bien classés : pas d'estimation.
 */
class StartingRating private constructor(
    private val slope: Double,
    private val meanQuality: Double,
    private val genreEffect: Map<Int, Double>,
    private val directorEffect: Map<Long, Double>,
) {
    /** Décalage par rapport à la note de départ habituelle, en points du classement. */
    fun shift(film: FilmTraits): Double {
        val quality = slope * (quality(film) - meanQuality)
        val genres = film.genres.mapNotNull { genreEffect[it] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val director = film.directors.mapNotNull { directorEffect[it] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return (WEIGHT * (quality + GENRE_WEIGHT * genres + director)).coerceIn(-MAX_SHIFT, MAX_SHIFT)
    }

    companion object {
        /** Films bien classés nécessaires pour apprendre, et duels pour qu'un film compte comme tel. */
        const val MIN_TRAINING = 15
        const val MIN_DUELS = 5

        /** Au plus un peu moins d'un demi-écart type de classement, et seulement 70 % de l'estimation. */
        const val MAX_SHIFT = 150.0
        private const val WEIGHT = 0.7
        private const val GENRE_WEIGHT = 0.5

        /** Un genre ou un réalisateur vu peu de fois reste près de zéro (comme trois films neutres de plus). */
        private const val SHRINK = 3.0

        /** Pas plus de 300 points par point de note TMDB : au-delà, ce serait du bruit. */
        private const val MAX_SLOPE = 300.0

        /** Note TMDB prudente : 300 votes fictifs à 6,5. */
        private const val PRIOR_VOTES = 300.0
        private const val PRIOR_MEAN = 6.5

        fun quality(film: FilmTraits): Double {
            val votes = (film.voteCount ?: 0).toDouble()
            val rating = film.tmdbRating ?: PRIOR_MEAN
            return (rating * votes + PRIOR_MEAN * PRIOR_VOTES) / (votes + PRIOR_VOTES)
        }

        /** [ranked] : films bien classés et leur écart à la note de départ habituelle. */
        fun learn(ranked: List<Pair<FilmTraits, Double>>): StartingRating? {
            if (ranked.size < MIN_TRAINING) return null
            val meanResidual = ranked.map { it.second }.average()
            val residuals = ranked.map { (film, delta) -> film to delta - meanResidual }
            val qualities = residuals.map { quality(it.first) }
            val meanQuality = qualities.average()
            val variance = qualities.sumOf { (it - meanQuality) * (it - meanQuality) }
            val slope = if (variance < 1e-6) {
                0.0
            } else {
                (residuals.indices.sumOf { (qualities[it] - meanQuality) * residuals[it].second } / variance).coerceIn(-MAX_SLOPE, MAX_SLOPE)
            }
            // Ce qui reste une fois la note TMDB prise en compte : la part de tes goûts.
            val rest = residuals.mapIndexed { i, (film, delta) -> film to delta - slope * (qualities[i] - meanQuality) }
            return StartingRating(
                slope = slope,
                meanQuality = meanQuality,
                genreEffect = shrunk(rest.flatMap { (film, delta) -> film.genres.map { it to delta } }),
                directorEffect = shrunk(rest.flatMap { (film, delta) -> film.directors.map { it to delta } }),
            )
        }

        private fun <K> shrunk(values: List<Pair<K, Double>>): Map<K, Double> =
            values.groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.sum() / (list.size + SHRINK) }
                .filterValues { abs(it) > 0.5 }
    }
}
