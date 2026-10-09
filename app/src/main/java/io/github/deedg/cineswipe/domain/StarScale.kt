package io.github.deedg.cineswipe.domain

/**
 * Étoiles calculées selon le rang du film parmi tous les films vus, et non plus selon des
 * seuils d'Elo fixes (en V4, 74 % des films étaient à 3 étoiles et personne n'atteignait 4,5).
 */
object StarScale {
    /**
     * Sous cette confiance (probabilité que les étoiles soient justes à une demi-étoile près),
     * les étoiles d'un film sont provisoires : en pratique, ses trois ou quatre premiers duels.
     */
    const val PROVISIONAL_CONFIDENCE = 0.7

    /** Part du classement (du meilleur au moins bon) → étoiles. Le reste vaut 1 étoile. */
    val buckets: List<Pair<Double, Float>> = listOf(
        0.05 to 5f,
        0.15 to 4.5f,
        0.30 to 4f,
        0.50 to 3.5f,
        0.70 to 3f,
        0.85 to 2.5f,
        0.95 to 2f,
    )

    /** [rank] = nombre de films strictement mieux classés (0 pour le meilleur). */
    fun starsForRank(rank: Int, total: Int): Float {
        val bucket = bucketForRank(rank, total)
        return if (bucket < buckets.size) buckets[bucket].second else 1f
    }

    /** Palier d'étoiles (0 = 5 étoiles … [buckets].size = 1 étoile) d'un rang. */
    fun bucketForRank(rank: Int, total: Int): Int {
        require(total > 0) { "total doit être positif" }
        val position = rank.toDouble() / total
        val index = buckets.indexOfFirst { position < it.first }
        return if (index < 0) buckets.size else index
    }

    /** Palier de chaque note (même ordre que [ratings]) ; les ex aequo partagent le même palier. */
    fun computeBuckets(ratings: DoubleArray): IntArray {
        if (ratings.isEmpty()) return IntArray(0)
        val sorted = ratings.sortedArrayDescending()
        return IntArray(ratings.size) { i -> bucketForRank(countStrictlyHigher(sorted, ratings[i]), ratings.size) }
    }

    /** Étoiles de chaque film ; les ex aequo partagent le même rang. */
    fun computeStars(ratings: Map<Long, Double>): Map<Long, Float> {
        if (ratings.isEmpty()) return emptyMap()
        val sorted = ratings.values.sortedDescending()
        return ratings.mapValues { (_, rating) ->
            val rank = countStrictlyHigher(sorted, rating)
            starsForRank(rank, sorted.size)
        }
    }

    fun isProvisional(confidence: Double): Boolean = confidence < PROVISIONAL_CONFIDENCE

    /** [sortedDescending] doit être trié du plus grand au plus petit. */
    private fun countStrictlyHigher(sortedDescending: DoubleArray, value: Double): Int {
        var low = 0
        var high = sortedDescending.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedDescending[mid] > value) low = mid + 1 else high = mid
        }
        return low
    }

    private fun countStrictlyHigher(sortedDescending: List<Double>, value: Double): Int {
        var low = 0
        var high = sortedDescending.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedDescending[mid] > value) low = mid + 1 else high = mid
        }
        return low
    }
}
