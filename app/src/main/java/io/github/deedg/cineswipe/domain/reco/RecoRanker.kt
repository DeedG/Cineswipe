package io.github.deedg.cineswipe.domain.reco

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** Lien TMDB « les amateurs de [seedId] aiment aussi [movieId] », au rang [rank]. */
data class SeedLink(
    val seedId: Long,
    val movieId: Long,
    val rank: Int,
    val similar: Boolean = false,
)

/** Score collaboratif d'un film, et la graine qui y contribue le plus. */
data class CollaborativeScore(
    val value: Double,
    val bestSeed: Long?,
    val bestContribution: Double,
    val seeds: Int,
)

/** Film candidat aux recos, avec ce qu'on en sait avant de charger sa fiche complète. */
data class RecoCandidate(
    val tmdbId: Long,
    val source: RecoSource,
    /** Raison propre à la source (« Grand classique », « Un film de … »). */
    val reason: RecoReason,
    val features: FilmFeatures,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
)

data class ScoredCandidate(
    val candidate: RecoCandidate,
    val score: Double,
    val reason: RecoReason,
)

/** Bilan d'une source : cartes montrées, gardées (vu, à voir, demandé) et écartées. */
data class SourceStats(
    val shown: Double = 0.0,
    val liked: Double = 0.0,
    val disliked: Double = 0.0,
)

/**
 * Note et ordonne les candidats des recos :
 * - score collaboratif : les recommandations TMDB de tous les films notés, pondérées par la
 *   note (un film recommandé par plusieurs films aimés remonte, par des films détestés descend) ;
 * - score de contenu : ressemblance avec le profil de goût (genres, thèmes, réalisateurs…) ;
 * - qualité : note TMDB, prudente quand il y a peu de votes ;
 * puis compose chaque lot en puisant dans les sources selon ce qui a plu jusqu'ici (bandit de
 * Thompson) et en évitant les doublons de réalisateur ou de genre.
 */
class RecoRanker(
    private val profile: TasteProfile,
    /** Poids des films du profil (graines des liens). */
    private val weights: Map<Long, Double>,
    private val titles: Map<Long, String>,
    private val random: Random = Random.Default,
) {

    fun score(candidate: RecoCandidate, collaborative: CollaborativeScore?): ScoredCandidate {
        val collaborativePart = collaborative?.let { squash(it.value) } ?: 0.0
        val content = profile.score(candidate.features)
        val quality = quality(candidate.voteAverage, candidate.voteCount)
        val score = COLLABORATIVE_WEIGHT * collaborativePart +
            CONTENT_WEIGHT * content.value.coerceIn(-1.5, 2.5) +
            QUALITY_WEIGHT * quality
        return ScoredCandidate(candidate, score, reasonFor(candidate, collaborative, content.highlight))
    }

    private fun reasonFor(candidate: RecoCandidate, collaborative: CollaborativeScore?, highlight: TasteHighlight?): RecoReason {
        val seed = collaborative?.let { score -> score.bestSeed.takeIf { score.bestContribution > 0.0 } }
        val seedTitle = seed?.let { titles[it] }
        if (candidate.source == RecoSource.FOR_YOU && seedTitle != null) {
            val liked = (weights[seed] ?: 0.0) >= LIKED_WEIGHT
            return RecoReason(if (liked) ReasonKind.LIKED else ReasonKind.CLOSE_TO, candidate.source, seedTitle)
        }
        if (candidate.source == RecoSource.PEOPLE || candidate.source == RecoSource.THEMES) return candidate.reason
        // Un film d'une liste générale qui touche de près aux goûts : la raison la plus personnelle.
        val strongLink = (collaborative?.bestContribution ?: 0.0) >= STRONG_LINK
        if (seedTitle != null && strongLink && (weights[seed] ?: 0.0) >= LIKED_WEIGHT) {
            return RecoReason(ReasonKind.LIKED, candidate.source, seedTitle)
        }
        if (highlight != null && highlight.contribution >= STRONG_HIGHLIGHT) {
            val kind = when (highlight.family) {
                TasteFamily.DIRECTOR -> ReasonKind.DIRECTOR
                TasteFamily.CAST -> ReasonKind.ACTOR
                TasteFamily.KEYWORD -> ReasonKind.THEME
                else -> null
            }
            if (kind != null) return RecoReason(kind, candidate.source, highlight.name)
        }
        return candidate.reason
    }

    /**
     * Compose un lot de [count] cartes : à chaque place, chaque source tire au sort sa chance de
     * plaire (loi bêta de son bilan) et la meilleure donne son meilleur candidat restant. Une
     * source ne fournit pas plus de 60 % du lot tant que d'autres ont des candidats.
     */
    fun compose(
        scored: List<ScoredCandidate>,
        stats: Map<RecoSource, SourceStats>,
        count: Int,
        coldStart: Boolean,
    ): List<ScoredCandidate> {
        class Entry(val item: ScoredCandidate, var score: Double)

        val pools = scored
            .groupBy { it.candidate.source }
            .mapValues { (_, items) -> items.map { Entry(it, it.score) }.sortedByDescending { it.score }.toMutableList() }
        val picked = ArrayList<ScoredCandidate>(count)
        val pickedIds = HashSet<Long>()
        val perSource = HashMap<RecoSource, Int>()
        val cap = ceil(count * SOURCE_CAP).toInt()

        while (picked.size < count) {
            pools.values.forEach { pool -> pool.removeAll { it.item.candidate.tmdbId in pickedIds } }
            val available = pools.filterValues { it.isNotEmpty() }
            if (available.isEmpty()) break
            val open = available.filterKeys { (perSource[it] ?: 0) < cap }.ifEmpty { available }
            val source = open.keys.maxBy { sample(it, stats[it] ?: SourceStats(), coldStart) }
            val entry = open.getValue(source).removeAt(0)
            picked += entry.item
            pickedIds += entry.item.candidate.tmdbId
            perSource[source] = (perSource[source] ?: 0) + 1

            // Diversité : un même réalisateur ou les mêmes genres coup sur coup lassent vite.
            val chosen = entry.item.candidate.features
            val directors = chosen.directors.map { it.id }.toSet()
            val genres = chosen.genreIds.toSet()
            pools.values.forEach { pool ->
                pool.forEach { other ->
                    val features = other.item.candidate.features
                    if (directors.isNotEmpty() && features.directors.any { it.id in directors }) other.score -= SAME_DIRECTOR_PENALTY
                    if (genres.isNotEmpty() && features.genreIds.count { it in genres } >= 2) other.score -= SAME_GENRES_PENALTY
                }
                pool.sortByDescending { it.score }
            }
        }
        return picked
    }

    /**
     * Sources à interroger sur TMDB pour ce lot, parmi [available], tirées comme les cartes :
     * celles qui plaisent sont interrogées plus souvent.
     */
    fun pickSources(
        available: List<RecoSource>,
        stats: Map<RecoSource, SourceStats>,
        count: Int,
        coldStart: Boolean,
    ): List<RecoSource> =
        available
            .map { it to sample(it, stats[it] ?: SourceStats(), coldStart) }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }

    /** Chance de plaire d'une source, tirée selon son bilan (bandit de Thompson). */
    fun sample(source: RecoSource, stats: SourceStats, coldStart: Boolean): Double {
        val (priorLiked, priorDisliked) = prior(source, coldStart)
        val skipped = (stats.shown - stats.liked - stats.disliked).coerceAtLeast(0.0)
        val alpha = priorLiked + stats.liked
        val beta = priorDisliked + stats.disliked + SKIP_FAILURE * skipped
        return beta(alpha, beta)
    }

    private fun beta(alpha: Double, beta: Double): Double {
        val x = gamma(alpha)
        val y = gamma(beta)
        return if (x + y <= 0.0) 0.5 else x / (x + y)
    }

    /** Loi gamma (Marsaglia et Tsang). */
    private fun gamma(shape: Double): Double {
        if (shape < 1.0) {
            val u = random.nextDouble().coerceAtLeast(1e-12)
            return gamma(shape + 1.0) * u.pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian()
                v = 1.0 + c * x
            } while (v <= 0.0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        val u = random.nextDouble().coerceAtLeast(1e-300)
        return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * random.nextDouble())
    }

    companion object {
        const val COLLABORATIVE_WEIGHT = 1.0
        const val CONTENT_WEIGHT = 0.5
        const val QUALITY_WEIGHT = 0.3

        /** Poids à partir duquel un film du profil compte comme « aimé ». */
        const val LIKED_WEIGHT = 0.9

        /** Un lien vers un film aimé, dans le haut de sa liste. */
        const val STRONG_LINK = 0.6
        const val STRONG_HIGHLIGHT = 0.6

        const val SOURCE_CAP = 0.6
        const val SAME_DIRECTOR_PENALTY = 0.4
        const val SAME_GENRES_PENALTY = 0.08

        /** Une carte passée sans action compte comme un petit échec (on fait défiler vite). */
        const val SKIP_FAILURE = 0.25

        /** Note TMDB « moyenne » et nombre de votes qui pèse autant qu'elle. */
        private const val QUALITY_MEAN = 6.5
        private const val QUALITY_VOTES = 300.0
        private const val QUALITY_SPREAD = 0.7

        /** Écrasement du score collaboratif : les premiers liens comptent plus que les suivants. */
        private const val COLLABORATIVE_SCALE = 1.5

        fun squash(value: Double): Double = sign(value) * (1.0 - exp(-abs(value) / COLLABORATIVE_SCALE))

        /** Note TMDB centrée et réduite, ramenée vers la moyenne quand les votes sont rares. */
        fun quality(voteAverage: Double?, voteCount: Int?): Double {
            if (voteAverage == null || voteCount == null || voteCount <= 0) return 0.0
            val bayes = (voteCount * voteAverage + QUALITY_VOTES * QUALITY_MEAN) / (voteCount + QUALITY_VOTES)
            return ((bayes - QUALITY_MEAN) / QUALITY_SPREAD).coerceIn(-2.0, 2.0)
        }

        /** A priori de chaque source (succès, échecs fictifs) ; au démarrage, les classiques d'abord. */
        fun prior(source: RecoSource, coldStart: Boolean): Pair<Double, Double> = when (source) {
            RecoSource.FOR_YOU -> 3.0 to 1.0
            RecoSource.CLASSICS -> if (coldStart) 4.0 to 1.0 else 1.5 to 1.5
            RecoSource.GEMS -> 1.2 to 1.2
            RecoSource.TRENDING -> 1.0 to 1.5
            RecoSource.PEOPLE -> 2.0 to 1.0
            RecoSource.THEMES -> 1.5 to 1.0
        }

        /** Poids d'un lien selon son rang dans la liste de TMDB (1 au premier rang). */
        fun rankWeight(rank: Int): Double = 1.0 / (1.0 + rank / 4.0)

        /**
         * Agrège les liens : chaque graine apporte son poids (selon la note du film) multiplié par
         * le poids du rang ; les films « similaires » comptent moitié moins que les « recommandés ».
         */
        fun collaborative(links: List<SeedLink>, weights: Map<Long, Double>): Map<Long, CollaborativeScore> {
            class Sum(var value: Double = 0.0, var best: Long? = null, var bestValue: Double = 0.0, var seeds: Int = 0)
            val sums = HashMap<Long, Sum>()
            links.forEach { link ->
                val weight = weights[link.seedId] ?: return@forEach
                val contribution = weight * rankWeight(link.rank) * (if (link.similar) SIMILAR_FACTOR else 1.0)
                val sum = sums.getOrPut(link.movieId) { Sum() }
                sum.value += contribution
                sum.seeds++
                if (contribution > sum.bestValue) {
                    sum.bestValue = contribution
                    sum.best = link.seedId
                }
            }
            return sums.mapValues { (_, sum) -> CollaborativeScore(sum.value, sum.best, sum.bestValue, sum.seeds) }
        }

        private const val SIMILAR_FACTOR = 0.5
    }
}
