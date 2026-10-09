package io.github.deedg.cineswipe.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Ce que l'on sait d'un film avant les duels du journal. */
data class RankingPrior(
    val tmdbId: Long,
    /** Note de départ : 1500, ou la note héritée d'une ancienne version. */
    val rating: Double = Ranking.INITIAL_RATING,
    /** Duels déjà comptés dans cette note de départ mais absents du journal. */
    val duels: Int = 0,
    /** Note actuelle, point de départ du calcul (qui converge alors en quelques passes). */
    val current: Double = rating,
    /** Incertitude de départ imposée (sinon, déduite de [duels]). */
    val deviation: Double? = null,
)

/** Un duel du journal ; en cas d'égalité, l'ordre des deux films est indifférent. */
data class DuelOutcome(
    val winnerId: Long,
    val loserId: Long,
    val draw: Boolean = false,
)

/** Note d'un film : estimation, incertitude (un écart type, en points Elo) et nombre de duels. */
data class FilmRating(
    val rating: Double,
    val deviation: Double,
    val duels: Int,
)

/**
 * Classement de l'Arène (modèle de Bradley-Terry, l'équivalent statistique de l'Elo).
 *
 * L'Elo de la V4 ajustait deux notes après chaque duel : le résultat dépendait de l'ordre des
 * matchs, les premiers duels d'un film pesaient trop ou trop peu, et l'incertitude n'était pas
 * mesurée. Ici, toutes les notes sont recalculées à partir du journal complet des duels : c'est
 * l'estimation la plus probable compte tenu de tous les résultats (avec un a priori gaussien par
 * film, centré sur sa note de départ), et chaque note est accompagnée de son incertitude.
 *
 * Les notes restent sur l'échelle Elo : 200 points d'écart ≈ 76 % de chances de gagner.
 */
object Ranking {
    const val INITIAL_RATING = 1500.0

    /** Incertitude d'un film qui n'a jamais combattu (comme Glicko). */
    const val INITIAL_DEVIATION = 350.0

    /** Une note héritée d'une ancienne version ne vaut jamais mieux que ± 90. */
    const val MIN_PRIOR_DEVIATION = 90.0

    /** Points Elo par unité de log-cote (400 / ln 10). */
    val SCALE: Double = 400.0 / ln(10.0)

    private const val MAX_SWEEPS = 500
    private const val TOLERANCE = 1e-6
    private const val MAX_STEP = 1.0
    private const val CONFIDENCE_DRAWS = 100

    /** Incertitude d'une note héritée selon le nombre de duels qui l'ont produite. */
    fun priorDeviation(priorDuels: Int, initialDeviation: Double = INITIAL_DEVIATION): Double =
        if (priorDuels <= 0) initialDeviation else max(MIN_PRIOR_DEVIATION, initialDeviation / sqrt(1.0 + priorDuels / 2.0))

    /**
     * Notes de tous les films à partir du journal. Un film du journal absent de [priors] part
     * de 1500. Les duels d'un film contre lui-même sont ignorés.
     */
    fun compute(
        priors: Collection<RankingPrior>,
        duels: List<DuelOutcome>,
        initialDeviation: Double = INITIAL_DEVIATION,
    ): Map<Long, FilmRating> {
        val films = ArrayList<RankingPrior>(priors.size)
        val index = HashMap<Long, Int>(priors.size * 2)
        priors.forEach { prior ->
            if (prior.tmdbId !in index) {
                index[prior.tmdbId] = films.size
                films += prior
            }
        }
        fun indexOf(id: Long): Int = index.getOrPut(id) {
            films += RankingPrior(id)
            films.size - 1
        }

        val validDuels = duels.filter { it.winnerId != it.loserId }
        val first = IntArray(validDuels.size)
        val second = IntArray(validDuels.size)
        val firstScore = DoubleArray(validDuels.size)
        validDuels.forEachIndexed { k, duel ->
            first[k] = indexOf(duel.winnerId)
            second[k] = indexOf(duel.loserId)
            firstScore[k] = if (duel.draw) 0.5 else 1.0
        }

        val n = films.size
        if (n == 0) return emptyMap()

        // Liste d'adjacence compacte : pour chaque film, ses adversaires et son score contre eux.
        val degree = IntArray(n)
        for (k in first.indices) {
            degree[first[k]]++
            degree[second[k]]++
        }
        val offsets = IntArray(n + 1)
        for (i in 0 until n) offsets[i + 1] = offsets[i] + degree[i]
        val fill = offsets.copyOf(n)
        val opponents = IntArray(offsets[n])
        val scores = DoubleArray(offsets[n])
        for (k in first.indices) {
            val a = first[k]
            val b = second[k]
            opponents[fill[a]] = b
            scores[fill[a]++] = firstScore[k]
            opponents[fill[b]] = a
            scores[fill[b]++] = 1.0 - firstScore[k]
        }

        // En log-cotes : θ = (note − 1500) / SCALE.
        val theta = DoubleArray(n) { (films[it].current - INITIAL_RATING) / SCALE }
        val mean = DoubleArray(n) { (films[it].rating - INITIAL_RATING) / SCALE }
        val priorPrecision = DoubleArray(n) {
            val deviation = (films[it].deviation ?: priorDeviation(films[it].duels, initialDeviation)) / SCALE
            1.0 / (deviation * deviation)
        }

        // Méthode de Newton coordonnée par coordonnée : la vraisemblance a posteriori est
        // strictement concave, chaque passe la fait monter jusqu'à l'unique optimum.
        for (sweep in 0 until MAX_SWEEPS) {
            var largest = 0.0
            for (i in 0 until n) {
                var gradient = -(theta[i] - mean[i]) * priorPrecision[i]
                var curvature = priorPrecision[i]
                for (k in offsets[i] until offsets[i + 1]) {
                    val p = sigmoid(theta[i] - theta[opponents[k]])
                    gradient += scores[k] - p
                    curvature += p * (1.0 - p)
                }
                val step = (gradient / curvature).coerceIn(-MAX_STEP, MAX_STEP)
                theta[i] += step
                largest = max(largest, abs(step))
            }
            if (largest < TOLERANCE) break
        }

        // Incertitude (approximation de Laplace) : un duel contre un film lui-même incertain
        // renseigne moins, comme dans Glicko. Deux passes suffisent à propager cet effet.
        var variance = DoubleArray(n) { i ->
            var information = priorPrecision[i]
            for (k in offsets[i] until offsets[i + 1]) {
                val p = sigmoid(theta[i] - theta[opponents[k]])
                information += p * (1.0 - p)
            }
            1.0 / information
        }
        repeat(2) {
            val previous = variance
            variance = DoubleArray(n) { i ->
                var information = priorPrecision[i]
                for (k in offsets[i] until offsets[i + 1]) {
                    val j = opponents[k]
                    val attenuation = attenuation(previous[j])
                    val p = sigmoid(attenuation * (theta[i] - theta[j]))
                    information += attenuation * attenuation * p * (1.0 - p)
                }
                1.0 / information
            }
        }

        val result = HashMap<Long, FilmRating>(n * 2)
        for (i in 0 until n) {
            result[films[i].tmdbId] = FilmRating(
                rating = INITIAL_RATING + SCALE * theta[i],
                deviation = SCALE * sqrt(variance[i]),
                duels = films[i].duels + degree[i],
            )
        }
        return result
    }

    /**
     * Confiance dans les étoiles de chaque film : probabilité que ses vraies étoiles soient celles
     * affichées ou le palier voisin (une demi-étoile près). Estimée sur [draws] classements
     * plausibles, tirés selon les incertitudes. Le tirage d'un film dépend seulement de son
     * identifiant : sa confiance ne bouge pas quand on ajoute un autre film sans rien changer au
     * classement.
     */
    fun starConfidence(ratings: Map<Long, FilmRating>, draws: Int = CONFIDENCE_DRAWS): Map<Long, Double> {
        val ids = ratings.keys.toLongArray()
        val n = ids.size
        if (n == 0) return emptyMap()
        val rating = DoubleArray(n) { ratings.getValue(ids[it]).rating }
        val deviation = DoubleArray(n) { ratings.getValue(ids[it]).deviation }
        val shown = StarScale.computeBuckets(rating)
        val bucketOfRank = IntArray(n) { StarScale.bucketForRank(it, n) }
        val hits = IntArray(n)
        val sample = DoubleArray(n)
        val sorted = DoubleArray(n)
        for (draw in 0 until draws) {
            for (i in 0 until n) sample[i] = rating[i] + deviation[i] * gaussian(ids[i], draw)
            sample.copyInto(sorted)
            sorted.sort()
            for (i in 0 until n) {
                // Rang dans ce tirage = nombre de films tirés au-dessus.
                val rank = n - upperBound(sorted, sample[i])
                if (abs(bucketOfRank[rank] - shown[i]) <= 1) hits[i]++
            }
        }
        val result = HashMap<Long, Double>(n * 2)
        for (i in 0 until n) result[ids[i]] = hits[i].toDouble() / draws
        return result
    }

    /** Premier indice de [sorted] (croissant) dont la valeur dépasse [value]. */
    private fun upperBound(sorted: DoubleArray, value: Double): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }

    /** Tirage gaussien déterministe pour un film et un numéro de tirage (Box-Muller sur un hachage). */
    private fun gaussian(tmdbId: Long, draw: Int): Double {
        val base = tmdbId * 0x9E3779B97F4A7C15uL.toLong() + draw * 0xBF58476D1CE4E5B9uL.toLong()
        val u1 = uniform(mix(base))
        val u2 = uniform(mix(base xor 0x632BE59BD9B4E019L))
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** SplitMix64 : mélange un entier 64 bits. */
    private fun mix(seed: Long): Long {
        var z = seed + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Nombre dans ]0 ; 1[ à partir des 53 bits de poids fort. */
    private fun uniform(bits: Long): Double = ((bits ushr 11) + 0.5) / (1L shl 53).toDouble()

    fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** Facteur g de Glicko : un adversaire incertain (variance en log-cotes²) renseigne moins. */
    fun attenuation(variance: Double): Double = 1.0 / sqrt(1.0 + 3.0 * variance / (PI * PI))
}
