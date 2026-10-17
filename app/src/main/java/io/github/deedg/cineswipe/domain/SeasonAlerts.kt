package io.github.deedg.cineswipe.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Nouvelle saison d'une série suivie : annoncée (première date connue) ou sortie. */
data class SeasonAlert(val tmdbId: Long, val title: String, val season: Int, val kind: Kind, val date: LocalDate?) {
    enum class Kind { ANNOUNCED, OUT }

    /** Une alerte n'est envoyée qu'une fois. */
    val key: String get() = "$tmdbId:$season:${kind.name}"
}

/**
 * Mémoire du suivi : la dernière saison connue de chaque série (au premier passage, celle déjà
 * diffusée, pour ne pas annoncer comme nouvelles des saisons sorties depuis longtemps) et les
 * alertes envoyées.
 */
@Serializable
data class SeasonWatchState(
    val known: Map<Long, Int> = emptyMap(),
    val sent: Set<String> = emptySet(),
)

object SeasonAlerts {
    /** Une saison annoncée trop loin dans le temps attend : l'alerte part dans le mois qui précède. */
    const val ANNOUNCE_DAYS = 30L

    /**
     * Alerte à envoyer pour une série : [lastAired] et [nextSeason]/[nextEpisode]/[nextDate] viennent
     * de TMDB (dernier épisode diffusé, prochain annoncé). [known] : dernière saison connue, null au
     * premier passage (rien n'est annoncé, la saison diffusée devient la référence).
     */
    fun check(
        tmdbId: Long,
        title: String,
        known: Int?,
        lastAired: Int?,
        nextSeason: Int?,
        nextEpisode: Int?,
        nextDate: LocalDate?,
        today: LocalDate,
        sent: Set<String>,
    ): SeasonAlert? {
        val reference = known ?: return null
        if (lastAired != null && lastAired > reference) {
            SeasonAlert(tmdbId, title, lastAired, SeasonAlert.Kind.OUT, null).takeIf { it.key !in sent }?.let { return it }
        }
        if (nextSeason != null && nextSeason > maxOf(reference, lastAired ?: 0) && nextEpisode == 1 && nextDate != null &&
            !nextDate.isAfter(today.plusDays(ANNOUNCE_DAYS))
        ) {
            return SeasonAlert(tmdbId, title, nextSeason, SeasonAlert.Kind.ANNOUNCED, nextDate).takeIf { it.key !in sent }
        }
        return null
    }

    /** Nouvelle référence : la saison la plus récente diffusée (jamais en arrière). */
    fun nextKnown(known: Int?, lastAired: Int?, seasonCount: Int?): Int =
        listOfNotNull(known, lastAired, seasonCount.takeIf { known == null && lastAired == null }).maxOrNull() ?: 0
}
