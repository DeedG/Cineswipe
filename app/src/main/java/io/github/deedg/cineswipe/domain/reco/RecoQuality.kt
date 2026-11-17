package io.github.deedg.cineswipe.domain.reco

/** Finesse des recos, du début (peu de films, peu de duels) à des recos qui suivent bien les goûts. */
enum class RecoQualityLevel { STARTING, FAIR, GOOD, SHARP }

/** Ce qui ferait le plus progresser les recos. */
enum class RecoQualityAdvice { CENSUS, DUELS, NONE }

/**
 * Ce que l'appli sait des goûts : [watched] titres vus (la matière) et [reliability], la fiabilité
 * du classement de l'Arène en % (ce qu'on en pense). [score] de 0 à 1.
 */
data class RecoQuality(
    val score: Float,
    val level: RecoQualityLevel,
    val advice: RecoQualityAdvice,
    val watched: Int,
    val reliability: Int,
)

object RecoQualities {
    /** Titres vus à partir desquels en recenser plus n'apporte plus grand-chose aux recos. */
    const val FILMS_ENOUGH = 100
    const val SERIES_ENOUGH = 30

    private const val FAIR = 0.35f
    private const val GOOD = 0.6f
    private const val SHARP = 0.8f
    private const val RELIABLE = 0.9

    /** Null sans titre vu : rien à dire encore. [reliability] entre 0 et 1. */
    fun of(series: Boolean, watched: Int, reliability: Double): RecoQuality? {
        if (watched <= 0) return null
        val enough = if (series) SERIES_ENOUGH else FILMS_ENOUGH
        val breadth = (watched.toDouble() / enough).coerceIn(0.0, 1.0)
        val trust = reliability.coerceIn(0.0, 1.0)
        val score = ((breadth + trust) / 2).toFloat()
        return RecoQuality(
            score = score,
            level = when {
                score < FAIR -> RecoQualityLevel.STARTING
                score < GOOD -> RecoQualityLevel.FAIR
                score < SHARP -> RecoQualityLevel.GOOD
                else -> RecoQualityLevel.SHARP
            },
            // Le maillon faible : pas assez de titres, ou un classement encore incertain.
            advice = when {
                breadth >= 1.0 && trust >= RELIABLE -> RecoQualityAdvice.NONE
                breadth <= trust -> RecoQualityAdvice.CENSUS
                else -> RecoQualityAdvice.DUELS
            },
            watched = watched,
            reliability = (trust * 100).toInt(),
        )
    }
}
