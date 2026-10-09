package io.github.deedg.cineswipe.data.homelab

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {

    private fun row(
        id: Long,
        status: WatchStatus = WatchStatus.NONE,
        availability: Availability = Availability.NONE,
        watchedAt: Long? = null,
        source: WatchSource? = null,
    ) = UserMovieEntity(tmdbId = id, status = status, availability = availability, watchedAt = watchedAt, watchedSource = source, updatedAt = 0)

    private fun merge(current: List<UserMovieEntity>, snapshot: HomelabSnapshot): Map<Long, UserMovieEntity> =
        SyncMerger.merge(current, snapshot, NOW).userMovies.associateBy { it.tmdbId }

    @Test
    fun watchedOnServerBecomesWatchedWithServerDate() {
        val current = listOf(
            row(1, WatchStatus.WATCHLIST),
            row(2, WatchStatus.WATCHED, watchedAt = 50, source = WatchSource.MANUAL),
        )
        val snapshot = HomelabSnapshot(
            watched = mapOf(
                1L to WatchedOnServer(WatchSource.PLEX, 1_000),
                2L to WatchedOnServer(WatchSource.PLEX, 2_000),
                3L to WatchedOnServer(WatchSource.JELLYFIN, null),
            ),
        )
        val changes = SyncMerger.merge(current, snapshot, NOW)
        val byId = changes.userMovies.associateBy { it.tmdbId }

        assertEquals(2, changes.newlyWatched)
        // Les films qui passent dans les vus, avec la date du serveur (pour la notification).
        assertEquals(listOf(FreshWatch(1, WatchSource.PLEX, 1_000), FreshWatch(3, WatchSource.JELLYFIN, null)), changes.fresh)
        assertEquals(WatchStatus.WATCHED, byId.getValue(1).status)
        assertEquals(1_000L, byId.getValue(1).watchedAt)
        assertEquals(WatchSource.PLEX, byId.getValue(1).watchedSource)
        // Sans date du serveur : vu maintenant.
        assertEquals(NOW, byId.getValue(3).watchedAt)
        assertEquals(WatchSource.JELLYFIN, byId.getValue(3).watchedSource)
        // Déjà vu : rien à écrire, la date et la source locales sont gardées.
        assertFalse(2L in byId)
    }

    @Test
    fun syncNeverRemovesAWatchedFilm() {
        val current = listOf(row(1, WatchStatus.WATCHED, watchedAt = 5, source = WatchSource.IMPORT))
        assertTrue(merge(current, HomelabSnapshot()).isEmpty())
    }

    @Test
    fun availabilityFollowsServersWhenEveryServiceAnswered() {
        val current = listOf(
            row(1, availability = Availability.AVAILABLE),
            row(2, availability = Availability.REQUESTED),
            row(3, availability = Availability.REQUESTED),
            row(4),
        )
        val snapshot = HomelabSnapshot(available = setOf(2L, 5L), requested = setOf(3L, 5L), complete = true)
        val byId = merge(current, snapshot)

        // Retiré du serveur.
        assertEquals(Availability.NONE, byId.getValue(1).availability)
        // Téléchargé depuis la dernière synchro.
        assertEquals(Availability.AVAILABLE, byId.getValue(2).availability)
        assertEquals(NOW, byId.getValue(2).availabilityUpdatedAt)
        // Toujours demandé : inchangé.
        assertFalse(3L in byId)
        // Disponible l'emporte sur demandé.
        assertEquals(Availability.AVAILABLE, byId.getValue(5).availability)
        assertFalse(4L in byId)
    }

    @Test
    fun incompleteSyncNeverRemovesAvailability() {
        val current = listOf(row(1, availability = Availability.AVAILABLE), row(2, availability = Availability.REQUESTED))
        val byId = merge(current, HomelabSnapshot(available = setOf(3L), complete = false))

        assertFalse(1L in byId)
        assertFalse(2L in byId)
        assertEquals(Availability.AVAILABLE, byId.getValue(3).availability)
    }

    @Test
    fun requestStillInTheQueueStaysRequested() {
        val current = listOf(row(1, WatchStatus.WATCHLIST, Availability.REQUESTED))
        assertTrue(merge(current, HomelabSnapshot(pendingRequests = setOf(1L))).isEmpty())
    }

    private companion object {
        const val NOW = 9_000L
    }
}
