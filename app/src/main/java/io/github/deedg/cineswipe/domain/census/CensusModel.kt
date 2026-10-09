package io.github.deedg.cineswipe.domain.census

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** D'où vient un film du catalogue à recenser. */
enum class CensusSource {
    /** Les films les plus vus (les plus votés sur TMDB). */
    POPULAR,

    /** Les films français les plus vus. */
    FRENCH,

    /** Les films les plus vus d'une décennie. */
    DECADE,

    /** Un autre film de la même saga a été vu. */
    SAGA,

    /** Recommandé à partir d'un film vu. */
    GRAPH,
}

/** Ce qui aide à deviner si un film a été vu. */
data class CensusFeatures(
    val voteCount: Int? = null,
    val voteAverage: Double? = null,
    val year: Int? = null,
    val genreIds: List<Int> = emptyList(),
    val language: String? = null,
    /** Nombre de films vus qui le recommandent (graphe des recos). */
    val graphLinks: Int = 0,
    /** Un autre film de sa saga a été vu. */
    val sagaSeen: Boolean = false,
)

/**
 * Probabilité d'avoir vu un film : régression logistique apprise au fil des réponses du
 * recensement (vu / pas vu), partie d'un a priori raisonnable (les films très vus, récents,
 * français ou d'une saga déjà vue d'abord). Chaque poids est ramené doucement vers son a priori,
 * ce qui garde le modèle stable avec peu de réponses.
 */
class CensusModel(
    weights: Map<String, Double> = PRIOR,
    val updates: Int = 0,
) {
    val weights: Map<String, Double> = weights

    fun probability(features: CensusFeatures): Double = sigmoid(score(encode(features)))

    /** Modèle mis à jour avec des réponses (vrai = vu). */
    fun learn(answers: List<Pair<CensusFeatures, Boolean>>): CensusModel {
        val next = HashMap(weights)
        var count = updates
        answers.forEach { (features, seen) ->
            val x = encode(features)
            val p = sigmoid(x.entries.sumOf { (key, value) -> (next[key] ?: PRIOR[key] ?: 0.0) * value })
            val rate = max(MIN_RATE, BASE_RATE / sqrt(1.0 + count / RATE_HALF_LIFE))
            val error = (if (seen) 1.0 else 0.0) - p
            x.forEach { (key, value) ->
                val current = next[key] ?: PRIOR[key] ?: 0.0
                val prior = PRIOR[key] ?: 0.0
                next[key] = current + rate * (error * value - REGULARIZATION * (current - prior))
            }
            count++
        }
        return CensusModel(next, count)
    }

    private fun score(x: Map<String, Double>): Double =
        x.entries.sumOf { (key, value) -> (weights[key] ?: PRIOR[key] ?: 0.0) * value }

    companion object {
        private const val BASE_RATE = 0.15
        private const val MIN_RATE = 0.02
        private const val RATE_HALF_LIFE = 400.0
        private const val REGULARIZATION = 0.02

        const val BIAS = "bias"
        const val VOTES = "votes"
        const val RATING = "rating"
        const val GRAPH = "graph"
        const val SAGA = "saga"

        fun decadeKey(decade: Int) = "decade:$decade"
        fun genreKey(genre: Int) = "genre:$genre"
        fun languageKey(language: String) = "lang:$language"

        /**
         * A priori : 10 fois plus de votes sur TMDB ≈ cinq fois plus de chances de l'avoir vu ; les
         * films récents, français ou anglais, et les sagas déjà commencées d'abord.
         */
        val PRIOR: Map<String, Double> = buildMap {
            put(BIAS, -1.6)
            put(VOTES, 1.6)
            put(RATING, 0.15)
            put(GRAPH, 0.8)
            put(SAGA, 1.6)
            put(decadeKey(1950), -1.4)
            put(decadeKey(1960), -1.0)
            put(decadeKey(1970), -0.6)
            put(decadeKey(1980), -0.2)
            put(decadeKey(1990), 0.1)
            put(decadeKey(2000), 0.3)
            put(decadeKey(2010), 0.3)
            put(decadeKey(2020), 0.0)
            put(languageKey("fr"), 0.6)
            put(languageKey("en"), 0.3)
            put(languageKey("other"), -0.6)
            put(genreKey(16), 0.2) // Animation
            put(genreKey(35), 0.1) // Comédie
            put(genreKey(28), 0.1) // Action
            put(genreKey(99), -0.8) // Documentaire
            put(genreKey(10770), -1.0) // Téléfilm
            put(genreKey(37), -0.4) // Western
            put(genreKey(10402), -0.3) // Musique
        }

        /** Vecteur creux des caractéristiques d'un film. */
        fun encode(features: CensusFeatures): Map<String, Double> = buildMap {
            put(BIAS, 1.0)
            put(VOTES, log10((features.voteCount ?: 0).coerceAtLeast(0) + 1.0) - 3.0)
            features.voteAverage?.takeIf { it > 0.0 }?.let { put(RATING, it - 6.5) }
            if (features.graphLinks > 0) put(GRAPH, ln(1.0 + features.graphLinks))
            if (features.sagaSeen) put(SAGA, 1.0)
            features.year?.let { year -> put(decadeKey((year / 10 * 10).coerceIn(1950, 2020)), 1.0) }
            val language = when (features.language) {
                null -> null
                "fr", "en" -> features.language
                else -> "other"
            }
            language?.let { put(languageKey(it), 1.0) }
            val genres = features.genreIds.distinct()
            if (genres.isNotEmpty()) {
                val share = 1.0 / sqrt(genres.size.toDouble())
                genres.forEach { put(genreKey(it), share) }
            }
        }

        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    }
}
