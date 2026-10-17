package io.github.deedg.cineswipe.domain.reco

/**
 * Invitation affichée sur les recos quand elles manquent de matière : l'appli recense, note,
 * puis recommande. Sans assez de films vus, le recensement ; sans assez de duels, l'Arène.
 */
enum class RecoNudge { CENSUS, DUELS }

/** Ce que les recos savent de l'utilisateur. [reliability] : fiabilité de l'Arène, de 0 à 1. */
data class RecoStrength(val watched: Int, val censusAnswered: Int, val reliability: Double)

object RecoNudges {
    private data class Thresholds(val watchedEnough: Int, val censusEnough: Int, val duelsFrom: Int)

    private val FILMS = Thresholds(watchedEnough = 100, censusEnough = 60, duelsFrom = 10)
    private val SERIES = Thresholds(watchedEnough = 25, censusEnough = 20, duelsFrom = 5)

    /** En dessous, les étoiles et donc les goûts sont encore flous. */
    const val RELIABILITY_MIN = 0.5

    /** Après avoir suivi l'invitation, elle ne revient pas avant une semaine (si les recos restent faibles). */
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    /** Refusée (« Ne plus me le proposer ») : jamais plus. */
    const val NEVER = Long.MAX_VALUE

    /**
     * L'invitation à montrer, ou aucune. Le recensement passe avant les duels (on note ce qu'on a
     * recensé) ; [hiddenUntil] garde les invitations refusées ou suivies récemment.
     */
    fun pick(series: Boolean, strength: RecoStrength, hiddenUntil: Map<RecoNudge, Long>, now: Long): RecoNudge? {
        val limits = if (series) SERIES else FILMS
        fun visible(nudge: RecoNudge) = (hiddenUntil[nudge] ?: 0L) <= now
        val needsCensus = strength.watched < limits.watchedEnough && strength.censusAnswered < limits.censusEnough
        if (needsCensus && visible(RecoNudge.CENSUS)) return RecoNudge.CENSUS
        val needsDuels = strength.watched >= limits.duelsFrom && strength.reliability < RELIABILITY_MIN
        if (needsDuels && visible(RecoNudge.DUELS)) return RecoNudge.DUELS
        return null
    }

    fun encode(hidden: Map<RecoNudge, Long>): String = hidden.entries.joinToString(";") { "${it.key.name}=${it.value}" }

    fun decode(text: String?): Map<RecoNudge, Long> = text.orEmpty().split(';').mapNotNull { entry ->
        val (name, until) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val nudge = RecoNudge.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        until.toLongOrNull()?.let { nudge to it }
    }.toMap()
}
