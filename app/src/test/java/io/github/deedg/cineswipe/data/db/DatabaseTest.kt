package io.github.deedg.cineswipe.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.HomelabSnapshot
import io.github.deedg.cineswipe.data.homelab.ServerTitle
import io.github.deedg.cineswipe.data.homelab.WatchedOnServer
import io.github.deedg.cineswipe.data.importer.BackupRows
import io.github.deedg.cineswipe.data.model.LibraryStats
import io.github.deedg.cineswipe.data.repository.ArenaRepository
import io.github.deedg.cineswipe.data.repository.ExportFile
import io.github.deedg.cineswipe.data.repository.ExportList
import io.github.deedg.cineswipe.data.repository.ExportListFilm
import io.github.deedg.cineswipe.domain.Matchmaker
import io.github.deedg.cineswipe.domain.Ranking
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Requêtes et transactions Room, sur une base en mémoire (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class DatabaseTest {

    private lateinit var database: CineSwipeDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CineSwipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Film vu ; une note différente de 1500 est une note héritée (duels sans journal). */
    private suspend fun watchedFilm(tmdbId: Long, rating: Double, duels: Int) {
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = tmdbId, title = "Film $tmdbId", posterPath = "/$tmdbId.jpg"))
        database.libraryDao().upsertUserMovie(
            UserMovieEntity(
                tmdbId = tmdbId,
                status = WatchStatus.WATCHED,
                rating = rating,
                duels = duels,
                updatedAt = 0,
                priorRating = rating,
                priorDuels = duels,
            ),
        )
    }

    private suspend fun user(tmdbId: Long): UserMovieEntity = database.userMovieDao().get(tmdbId)!!

    @Test
    fun aDuelReranksEverythingAndUndoRestoresTheRanking() = runBlocking {
        watchedFilm(1, 1500.0, 0)
        watchedFilm(2, 1500.0, 0)
        watchedFilm(3, 1500.0, 0)

        val duel = database.arenaDao().recordDuel(winnerId = 1, loserId = 2, draw = false, now = 10)!!
        assertTrue(user(1).rating > 1550.0)
        // Deux films identiques au départ : l'écart se partage à égalité.
        assertEquals(3000.0, user(1).rating + user(2).rating, 0.01)
        assertTrue(user(1).deviation < Ranking.INITIAL_DEVIATION)
        assertEquals(1, user(1).duels)
        assertEquals(1, user(2).duels)
        assertEquals(1500.0, duel.winnerRatingBefore, 0.0)
        assertEquals(user(1).rating, duel.winnerRatingAfter, 0.05)
        assertEquals(10L, user(1).updatedAt)
        // Le film qui n'a pas joué garde sa note et son incertitude ; chaque film classé a une confiance.
        assertEquals(1500.0, user(3).rating, 0.01)
        assertEquals(Ranking.INITIAL_DEVIATION, user(3).deviation, 0.01)
        assertEquals(0, user(3).duels)
        assertTrue((1L..3L).all { user(it).confidence > 0.0 })

        val undone = database.arenaDao().undoLastDuel(now = 20)!!
        assertEquals(duel.id, undone.id)
        assertEquals(1500.0, user(1).rating, 0.1)
        assertEquals(1500.0, user(2).rating, 0.1)
        assertEquals(Ranking.INITIAL_DEVIATION, user(1).deviation, 0.1)
        assertEquals(0, user(2).duels)
        assertNull(database.arenaDao().lastDuel())

        // « Annuler » retire le duel voté, même si un autre a été enregistré depuis.
        val first = database.arenaDao().recordDuel(winnerId = 1, loserId = 2, draw = false, now = 30)!!
        val second = database.arenaDao().recordDuel(winnerId = 3, loserId = 1, draw = false, now = 31)!!
        assertEquals(first.id, database.arenaDao().undoDuel(first.id, now = 32)?.id)
        assertEquals(second.id, database.arenaDao().lastDuel()?.id)
        assertEquals(0, user(2).duels)
        assertNull(database.arenaDao().undoDuel(first.id, now = 33))
    }

    @Test
    fun aDrawPullsTwoFilmsTogetherAndIsLogged() = runBlocking {
        watchedFilm(1, 1600.0, 10)
        watchedFilm(2, 1400.0, 10)

        val duel = database.arenaDao().recordDuel(winnerId = 1, loserId = 2, draw = true, now = 5)!!
        assertTrue(duel.draw)
        assertTrue(user(1).rating < 1600.0)
        assertTrue(user(2).rating > 1400.0)
        assertEquals(11, user(1).duels)
        assertTrue(database.arenaDao().observeHistory(10).first().single().draw)
        assertEquals(listOf(PairCount(lowId = 1, highId = 2, duels = 1)), database.arenaDao().pairCounts())
    }

    @Test
    fun aConsistentRoundRobinGivesAReliableRanking() = runBlocking {
        (1L..8L).forEach { watchedFilm(it, 1500.0, 0) }
        for (a in 1L..8L) {
            for (b in a + 1..8L) database.arenaDao().recordDuel(winnerId = b, loserId = a, draw = false, now = a * 10 + b)
        }

        val ranked = database.userMovieDao().ranked().sortedByDescending { it.rating }
        assertEquals((8L downTo 1L).toList(), ranked.map { it.tmdbId })
        assertEquals(7, ranked.first().duels)
        // Le premier a tout gagné : sa note reste ouverte vers le haut, mais l'incertitude a bien baissé.
        assertTrue(ranked.first().deviation < 0.7 * Ranking.INITIAL_DEVIATION)
        assertTrue(ranked[3].deviation < ranked.first().deviation)
        // Après sept victoires, les étoiles du premier ne sont plus provisoires.
        assertTrue(ranked.first().confidence >= StarScale.PROVISIONAL_CONFIDENCE)

        // Recommencer le classement : tout revient au départ.
        database.libraryDao().resetRanking()
        database.arenaDao().rerank()
        assertTrue((1L..8L).all { user(it).rating == 1500.0 && user(it).duels == 0 && user(it).priorDuels == 0 })
        assertNull(database.arenaDao().lastDuel())
    }

    /**
     * Un film retiré des vus (ou finalement « pas vu » au recensement) sort du classement : ses
     * duels ne comptent plus pour les autres, et reviennent s'il redevient vu.
     */
    @Test
    fun duelsAgainstAFilmNoLongerWatchedStopCounting() = runBlocking {
        watchedFilm(1, 1500.0, 0)
        watchedFilm(2, 1500.0, 0)
        watchedFilm(3, 1500.0, 0)
        val arena = database.arenaDao()
        arena.recordDuel(winnerId = 3, loserId = 1, draw = false, now = 1)
        arena.recordDuel(winnerId = 3, loserId = 2, draw = false, now = 2)
        val beaten = user(1).rating
        assertTrue(user(3).rating > 1600.0)

        database.libraryDao().setWatched(3, watched = false, now = 3)
        val ratings = arena.rerank()

        assertNull(ratings[3])
        assertEquals(1500.0, user(1).rating, 0.01)
        assertEquals(0, user(1).duels)
        // Le film retiré garde sa note et ses duels tels quels.
        assertTrue(user(3).rating > 1600.0)
        assertEquals(2, user(3).duels)

        database.libraryDao().setWatched(3, watched = true, now = 4)
        arena.rerank()
        assertEquals(beaten, user(1).rating, 0.01)
        assertEquals(1, user(1).duels)
    }

    /** En V4, retirer un film des vus effaçait son Elo, et l'annulation ne le restaurait pas. */
    @Test
    fun unwatchingKeepsRatingAndDuels() = runBlocking {
        watchedFilm(1, 1625.0, 9)

        database.libraryDao().setWatched(1, watched = false, now = 5)
        val unwatched = database.userMovieDao().get(1)!!
        assertEquals(WatchStatus.NONE, unwatched.status)
        assertEquals(1625.0, unwatched.rating, 0.0)
        assertEquals(9, unwatched.duels)

        database.libraryDao().setWatched(1, watched = true, now = 6)
        val rewatched = database.userMovieDao().get(1)!!
        assertEquals(WatchStatus.WATCHED, rewatched.status)
        assertEquals(1625.0, rewatched.rating, 0.0)
    }

    @Test
    fun markingAnUnknownFilmWatchedCreatesItsEntry() = runBlocking {
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 7, title = "Nouveau"))
        database.libraryDao().setWatched(7, watched = true, now = 42)
        val entry = database.userMovieDao().get(7)!!
        assertEquals(WatchStatus.WATCHED, entry.status)
        assertEquals(WatchSource.MANUAL, entry.watchedSource)
        assertEquals(42L, entry.watchedAt)
    }

    @Test
    fun libraryAndStatsQueriesIgnoreCacheOnlyFilms() = runBlocking {
        watchedFilm(1, 1600.0, 4)
        watchedFilm(2, 1400.0, 4)
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 3, title = "Dispo"))
        database.libraryDao().upsertUserMovie(UserMovieEntity(tmdbId = 3, availability = Availability.AVAILABLE, updatedAt = 0))
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 4, title = "Cache"))
        database.libraryDao().upsertUserMovie(UserMovieEntity(tmdbId = 4, shownCount = 1, updatedAt = 0))

        val library = database.userMovieDao().observeLibrary().first()
        assertEquals(setOf(1L, 2L, 3L), library.map { it.tmdbId }.toSet())
        assertEquals(LibraryStats(watched = 2, watchlist = 0, available = 1, duels = 4), database.userMovieDao().observeStats().first())
        assertEquals(2, database.userMovieDao().watchedCandidates().size)
    }

    /** L'annulation d'une action du feed remet la ligne exactement comme avant, ou la supprime. */
    @Test
    fun cardActionsAreUndoneExactly() = runBlocking {
        val dao = database.libraryDao()
        val newFilm = MovieEntity(tmdbId = 10, title = "Jamais vu")

        val noPrevious = dao.applyCardAction(newFilm, WatchStatus.WATCHLIST, now = 5)
        assertNull(noPrevious)
        assertEquals(WatchStatus.WATCHLIST, database.userMovieDao().get(10)!!.status)
        assertEquals(newFilm, database.movieDao().get(10))
        dao.restore(10, noPrevious)
        assertNull(database.userMovieDao().get(10))

        watchedFilm(11, 1625.0, 9)
        val before = database.userMovieDao().get(11)!!
        val previous = dao.applyCardAction(MovieEntity(tmdbId = 11, title = "Autre titre"), WatchStatus.NONE, now = 6)
        assertEquals(before, previous)
        assertEquals("Film 11", database.movieDao().get(11)!!.title)
        dao.restore(11, previous)
        assertEquals(before, database.userMovieDao().get(11))
    }

    @Test
    fun feedMemoryDrivesTheRecosExclusions() = runBlocking {
        val dao = database.libraryDao()
        val userMovies = database.userMovieDao()
        dao.markShown(1, now = 100)
        dao.markShown(1, now = 200)
        assertEquals(2, userMovies.get(1)!!.shownCount)
        assertEquals(200L, userMovies.get(1)!!.lastShownAt)

        dao.markShown(2, now = 100)
        dao.applyStatus(3, WatchStatus.DISMISSED, now = 100)
        dao.upsertUserMovie(UserMovieEntity(tmdbId = 4, shownCount = 3, lastShownAt = 1, updatedAt = 0))
        dao.upsertUserMovie(UserMovieEntity(tmdbId = 5, availability = Availability.AVAILABLE, updatedAt = 0))
        dao.upsertUserMovie(UserMovieEntity(tmdbId = 6, shownCount = 10, lastShownAt = 0, updatedAt = 0))
        // « Pas vu » au recensement : neutre pour les recos.
        dao.upsertUserMovie(UserMovieEntity(tmdbId = 7, censusAt = 50, updatedAt = 50))

        // Délai de 100 après une première apparition, doublé à chaque nouvelle apparition (au plus ×16) :
        // 1 revient après 400, 2 après 200, 4 après 401, 6 après 1600 ; 3 n'est pas pour moi, 5 n'est que dispo.
        assertEquals(setOf(1L, 2L, 3L, 4L, 6L), userMovies.recosExcludedIds(cooldown = 100, now = 150, excludeAvailable = false).toSet())
        assertEquals(setOf(1L, 3L, 4L, 6L), userMovies.recosExcludedIds(cooldown = 100, now = 300, excludeAvailable = false).toSet())
        // Plus tard, les films ignorés reviennent ; le homelab écarte aussi les films dispo.
        assertEquals(setOf(3L, 6L), userMovies.recosExcludedIds(cooldown = 100, now = 1_000, excludeAvailable = false).toSet())
        assertEquals(setOf(3L, 5L, 6L), userMovies.recosExcludedIds(cooldown = 100, now = 1_000, excludeAvailable = true).toSet())
        // Jamais exclu pour toujours : même montré dix fois, un film finit par revenir.
        assertEquals(setOf(3L), userMovies.recosExcludedIds(cooldown = 100, now = 2_000, excludeAvailable = false).toSet())
        assertEquals(setOf(3L), userMovies.actedIds(excludeAvailable = false).toSet())
    }

    @Test
    fun tasteRowsLinksAndSourcesFeedTheRecos() = runBlocking {
        val keyword = TmdbTag(4379, "voyage dans le temps")
        val director = TmdbTag(137427, "Denis Villeneuve")
        database.libraryDao().upsertMovie(
            MovieEntity(
                tmdbId = 1,
                title = "Vu",
                genreIds = listOf(18, 878),
                keywords = listOf(keyword),
                directors = listOf(director),
                cast = listOf(CastMember("Amy Adams", "/amy.jpg", id = 9273)),
                originalLanguage = "en",
                detailsVersion = 1,
            ),
        )
        database.libraryDao().upsertUserMovie(UserMovieEntity(tmdbId = 1, status = WatchStatus.WATCHED, rating = 1610.0, duels = 7, updatedAt = 0))
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 2, title = "À voir"))
        database.libraryDao().applyStatus(2, WatchStatus.WATCHLIST, now = 1)
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 3, title = "Pas pour moi"))
        database.libraryDao().applyStatus(3, WatchStatus.DISMISSED, now = 1)
        database.libraryDao().markShown(4, now = 1)

        val reco = database.recoDao()
        val taste = reco.tasteRows().associateBy { it.tmdbId }
        assertEquals(setOf(1L, 2L, 3L), taste.keys)
        assertEquals(listOf(keyword), taste.getValue(1).keywords)
        assertEquals(listOf(director), taste.getValue(1).directors)
        assertEquals(9273L, taste.getValue(1).cast.single().id)
        assertEquals("en", taste.getValue(1).originalLanguage)
        assertEquals(WatchStatus.DISMISSED, taste.getValue(3).status)
        // Fiches à compléter : celles qui n'ont pas les mots-clés, le film vu d'abord.
        assertEquals(listOf(2L, 3L), reco.tasteToEnrich(version = 1, limit = 10).sorted())

        // Liens de la graine 1 : le film à voir (2) et le film montré récemment (101) ne sont plus proposables.
        reco.saveLinks(
            seedId = 1,
            links = listOf(
                RecoLinkEntity(seedId = 1, movieId = 100, position = 0, kind = RecoLinkKind.RECOMMENDED),
                RecoLinkEntity(seedId = 1, movieId = 101, position = 1, kind = RecoLinkKind.RECOMMENDED),
                RecoLinkEntity(seedId = 1, movieId = 2, position = 2, kind = RecoLinkKind.RECOMMENDED),
            ),
            movies = listOf(MovieEntity(tmdbId = 100, title = "Lié"), MovieEntity(tmdbId = 101, title = "Lié aussi")),
            now = 5,
        )
        database.libraryDao().markShown(101, now = 100)
        assertEquals(listOf(100L), reco.openLinks(cooldown = 100, now = 150, excludeAvailable = false).map { it.movieId })
        // Le délai passé, le film montré redevient proposable ; le film à voir, jamais.
        assertEquals(setOf(100L, 101L), reco.openLinks(cooldown = 100, now = 250, excludeAvailable = false).map { it.movieId }.toSet())
        assertEquals("Lié", database.movieDao().get(100)?.title)
        assertEquals(5L, reco.seeds().single().fetchedAt)

        // Bilan des sources, annulations comprises.
        reco.recordSource(RecoSource.GEMS, shown = 1.0, liked = 1.0, disliked = 0.0, now = 1)
        reco.recordSource(RecoSource.GEMS, shown = 0.0, liked = -1.0, disliked = 0.0, now = 2)
        val gems = reco.sources().single()
        assertEquals(1.0, gems.shown, 0.0)
        assertEquals(0.0, gems.liked, 0.0)

        val statuses = database.userMovieDao().observeCardStatuses().first().associateBy { it.tmdbId }
        assertEquals(setOf(1L, 2L, 3L), statuses.keys)
        assertEquals(WatchStatus.WATCHLIST, statuses.getValue(2).status)
    }

    @Test
    fun listsSurviveTheRoundTripThroughTheDatabase() = runBlocking {
        val movie = MovieEntity(
            tmdbId = 9,
            title = "Avec liste",
            cast = listOf(CastMember("Amy Adams", "/amy.jpg", id = 9273), CastMember("Inconnu")),
            genreIds = listOf(18, 878),
            genres = listOf("Drame", "Science-Fiction"),
            keywords = listOf(TmdbTag(4379, "voyage dans le temps")),
            directors = listOf(TmdbTag(137427, "Denis Villeneuve")),
            originalLanguage = "en",
            popularity = 42.5,
            detailsVersion = 1,
        )
        database.libraryDao().upsertMovie(movie)
        assertEquals(movie, database.movieDao().get(9))
    }

    @Test
    fun personalListsKeepTheirFilmsAndMergeBackupsByName() = runBlocking {
        watchedFilm(1, 1500.0, 0)
        database.libraryDao().upsertMovie(MovieEntity(tmdbId = 2, title = "Film 2", posterPath = "/2.jpg"))
        val dao = database.listDao()
        val christmas = dao.create("Films de Noël", now = 10, firstFilm = 1)
        dao.add(christmas, 2, now = 20)
        dao.add(christmas, 2, now = 30)
        val films = dao.observeItems(christmas).first()
        // Le dernier ajouté d'abord, sans doublon ; le statut vient de la bibliothèque.
        assertEquals(listOf(2L, 1L), films.map { it.tmdbId })
        assertEquals(WatchStatus.WATCHED, films.last().status)
        assertEquals(listOf("/2.jpg", "/1.jpg"), dao.observeCovers().first().map { it.posterPath })

        // Sauvegarde : « films de noël » rejoint la liste existante, l'autre est créée ; le film
        // inconnu reçoit une fiche minimale.
        val backup = ExportFile(
            version = "test",
            exportedAt = 0,
            films = emptyList(),
            duels = emptyList(),
            recoSources = emptyList(),
            catalog = emptyList(),
            lists = listOf(
                ExportList("films de noël", 5, listOf(ExportListFilm(1, addedAt = 5), ExportListFilm(3, "Nouveau", 2001, addedAt = 6))),
                ExportList("À montrer", 7, listOf(ExportListFilm(2, addedAt = 7))),
                ExportList("  ", 8, emptyList()),
            ),
        )
        val (lists, movies) = BackupRows.lists(backup)
        assertEquals(2, dao.mergeImported(lists, movies, now = 100))
        assertEquals(listOf("Films de Noël", "À montrer"), dao.lists().map { it.name })
        assertEquals(setOf(1L, 2L, 3L), dao.observeItems(christmas).first().map { it.tmdbId }.toSet())
        assertEquals("Nouveau", database.movieDao().get(3)?.title)
    }

    @Test
    fun aFilmJustWatchedPlaysThreeDuelsInARow() = runBlocking {
        (1L..6L).forEach { watchedFilm(it, 1400.0 + it * 20, 2) }
        val arena = ArenaRepository(database, Matchmaker(Random(7)))
        arena.focusOn(listOf(4L, 99L))
        repeat(3) { round ->
            val match = arena.pickMatch(cooldownIds = setOf(4L), recentPairs = emptySet(), anchorId = arena.focus.value?.current)!!
            assertTrue(match.a.tmdbId == 4L || match.b.tmdbId == 4L)
            val other = if (match.a.tmdbId == 4L) match.b.tmdbId else match.a.tmdbId
            arena.recordDuel(4L, other)
            val placed = arena.focusPlayed(4L, other)
            if (round < 2) assertNull(placed) else assertEquals(4L, placed)
        }
        // Le suivant n'est pas parmi les vus : il est passé.
        assertEquals(99L, arena.focus.value?.current)
        arena.skipFocus(99L)
        assertNull(arena.focus.value)
        // Un duel sans le film en placement ne compte pas.
        arena.focusOn(listOf(5L))
        assertNull(arena.focusPlayed(1L, 2L))
        assertEquals(0, arena.focus.value?.played)
    }

    @Test
    fun syncCreatesPartialFichesThatTheArenaWaitsFor() = runBlocking {
        watchedFilm(1, 1500.0, 0)
        val snapshot = HomelabSnapshot(
            watched = mapOf(2L to WatchedOnServer(WatchSource.PLEX, 100)),
            available = setOf(2L, 3L),
            titles = mapOf(2L to ServerTitle("Vu sur Plex", 2001), 3L to ServerTitle("Sur le serveur", 2010)),
        )

        assertEquals(1, database.homelabDao().applySync(snapshot, now = 500).size)

        assertEquals("Vu sur Plex", database.movieDao().get(2)?.title)
        assertEquals(2001, database.movieDao().get(2)?.year)
        assertEquals(WatchStatus.WATCHED, database.userMovieDao().get(2)?.status)
        assertEquals(WatchSource.PLEX, database.userMovieDao().get(2)?.watchedSource)
        assertEquals(Availability.AVAILABLE, database.userMovieDao().get(3)?.availability)
        // L'Arène attend l'affiche des fiches partielles ; elles sont à compléter, les films vus d'abord.
        assertEquals(listOf(1L), database.userMovieDao().watchedCandidates().map { it.tmdbId })
        assertEquals(1, database.userMovieDao().watchedCount())
        assertEquals(listOf(2L, 3L), database.homelabDao().moviesToEnrich(10))

        // Seerr ne donne que l'identifiant : pas de fiche du tout, à compléter aussi.
        database.homelabDao().applySync(HomelabSnapshot(available = setOf(4L), complete = false), now = 600)
        assertNull(database.movieDao().get(4))
        assertEquals(listOf(2L, 3L, 4L), database.homelabDao().moviesToEnrich(10))
        // Synchro incomplète : les disponibilités déjà connues restent.
        assertEquals(Availability.AVAILABLE, database.userMovieDao().get(3)?.availability)
    }

    @Test
    fun requestPutsTheFilmInTheWatchlistAndAvailableFilmsFeedDiscover() = runBlocking {
        val dao = database.homelabDao()
        assertNull(dao.applyRequest(MovieEntity(tmdbId = 20, title = "Demandé", posterPath = "/20.jpg"), now = 5))
        assertEquals(WatchStatus.WATCHLIST, database.userMovieDao().get(20)?.status)
        assertEquals(Availability.REQUESTED, database.userMovieDao().get(20)?.availability)
        assertEquals("Demandé", database.movieDao().get(20)?.title)

        // Un film déjà vu reste vu.
        watchedFilm(21, 1500.0, 0)
        dao.applyRequest(MovieEntity(tmdbId = 21, title = "Film 21"), now = 6)
        assertEquals(WatchStatus.WATCHED, database.userMovieDao().get(21)?.status)

        // Découverte « dispo » : disponibles, ni vus ni écartés, filtrés par année, les mieux notés d'abord.
        val library = database.libraryDao()
        library.upsertMovie(MovieEntity(tmdbId = 30, title = "Bon", year = 2000, tmdbRating = 8.0))
        library.upsertMovie(MovieEntity(tmdbId = 31, title = "Moyen", year = 2005, tmdbRating = 6.0))
        library.upsertMovie(MovieEntity(tmdbId = 32, title = "Vieux", year = 1950, tmdbRating = 9.0))
        library.upsertMovie(MovieEntity(tmdbId = 33, title = "Écarté", year = 2001, tmdbRating = 9.5))
        listOf(30L, 31L, 32L).forEach { library.upsertUserMovie(UserMovieEntity(tmdbId = it, availability = Availability.AVAILABLE, updatedAt = 0)) }
        library.upsertUserMovie(UserMovieEntity(tmdbId = 33, status = WatchStatus.DISMISSED, availability = Availability.AVAILABLE, updatedAt = 0))

        assertEquals(listOf(30L, 31L), dao.availableMovies(yearMin = 1990, yearMax = null).map { it.tmdbId })
        assertEquals(listOf(32L, 30L, 31L), dao.availableMovies(yearMin = null, yearMax = null).map { it.tmdbId })
    }

    @Test
    fun outboxKeepsPendingRequestsAndPurgesFinishedOnes() = runBlocking {
        val outbox = database.outboxDao()
        val pending = outbox.insert(
            OutboxEntity(type = OutboxType.REQUEST, service = HomelabService.SEERR, tmdbId = 40, title = "A", createdAt = 1, updatedAt = 1),
        )
        val sent = outbox.insert(
            OutboxEntity(
                type = OutboxType.REQUEST,
                service = HomelabService.RADARR,
                tmdbId = 41,
                title = "B",
                state = OutboxState.SENT,
                remoteId = 9,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        outbox.insert(OutboxEntity(type = OutboxType.CANCEL, service = HomelabService.SEERR, tmdbId = 42, title = "C", remoteId = 3, createdAt = 1, updatedAt = 1))

        assertEquals(listOf(40L), outbox.pendingRequestIds())
        assertEquals(2, outbox.pending().size)
        assertEquals(9L, outbox.get(sent)?.remoteId)

        outbox.purgeFinished(before = 2)
        assertNull(outbox.get(sent))
        assertNotNull(outbox.get(pending))
        assertEquals(HomelabService.SEERR, outbox.get(pending)?.service)
    }
}
