package io.github.deedg.cineswipe.domain

import kotlin.random.Random

/** Un film vu, candidat à un duel. */
data class ArenaCandidate(
    val tmdbId: Long,
    val rating: Double,
    val duels: Int,
    /** Incertitude de la note (écart type, en points Elo). */
    val deviation: Double = Ranking.INITIAL_DEVIATION,
    /** Probabilité que ses étoiles soient justes à une demi-étoile près. */
    val confidence: Double = 0.0,
)

/**
 * Choisit les duels qui apprennent le plus sur le classement :
 * - le premier film est tiré en proportion de son incertitude (les nouveaux d'abord, sans
 *   jamais oublier les autres) ;
 * - l'adversaire est celui qui réduit le plus l'incertitude attendue, en pratique un film bien
 *   classé et de niveau proche, comme une recherche dichotomique ; une paire déjà jouée vaut
 *   moins, une paire récente est évitée ;
 * - de temps en temps, un adversaire au hasard pour repérer les surprises.
 */
class Matchmaker(
    private val random: Random = Random.Default,
    /** L'adversaire est tiré parmi les meilleurs choix, pour varier les affiches. */
    private val shortlist: Int = 4,
    private val explorationRate: Double = 0.08,
    private val anchorFloor: Double = ANCHOR_FLOOR,
    private val opponentShare: Double = OPPONENT_SHARE,
) {

    fun pick(
        candidates: List<ArenaCandidate>,
        cooldownIds: Set<Long> = emptySet(),
        recentPairs: Set<Pair<Long, Long>> = emptySet(),
        pairCounts: Map<Pair<Long, Long>, Int> = emptyMap(),
        /** Film imposé comme premier film (placement après un visionnage), s'il est candidat. */
        anchorId: Long? = null,
    ): Pair<ArenaCandidate, ArenaCandidate>? {
        if (candidates.size < 2) return null

        val fresh = candidates.filter { it.tmdbId !in cooldownIds }
        val pool = if (fresh.size >= 2) fresh else candidates

        val forced = anchorId?.let { id -> candidates.firstOrNull { it.tmdbId == id } }
        val anchor = forced ?: weightedPick(pool) { variance(it) + anchorFloor }
        val opponents = pool.filter { it.tmdbId != anchor.tmdbId }.ifEmpty { candidates.filter { it.tmdbId != anchor.tmdbId } }
        val unseen = opponents
            .filter { pairKey(anchor.tmdbId, it.tmdbId) !in recentPairs }
            .ifEmpty { opponents }

        val opponent = if (random.nextDouble() < explorationRate) {
            unseen[random.nextInt(unseen.size)]
        } else {
            val ranked = unseen
                .map { it to expectedGain(anchor, it, opponentShare) / (1.0 + (pairCounts[pairKey(anchor.tmdbId, it.tmdbId)] ?: 0)) }
                .sortedByDescending { it.second }
                .take(shortlist)
            weightedPick(ranked) { it.second }.first
        }

        return if (random.nextBoolean()) anchor to opponent else opponent to anchor
    }

    private fun <T> weightedPick(items: List<T>, weight: (T) -> Double): T {
        val weights = items.map { weight(it).coerceAtLeast(0.0) }
        val total = weights.sum()
        if (total <= 0.0) return items[random.nextInt(items.size)]
        var remaining = random.nextDouble() * total
        items.forEachIndexed { index, item ->
            remaining -= weights[index]
            if (remaining <= 0.0) return item
        }
        return items.last()
    }

    companion object {
        /** Poids minimal d'un film bien classé dans le tirage du premier film (en log-cotes²). */
        const val ANCHOR_FLOOR = 0.05

        /** Part du gain de l'adversaire comptée dans le choix (son tour viendra comme premier film). */
        const val OPPONENT_SHARE = 0.5

        /** Clé d'une paire, indépendante de l'ordre des deux films. */
        fun pairKey(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

        /** Variance de la note, en log-cotes². */
        fun variance(candidate: ArenaCandidate): Double {
            val deviation = candidate.deviation / Ranking.SCALE
            return deviation * deviation
        }

        /**
         * Baisse attendue de l'incertitude si [anchor] affronte [opponent] : grande quand l'issue
         * est indécise (niveaux proches) et quand l'adversaire est lui-même bien connu.
         */
        fun expectedGain(anchor: ArenaCandidate, opponent: ArenaCandidate, opponentShare: Double = OPPONENT_SHARE): Double {
            val anchorVariance = variance(anchor)
            val opponentVariance = variance(opponent)
            val difference = (anchor.rating - opponent.rating) / Ranking.SCALE
            val p = Ranking.sigmoid(Ranking.attenuation(anchorVariance + opponentVariance) * difference)
            val information = p * (1.0 - p)
            val anchorAttenuation = Ranking.attenuation(anchorVariance)
            val opponentAttenuation = Ranking.attenuation(opponentVariance)
            val anchorGain = anchorVariance - 1.0 / (1.0 / anchorVariance + information * opponentAttenuation * opponentAttenuation)
            val opponentGain = opponentVariance - 1.0 / (1.0 / opponentVariance + information * anchorAttenuation * anchorAttenuation)
            return anchorGain + opponentShare * opponentGain
        }
    }
}
