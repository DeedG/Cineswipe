package io.github.deedg.cineswipe.domain.journey

/** Étape du parcours d'un nouvel utilisateur : on recense, on note en duels, puis l'appli recommande. */
enum class JourneyStep { CENSUS, ARENA, RECOS }

/**
 * Où en est l'utilisateur : films (ou séries) vus et duels joués, face aux objectifs du parcours.
 * [skipped] : il a choisi de voir les recos sans attendre.
 */
data class JourneyState(
    val watched: Int,
    val watchedGoal: Int,
    val duels: Int,
    val duelsGoal: Int,
    val skipped: Boolean = false,
) {
    val censusDone: Boolean get() = watched >= watchedGoal
    val arenaDone: Boolean get() = duels >= duelsGoal

    /** Étape en cours : le recensement d'abord, puis l'Arène, puis les recos. */
    val step: JourneyStep
        get() = when {
            !censusDone -> JourneyStep.CENSUS
            !arenaDone -> JourneyStep.ARENA
            else -> JourneyStep.RECOS
        }

    /** Les recos s'affichent : parcours terminé, ou passé. */
    val recosOpen: Boolean get() = step == JourneyStep.RECOS || skipped

    val censusProgress: Float get() = fraction(watched, watchedGoal)
    val arenaProgress: Float get() = fraction(duels, duelsGoal)

    private fun fraction(done: Int, goal: Int): Float = if (goal <= 0) 1f else (done.toFloat() / goal).coerceIn(0f, 1f)

    companion object {
        /** Parcours terminé (démo, tests) : rien n'est verrouillé. */
        val DONE = JourneyState(watched = 0, watchedGoal = 0, duels = 0, duelsGoal = 0)
    }
}

/**
 * Objectifs du parcours : assez de films vus pour connaître les goûts, assez de duels pour que les
 * étoiles veuillent dire quelque chose. Volontairement atteignables en quelques minutes.
 */
object Journey {
    const val FILMS_WATCHED = 30
    const val FILMS_DUELS = 20
    const val SERIES_WATCHED = 10
    const val SERIES_DUELS = 10

    fun state(series: Boolean, watched: Int, duels: Int, skipped: Boolean): JourneyState = JourneyState(
        watched = watched,
        watchedGoal = if (series) SERIES_WATCHED else FILMS_WATCHED,
        duels = duels,
        duelsGoal = if (series) SERIES_DUELS else FILMS_DUELS,
        skipped = skipped,
    )
}
