package io.github.deedg.cineswipe.domain.census

import kotlin.math.ceil
import kotlin.random.Random

/** Réponse à un film du recensement. */
enum class CensusChoice {
    /** Pas encore répondu : en passant à la page suivante, il compte comme « pas vu ». */
    NONE,
    SEEN,

    /** Vu et adoré. */
    LOVED,

    /** Vu, bof. */
    DISLIKED,

    /** Pas vu, mais à voir. */
    WATCHLIST,

    /** Pas pour moi. */
    DISMISSED,
    ;

    val seen: Boolean get() = this == SEEN || this == LOVED || this == DISLIKED

    /** Un toucher : vu → adoré → bof → rien. */
    fun next(): CensusChoice = when (this) {
        NONE -> SEEN
        SEEN -> LOVED
        LOVED -> DISLIKED
        DISLIKED, WATCHLIST, DISMISSED -> NONE
    }
}

/** Film candidat, avec ce qui aide à deviner s'il a été vu. */
data class CensusCandidate(
    val tmdbId: Long,
    val features: CensusFeatures,
)

/** Filtre du recensement : tout le catalogue, les films français, ou une période. */
enum class CensusFilter(val yearMin: Int?, val yearMax: Int?, val language: String?) {
    ALL(null, null, null),
    FRENCH(null, null, "fr"),
    Y2020(2020, null, null),
    Y2010(2010, 2019, null),
    Y2000(2000, 2009, null),
    Y1990(1990, 1999, null),
    Y1980(1980, 1989, null),
    BEFORE_1980(null, 1979, null),
}

/**
 * Compose les pages du recensement : surtout les films que tu as le plus de chances d'avoir
 * vus (le recensement rapporte vite), plus une part tirée plus loin dans la liste pour que le
 * modèle apprenne aussi là où il se trompe. L'ordre dans la page est mélangé.
 */
class CensusPlanner(private val random: Random = Random.Default) {

    fun page(
        candidates: List<CensusCandidate>,
        model: CensusModel,
        size: Int,
        exploreShare: Double = EXPLORE_SHARE,
    ): List<Long> {
        if (candidates.isEmpty() || size <= 0) return emptyList()
        val ranked = candidates.sortedWith(
            compareByDescending<CensusCandidate> { model.probability(it.features) }.thenBy { it.tmdbId },
        )
        val exploreCount = if (ranked.size > size) ceil(size * exploreShare).toInt().coerceAtMost(size - 1) else 0
        val head = ranked.take(size - exploreCount)
        val tail = ranked.drop(head.size).take(EXPLORE_WINDOW).shuffled(random).take(exploreCount)
        return (head + tail).map { it.tmdbId }.shuffled(random)
    }

    companion object {
        const val EXPLORE_SHARE = 0.2

        /** Les films d'exploration sont tirés parmi les 300 suivants. */
        const val EXPLORE_WINDOW = 300
    }
}
