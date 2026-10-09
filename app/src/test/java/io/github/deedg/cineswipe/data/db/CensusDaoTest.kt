package io.github.deedg.cineswipe.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Catalogue et réponses du recensement, sur une base en mémoire (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CensusDaoTest {

    private lateinit var database: CineSwipeDatabase
    private val census get() = database.censusDao()

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

    private fun movie(id: Long, year: Int = 2010, language: String = "en", poster: Boolean = true, collection: Long? = null) =
        MovieEntity(
            tmdbId = id,
            title = "Film $id",
            year = year,
            posterPath = if (poster) "/$id.jpg" else null,
            originalLanguage = language,
            voteCount = 1_000,
            collectionId = collection,
        )

    private suspend fun candidates(yearMin: Int? = null, yearMax: Int? = null, language: String? = null) =
        census.candidates(yearMin, yearMax, language).map { it.tmdbId }.toSet()

    @Test
    fun answersAreRecordedThenUndoneExactly() = runBlocking {
        census.addToPool((1L..4L).map { movie(it) }, CensusSource.POPULAR, boost = false, now = 1)
        // Le 4 a déjà été montré dans les recos : sa ligne existe.
        val shown = UserMovieEntity(tmdbId = 4, shownCount = 1, lastShownAt = 10, updatedAt = 10)
        database.libraryDao().upsertUserMovie(shown)
        assertEquals(setOf(1L, 2L, 3L, 4L), candidates())

        val undo = census.answer(
            mapOf(1L to CensusChoice.SEEN, 2L to CensusChoice.LOVED, 3L to CensusChoice.NONE, 4L to CensusChoice.WATCHLIST),
            now = 100,
        )

        val users = database.userMovieDao()
        assertEquals(WatchStatus.WATCHED, users.get(1)!!.status)
        assertEquals(WatchSource.MANUAL, users.get(1)!!.watchedSource)
        assertEquals(100L, users.get(1)!!.watchedAt)
        assertNull(users.get(1)!!.impression)
        assertEquals(Impression.LOVED, users.get(2)!!.impression)
        // « Pas vu » : seule la date de réponse est notée.
        assertEquals(WatchStatus.NONE, users.get(3)!!.status)
        assertEquals(100L, users.get(3)!!.censusAt)
        assertEquals(WatchStatus.WATCHLIST, users.get(4)!!.status)
        assertEquals(1, users.get(4)!!.shownCount)
        assertTrue(candidates().isEmpty())
        assertEquals(4, census.observeAnswered().first())
        assertEquals(2, census.observeWatched().first())
        assertEquals(CensusAnswerCounts(answered = 4, seen = 2), census.answerCounts())

        census.restore(undo)

        assertNull(users.get(1))
        assertNull(users.get(2))
        assertNull(users.get(3))
        assertEquals(shown, users.get(4))
        assertEquals(setOf(1L, 2L, 3L, 4L), candidates())
        assertEquals(0, census.observeAnswered().first())
        assertEquals(CensusAnswerCounts(answered = 0, seen = 0), census.answerCounts())
    }

    @Test
    fun theCatalogueSkipsFilmsWithoutPosterAndFollowsTheFilters() = runBlocking {
        census.addToPool(
            listOf(movie(1, year = 1995, language = "fr"), movie(2, year = 2015), movie(3, poster = false)),
            CensusSource.POPULAR,
            boost = false,
            now = 1,
        )
        // Un film déjà traité (vu, à voir, écarté) n'est jamais à recenser.
        census.addToPool(listOf(movie(4)), CensusSource.FRENCH, boost = false, now = 1)
        database.libraryDao().applyStatus(4, WatchStatus.DISMISSED, now = 2)

        assertEquals(setOf(1L, 2L), candidates())
        assertEquals(2, census.candidateCount(null, null, null))
        assertEquals(setOf(1L), candidates(yearMin = 1990, yearMax = 1999))
        assertEquals(setOf(2L), candidates(yearMin = 2010))
        assertEquals(setOf(1L), candidates(language = "fr"))
        assertEquals(4, census.poolSize())
    }

    @Test
    fun aSagaBoostsItsFilmsWithoutOverwritingTheirFiches() = runBlocking {
        val full = movie(1).copy(director = "Lana Wachowski", runtimeMinutes = 136)
        database.libraryDao().upsertMovie(full)
        census.addToPool(listOf(movie(1), movie(2)), CensusSource.POPULAR, boost = false, now = 1)
        assertTrue(census.candidates(null, null, null).none { it.boost })

        census.addToPool(listOf(movie(1), movie(3)), CensusSource.SAGA, boost = true, now = 2)

        val boosted = census.candidates(null, null, null).filter { it.boost }.map { it.tmdbId }.toSet()
        assertEquals(setOf(1L, 3L), boosted)
        // La fiche complète déjà en cache reste intacte.
        assertEquals(full, database.movieDao().get(1))
    }

    @Test
    fun linksAndSagasComeFromWatchedFilms() = runBlocking {
        listOf(movie(1, collection = 77), movie(2, collection = 77), movie(3, collection = 88), movie(10), movie(11))
            .forEach { database.libraryDao().upsertMovie(it) }
        database.libraryDao().setWatched(1, watched = true, now = 1)
        database.libraryDao().setWatched(2, watched = true, now = 1)
        database.libraryDao().applyStatus(3, WatchStatus.WATCHLIST, now = 1)
        val links = listOf(
            RecoLinkEntity(seedId = 1, movieId = 10, position = 0, kind = RecoLinkKind.RECOMMENDED),
            RecoLinkEntity(seedId = 2, movieId = 10, position = 0, kind = RecoLinkKind.RECOMMENDED),
            RecoLinkEntity(seedId = 3, movieId = 11, position = 0, kind = RecoLinkKind.RECOMMENDED),
        )
        links.groupBy { it.seedId }.forEach { (seed, seedLinks) ->
            database.recoDao().saveLinks(seedId = seed, links = seedLinks, movies = emptyList(), now = 1)
        }

        // Seuls les films vus comptent : 10 est recommandé par deux films vus, 11 par un film à voir.
        assertEquals(mapOf(10L to 2), census.graphLinks().associate { it.movieId to it.links })
        assertEquals(listOf(77L), census.watchedCollections())
    }

    @Test
    fun impressionsShiftTheStartingPointOfTheArena() = runBlocking {
        census.addToPool((1L..3L).map { movie(it) }, CensusSource.POPULAR, boost = false, now = 1)
        census.answer(mapOf(1L to CensusChoice.LOVED, 2L to CensusChoice.SEEN, 3L to CensusChoice.DISLIKED), now = 5)

        val ratings = database.arenaDao().rerank()

        assertEquals(1680.0, ratings.getValue(1).rating, 0.5)
        assertEquals(1500.0, ratings.getValue(2).rating, 0.5)
        assertEquals(1320.0, ratings.getValue(3).rating, 0.5)
        // Une première impression rend la note un peu moins incertaine que « vu » tout court.
        assertTrue(ratings.getValue(1).deviation < ratings.getValue(2).deviation)
    }
}
