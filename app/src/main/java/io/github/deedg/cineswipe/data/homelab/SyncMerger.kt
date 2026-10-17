package io.github.deedg.cineswipe.data.homelab

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus

/** Ce que les services du homelab disent des films, à un instant donné. */
data class HomelabSnapshot(
    /** tmdbId → visionnage connu d'un serveur média. */
    val watched: Map<Long, WatchedOnServer> = emptyMap(),
    val available: Set<Long> = emptySet(),
    val requested: Set<Long> = emptySet(),
    /** Titres donnés par les serveurs, pour créer les fiches qui manquent encore. */
    val titles: Map<Long, ServerTitle> = emptyMap(),
    /** Faux si un service n'a pas répondu : aucune disponibilité n'est alors retirée. */
    val complete: Boolean = true,
    /** Demandes encore dans la file d'attente : elles restent « demandées ». */
    val pendingRequests: Set<Long> = emptySet(),
)

/**
 * Visionnage connu d'un serveur média. Pour une série ([series]) : au moins un épisode vu,
 * [finished] si tous les épisodes du serveur le sont, sinon la saison en cours ([season]).
 */
data class WatchedOnServer(
    val source: WatchSource,
    val watchedAt: Long?,
    val series: Boolean = false,
    val finished: Boolean = false,
    val season: Int? = null,
) {
    /** Avancement à noter pour une série. */
    val progress: SeriesProgress? get() = if (!series) null else if (finished) SeriesProgress.FINISHED else SeriesProgress.IN_PROGRESS
}

data class ServerTitle(val title: String, val year: Int?)

/** Film devenu « vu » grâce à un serveur média, avec la date de visionnage qu'il donne. */
data class FreshWatch(val tmdbId: Long, val source: WatchSource, val watchedAt: Long?)

data class SyncChanges(
    /** Lignes à écrire (seules celles qui changent). */
    val userMovies: List<UserMovieEntity>,
    /** Films qui passent dans les vus avec cette synchro. */
    val fresh: List<FreshWatch> = emptyList(),
) {
    val newlyWatched: Int get() = fresh.size
}

/**
 * Fusion d'une synchro avec les données locales, sans dépendance Android (testée en local) :
 * - un film vu sur un serveur devient « vu » ; un film n'est jamais retiré des vus par une synchro
 *   (le classement est précieux) ;
 * - disponible sur un serveur > demandé > rien ; une disponibilité n'est retirée que si tous les
 *   services ont répondu ;
 * - séries : l'avancement suit le serveur (en cours à la saison N, ou finie) sans jamais reculer :
 *   une série finie, décrochée ou vue par épisodes ne repasse pas « en cours », et la saison ne
 *   redescend pas.
 */
object SyncMerger {

    fun merge(current: List<UserMovieEntity>, snapshot: HomelabSnapshot, now: Long): SyncChanges {
        val rows = current.associateBy { it.tmdbId }
        val changed = LinkedHashMap<Long, UserMovieEntity>()
        fun latest(id: Long): UserMovieEntity = changed[id] ?: rows[id] ?: UserMovieEntity(tmdbId = id, updatedAt = now)

        val fresh = ArrayList<FreshWatch>()
        snapshot.watched.forEach { (id, info) ->
            val row = latest(id)
            if (row.status != WatchStatus.WATCHED) {
                changed[id] = row.copy(
                    status = WatchStatus.WATCHED,
                    watchedAt = row.watchedAt ?: info.watchedAt ?: now,
                    watchedSource = row.watchedSource ?: info.source,
                    progress = info.progress ?: row.progress,
                    currentSeason = if (info.series) info.season.takeIf { !info.finished } else row.currentSeason,
                    updatedAt = now,
                )
                fresh += FreshWatch(id, info.source, info.watchedAt)
            } else if (info.series) {
                if (info.finished) {
                    if (row.progress != SeriesProgress.FINISHED) {
                        changed[id] = row.copy(progress = SeriesProgress.FINISHED, currentSeason = null, updatedAt = now)
                        // Série tout juste finie : de quoi la (re)placer dans le classement.
                        fresh += FreshWatch(id, info.source, info.watchedAt)
                    }
                } else if (row.progress == null || row.progress == SeriesProgress.IN_PROGRESS) {
                    // En cours : la saison n'avance que vers l'avant (on a pu regarder ailleurs que sur le serveur).
                    val season = listOfNotNull(row.currentSeason, info.season).maxOrNull()
                    if (row.progress != SeriesProgress.IN_PROGRESS || row.currentSeason != season) {
                        changed[id] = row.copy(progress = SeriesProgress.IN_PROGRESS, currentSeason = season, updatedAt = now)
                    }
                }
                // Finie, décrochée ou vue par épisodes d'après l'utilisateur : le serveur ne la fait pas reculer.
            }
        }

        val candidates = LinkedHashSet<Long>()
        current.forEach { if (it.availability != Availability.NONE) candidates += it.tmdbId }
        candidates += snapshot.available
        candidates += snapshot.requested
        candidates += snapshot.pendingRequests
        for (id in candidates) {
            val row = latest(id)
            val target = when {
                id in snapshot.available -> Availability.AVAILABLE
                id in snapshot.requested || id in snapshot.pendingRequests -> Availability.REQUESTED
                snapshot.complete -> Availability.NONE
                else -> row.availability
            }
            if (target != row.availability) {
                changed[id] = row.copy(availability = target, availabilityUpdatedAt = now, updatedAt = now)
            }
        }
        return SyncChanges(userMovies = changed.values.toList(), fresh = fresh)
    }
}
