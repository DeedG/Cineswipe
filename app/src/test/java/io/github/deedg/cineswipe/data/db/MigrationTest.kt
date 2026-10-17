package io.github.deedg.cineswipe.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.domain.Ranking
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusSource
import io.github.deedg.cineswipe.domain.reco.RecoSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Mise à jour d'une base réelle d'une ancienne version (v2 : 0.1.11, v3 : 0.1.15) : la base est
 * créée à partir du schéma versionné, remplie, puis ouverte par Room qui la migre jusqu'à la
 * version actuelle et valide le résultat comme sur le téléphone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath(NAME)

    @Before
    fun setUp() {
        file.parentFile?.mkdirs()
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun version2BecomesVersion3WithPriorsFromTheEloEra() = runBlocking {
        createVersion(2) { db ->
            listOf(1L to "Hérité de la V4", 2L to "Gagnant", 3L to "Perdant").forEach { (id, title) ->
                db.execSQL(
                    "INSERT INTO movie (tmdbId, title, posterPath, `cast`, genreIds, genres) VALUES (?, ?, ?, '[]', '[18]', '[]')",
                    arrayOf<Any>(id, title, "/$id.jpg"),
                )
            }
            // 1 : classé par l'ancienne version (12 duels sans journal). 2 a battu 3 dans le journal.
            userMovie(db, 1, rating = 1620.0, duels = 12)
            userMovie(db, 2, rating = 1520.0, duels = 1)
            userMovie(db, 3, rating = 1480.0, duels = 1)
            db.execSQL(
                """
                INSERT INTO duel (winnerId, loserId, createdAt, winnerRatingBefore, loserRatingBefore, winnerRatingAfter, loserRatingAfter)
                VALUES (2, 3, 5, 1500.0, 1500.0, 1520.0, 1480.0)
                """,
            )
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.userMovieDao()
            val inherited = dao.get(1)!!
            assertEquals(1620.0, inherited.priorRating, 0.0)
            assertEquals(12, inherited.priorDuels)
            assertEquals(Ranking.INITIAL_DEVIATION, inherited.deviation, 0.0)
            // La note d'avant le premier duel du journal devient la note de départ.
            assertEquals(1500.0, dao.get(2)!!.priorRating, 0.0)
            assertEquals(0, dao.get(2)!!.priorDuels)
            assertEquals(1500.0, dao.get(3)!!.priorRating, 0.0)
            assertFalse(database.arenaDao().lastDuel()!!.draw)
            assertEquals(0, database.movieDao().get(1)!!.detailsVersion)
            assertTrue(database.movieDao().get(1)!!.keywords.isEmpty())

            // Premier recalcul (au démarrage) : la note héritée reste, son incertitude dépend de ses 12 duels.
            val ratings = database.arenaDao().rerank()
            assertEquals(1620.0, ratings.getValue(1).rating, 0.01)
            assertEquals(Ranking.priorDeviation(12), ratings.getValue(1).deviation, 0.01)
            assertTrue(ratings.getValue(2).rating > 1500.0)
            assertEquals(3000.0, ratings.getValue(2).rating + ratings.getValue(3).rating, 0.01)
            assertEquals(12, dao.get(1)!!.duels)
            assertEquals(1, dao.get(2)!!.duels)

            // Les nouvelles tables sont là.
            database.recoDao().recordSource(RecoSource.CLASSICS, shown = 1.0, liked = 1.0, disliked = 0.0, now = 9)
            assertEquals(1.0, database.recoDao().sources().single().liked, 0.0)
        } finally {
            database.close()
        }
    }

    @Test
    fun version3BecomesVersion4WithTheCensus() = runBlocking {
        createVersion(3) { db ->
            listOf(1L to "Matrix", 2L to "Matrix Reloaded").forEach { (id, title) ->
                db.execSQL(
                    "INSERT INTO movie (tmdbId, title, posterPath, `cast`, genreIds, genres, detailsVersion) VALUES (?, ?, ?, '[]', '[878]', '[]', 1)",
                    arrayOf<Any>(id, title, "/$id.jpg"),
                )
            }
            db.execSQL(
                """
                INSERT INTO user_movie (tmdbId, status, rating, duels, availability, shownCount, lastShownAt, updatedAt, deviation, confidence, priorRating, priorDuels)
                VALUES (1, 'WATCHED', 1600.0, 4, 'NONE', 0, NULL, 0, 200.0, 0.8, 1500.0, 0)
                """,
            )
            db.execSQL(
                """
                INSERT INTO user_movie (tmdbId, status, rating, duels, availability, shownCount, lastShownAt, updatedAt)
                VALUES (2, 'NONE', 1500.0, 0, 'NONE', 3, 10, 10)
                """,
            )
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            // Les données restent ; les nouvelles colonnes sont vides.
            val watched = database.userMovieDao().get(1)!!
            assertEquals(WatchStatus.WATCHED, watched.status)
            assertEquals(1600.0, watched.rating, 0.0)
            assertEquals(0.8, watched.confidence, 0.0)
            assertNull(watched.censusAt)
            assertNull(watched.impression)
            assertEquals(3, database.userMovieDao().get(2)!!.shownCount)
            assertNull(database.movieDao().get(1)!!.collectionId)
            // Ancienne fiche : elle sera rechargée pour connaître sa saga.
            assertTrue(database.movieDao().get(1)!!.detailsVersion < TmdbMapper.DETAILS_VERSION)

            // Le catalogue du recensement est là, et le film montré dans les recos peut y être recensé.
            val census = database.censusDao()
            census.addToPool(listOf(database.movieDao().get(2)!!), CensusSource.SAGA, boost = true, now = 20)
            assertEquals(listOf(2L), census.candidates(null, null, null).map { it.tmdbId })
            assertTrue(census.candidates(null, null, null).single().boost)
            census.answer(mapOf(2L to CensusChoice.LOVED), now = 30)
            assertEquals(Impression.LOVED, database.userMovieDao().get(2)!!.impression)
            assertEquals(3, database.userMovieDao().get(2)!!.shownCount)
            assertTrue(census.candidates(null, null, null).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun version4BecomesVersion5WithImportedRatings() = runBlocking {
        createVersion(4) { db ->
            db.execSQL(
                "INSERT INTO movie (tmdbId, title, posterPath, `cast`, genreIds, genres, detailsVersion, keywords, directors) VALUES (1, 'Matrix', '/1.jpg', '[]', '[878]', '[]', 2, '[]', '[]')",
            )
            db.execSQL(
                """
                INSERT INTO user_movie (tmdbId, status, rating, duels, availability, shownCount, updatedAt, deviation, confidence, priorRating, priorDuels, impression)
                VALUES (1, 'WATCHED', 1600.0, 4, 'NONE', 0, 0, 200.0, 0.8, 1500.0, 0, 'LOVED')
                """,
            )
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val watched = database.userMovieDao().get(1)!!
            assertEquals(Impression.LOVED, watched.impression)
            assertNull(watched.importedRating)
            // La nouvelle colonne s'écrit et se relit.
            database.libraryDao().upsertUserMovie(watched.copy(importedRating = 8.5))
            assertEquals(8.5, database.userMovieDao().get(1)!!.importedRating!!, 0.0)
        } finally {
            database.close()
        }
    }

    @Test
    fun version5BecomesVersion6WithPersonalLists() = runBlocking {
        createVersion(5) { db ->
            db.execSQL(
                "INSERT INTO movie (tmdbId, title, posterPath, `cast`, genreIds, genres, detailsVersion, keywords, directors) VALUES (1, 'Matrix', '/1.jpg', '[]', '[878]', '[]', 2, '[]', '[]')",
            )
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.listDao()
            assertTrue(dao.lists().isEmpty())
            val id = dao.create("Films de Noël", now = 10, firstFilm = 1)
            dao.add(id, 1, now = 20)
            val films = dao.observeItems(id).first()
            assertEquals(listOf(1L), films.map { it.tmdbId })
            assertNull(films.single().status)
            assertEquals(listOf(id), dao.observeListsOf(1).first())
            val summary = dao.observeLists().first().single()
            assertEquals(1, summary.count)
            assertEquals(20L, summary.updatedAt)
            // Supprimer la liste retire ses films, pas leurs fiches.
            dao.delete(id)
            assertTrue(dao.items().isEmpty())
            assertEquals("Matrix", database.movieDao().get(1)?.title)
        } finally {
            database.close()
        }
    }

    @Test
    fun version6BecomesVersion7WithSeriesColumns() = runBlocking {
        createVersion(6) { db ->
            db.execSQL(
                "INSERT INTO movie (tmdbId, title, posterPath, `cast`, genreIds, genres, detailsVersion, keywords, directors) VALUES (1, 'Matrix', '/1.jpg', '[]', '[878]', '[]', 2, '[]', '[]')",
            )
            userMovie(db, 1, rating = 1612.0, duels = 4)
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            // Les films ne bougent pas : les colonnes des séries sont vides.
            val movie = checkNotNull(database.movieDao().get(1))
            assertEquals("Matrix", movie.title)
            assertNull(movie.seasonCount)
            assertTrue(movie.seasons.isEmpty())
            val user = checkNotNull(database.userMovieDao().get(1))
            assertEquals(WatchStatus.WATCHED, user.status)
            assertEquals(1612.0, user.rating, 0.0)
            assertNull(user.progress)
            assertNull(user.currentSeason)
            // Et la colonne s'écrit.
            database.libraryDao().setProgress(1, SeriesProgress.IN_PROGRESS, 2, now = 5)
            assertEquals(SeriesProgress.IN_PROGRESS, database.userMovieDao().get(1)?.progress)
            assertEquals(2, database.userMovieDao().get(1)?.currentSeason)
        } finally {
            database.close()
        }
    }

    @Test
    fun version7BecomesVersion8WithRequestedSeasons() = runBlocking {
        createVersion(7) { db ->
            db.execSQL(
                "INSERT INTO outbox (type, service, tmdbId, title, state, attempts, createdAt, updatedAt) VALUES ('REQUEST', 'SEERR', 1396, 'Breaking Bad', 'PENDING', 0, 1, 1)",
            )
        }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            // Une demande d'avant : toute la série.
            val entry = database.outboxDao().pending().single()
            assertEquals("Breaking Bad", entry.title)
            assertNull(entry.seasonList)
            database.outboxDao().update(entry.copy(seasons = OutboxEntity.encodeSeasons(listOf(3, 1))))
            assertEquals(listOf(1, 3), database.outboxDao().pending().single().seasonList)
        } finally {
            database.close()
        }
    }

    @Test
    fun version8BecomesVersion9WithTheSeasonArena() = runBlocking {
        createVersion(8) { db -> userMovie(db, 1396, 1600.0, 4) }

        val database = Room.databaseBuilder(context, CineSwipeDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            // Le classement des séries est intact ; l'Arène des saisons part de zéro.
            assertEquals(1600.0, database.userMovieDao().get(1396)!!.rating, 0.0)
            assertTrue(database.seasonArenaDao().ranks().isEmpty())
            database.seasonArenaDao().insertRanks(listOf(SeasonRankEntity(SeasonRankEntity.key(1396, 4), 1396, 4)))
            assertEquals(1396004L, database.seasonArenaDao().ranks().single().key)
        } finally {
            database.close()
        }
    }

    private fun userMovie(db: SQLiteDatabase, id: Long, rating: Double, duels: Int) {
        db.execSQL(
            "INSERT INTO user_movie (tmdbId, status, rating, duels, availability, shownCount, updatedAt) VALUES (?, 'WATCHED', ?, ?, 'NONE', 0, 0)",
            arrayOf<Any>(id, rating, duels),
        )
    }

    /** Crée une base telle que la version [version] la laissait, d'après `app/schemas/…/<version>.json`. */
    private fun createVersion(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File("$SCHEMAS/$version.json").readText()).jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            fill(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val SCHEMAS = "schemas/io.github.deedg.cineswipe.data.db.CineSwipeDatabase"
    }
}
