package io.github.deedg.cineswipe.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportFormatsTest {

    private fun file(name: String, text: String) = ImportFile(name, text.toByteArray())

    private fun zip(vararg entries: Pair<String, String>): ImportFile {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return ImportFile("letterboxd-export.zip", bytes.toByteArray())
    }

    private fun day(text: String) = Instant.parse("${text}T00:00:00Z").toEpochMilli()

    @Test
    fun letterboxdArchiveMergesWatchedRatingsDiaryAndWatchlist() {
        val batch = ImportFormats.parse(
            listOf(
                zip(
                    "profile.csv" to "Date Joined,Username\n2020-01-01,alex\n",
                    "watched.csv" to "Date,Name,Year,Letterboxd URI\n2021-03-04,Dune,2021,https://boxd.it/a\n2021-03-05,\"Crouching Tiger, Hidden Dragon\",2000,https://boxd.it/b\n",
                    "ratings.csv" to "Date,Name,Year,Letterboxd URI,Rating\n2021-03-04,Dune,2021,https://boxd.it/a,4.5\n",
                    "diary.csv" to "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n2021-03-06,Dune,2021,https://boxd.it/c,4.5,,,2021-03-01\n",
                    "watchlist.csv" to "Date,Name,Year,Letterboxd URI\n2022-01-01,Drive,2011,https://boxd.it/d\n",
                    "lists/favs.csv" to "Letterboxd list export v7\nDate,Name\n",
                    "deleted/watched.csv" to "Date,Name,Year,Letterboxd URI\n2021-03-04,Effacé,2021,https://boxd.it/x\n",
                ),
            ),
        )
        assertEquals(setOf(ImportSource.LETTERBOXD), batch.sources)
        assertEquals(3, batch.items.size)
        val dune = batch.items.single { it.title == "Dune" }
        assertTrue(dune.watched)
        assertEquals(9.0, dune.rating10!!, 0.0)
        // Date du journal (vraie date de visionnage), plus ancienne que celle de watched.csv.
        assertEquals(day("2021-03-01"), dune.watchedAt)
        assertEquals(2000, batch.items.single { it.title == "Crouching Tiger, Hidden Dragon" }.year)
        val drive = batch.items.single { it.title == "Drive" }
        assertTrue(drive.watchlist)
        assertFalse(drive.watched)
        assertTrue(batch.ignored.isEmpty())
    }

    @Test
    fun imdbRatingsAndWatchlistKeepMoviesOnly() {
        val ratings = file(
            "ratings.csv",
            "Const,Your Rating,Date Rated,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors\n" +
                "tt1160419,8,2021-10-01,Dune: Part One,Dune: Part One,https://www.imdb.com/title/tt1160419/,Movie,8.0,155,2021,Sci-Fi,800000,2021-09-15,Denis Villeneuve\n" +
                "tt0903747,10,2020-01-01,Breaking Bad,Breaking Bad,https://www.imdb.com/title/tt0903747/,TV Series,9.5,49,2008,Drama,2000000,2008-01-20,\n",
        )
        val watchlist = file(
            "WATCHLIST.csv",
            "Position,Const,Created,Modified,Description,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors,Your Rating,Date Rated\n" +
                "1,tt0780504,2022-01-01,2022-01-01,,Drive,Drive,https://www.imdb.com/title/tt0780504/,Movie,7.8,100,2011,Drama,700000,2011-09-16,Nicolas Winding Refn,,\n",
        )
        val batch = ImportFormats.parse(listOf(ratings, watchlist))
        assertEquals(setOf(ImportSource.IMDB), batch.sources)
        assertEquals(2, batch.items.size)
        val dune = batch.items.single { it.imdbId == "tt1160419" }
        assertTrue(dune.watched)
        assertEquals(8.0, dune.rating10!!, 0.0)
        assertEquals(2021, dune.year)
        val drive = batch.items.single { it.imdbId == "tt0780504" }
        assertTrue(drive.watchlist)
        assertNull(drive.rating10)
    }

    @Test
    fun traktJsonUsesIdsRatingsAndWatchlist() {
        val watched = file(
            "watched-movies.json",
            """[{"plays":2,"last_watched_at":"2023-05-01T20:00:00.000Z","movie":{"title":"Dune","year":2021,"ids":{"trakt":1,"imdb":"tt1160419","tmdb":438631}}}]""",
        )
        val ratings = file(
            "ratings-movies.json",
            """[{"rated_at":"2023-05-02T10:00:00.000Z","rating":7,"type":"movie","movie":{"title":"Drive","year":2011,"ids":{"tmdb":64690,"imdb":"tt0780504"}}},""" +
                """{"rated_at":"2023-05-02T10:00:00.000Z","rating":9,"type":"show","show":{"title":"Dark","ids":{"tmdb":70523}}}]""",
        )
        val watchlist = file(
            "watchlist-movies.json",
            """[{"listed_at":"2023-01-01T00:00:00.000Z","type":"movie","movie":{"title":"Arrival","year":2016,"ids":{"tmdb":329865}}}]""",
        )
        val collection = file("collection-movies.json", """[{"collected_at":"2023-01-01T00:00:00.000Z","movie":{"title":"X","ids":{"tmdb":1}}}]""")
        val batch = ImportFormats.parse(listOf(watched, ratings, watchlist, collection))
        assertEquals(setOf(ImportSource.TRAKT), batch.sources)
        assertEquals(listOf(438631L, 64690L, 329865L), batch.items.map { it.tmdbId })
        val dune = batch.items[0]
        assertTrue(dune.watched)
        assertEquals(Instant.parse("2023-05-01T20:00:00Z").toEpochMilli(), dune.watchedAt)
        val drive = batch.items[1]
        assertTrue(drive.watched)
        assertEquals(7.0, drive.rating10!!, 0.0)
        assertTrue(batch.items[2].watchlist)
    }

    @Test
    fun genericCsvDetectsSeparatorScaleAndStatus() {
        val csv = file(
            "senscritique.csv",
            "Titre;Année;Note;Statut\nLe Fabuleux Destin d'Amélie Poulain;2001;8;Vu\nDune;2021;;Envie de voir\nPersona;1966;10;vu\n",
        )
        val batch = ImportFormats.parse(listOf(csv))
        assertEquals(setOf(ImportSource.CSV), batch.sources)
        assertEquals(3, batch.items.size)
        assertEquals(8.0, batch.items[0].rating10!!, 0.0)
        assertTrue(batch.items[1].watchlist)
        assertFalse(batch.items[1].watched)
        assertEquals(1966, batch.items[2].year)

        // Notes sur 5 (TV Time, Simkl…) : doublées.
        val five = ImportFormats.parse(listOf(file("films.csv", "title,year,rating\nDune,2021,4.5\nDrive,2011,3\n")))
        assertEquals(9.0, five.items[0].rating10!!, 0.0)
        assertEquals(6.0, five.items[1].rating10!!, 0.0)

        // Notes sur 100 : divisées par 10.
        val hundred = ImportFormats.parse(listOf(file("films.csv", "title,rating\nDune,85\n")))
        assertEquals(8.5, hundred.items[0].rating10!!, 1e-9)
    }

    @Test
    fun cineSwipeBackupIsRecognised() {
        val backup = file(
            "cineswipe-20261005.json",
            """{"app":"CineSwipe","version":"0.1.20","exportedAt":1,"schema":4,""" +
                """"films":[{"tmdbId":64690,"title":"Drive","year":2011,"status":"WATCHED","rating":1600.0,"deviation":200.0,"confidence":0.5,""" +
                """"duels":3,"priorRating":1500.0,"priorDuels":0,"availability":"NONE","shownCount":0,"updatedAt":5},""" +
                """{"tmdbId":329865,"status":"WATCHLIST","rating":1500.0,"deviation":350.0,"confidence":0.0,"duels":0,"priorRating":1500.0,""" +
                """"priorDuels":0,"availability":"NONE","shownCount":0,"updatedAt":5},""" +
                """{"tmdbId":1,"status":"NONE","rating":1500.0,"deviation":350.0,"confidence":0.0,"duels":0,"priorRating":1500.0,""" +
                """"priorDuels":0,"availability":"NONE","shownCount":0,"censusAt":3,"updatedAt":5}],""" +
                """"duels":[],"recoSources":[],"catalog":[]}""",
        )
        val batch = ImportFormats.parse(listOf(backup))
        assertEquals(setOf(ImportSource.CINESWIPE), batch.sources)
        assertNotNull(batch.backup)
        assertEquals(2, batch.items.size)
        assertTrue(batch.items.single { it.tmdbId == 64690L }.watched)
        assertTrue(batch.items.single { it.tmdbId == 329865L }.watchlist)
    }

    @Test
    fun unknownFilesAreReportedAsIgnored() {
        val batch = ImportFormats.parse(listOf(file("notes.txt", "rien à voir"), file("data.json", """{"hello":1}""")))
        assertTrue(batch.items.isEmpty())
        assertEquals(listOf("notes.txt", "data.json"), batch.ignored)
    }

    @Test
    fun csvHandlesQuotesNewlinesAndCrlf() {
        val rows = Csv.parse("a,b\r\n\"x, y\",\"he said \"\"hi\"\"\nline 2\"\r\nlast,1")
        assertEquals(listOf(listOf("a", "b"), listOf("x, y", "he said \"hi\"\nline 2"), listOf("last", "1")), rows)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a\tb\n1\t2\n"))
    }

    @Test
    fun datesInCommonFormats() {
        assertEquals(day("2021-03-04"), ImportFormats.parseDate("2021-03-04"))
        assertEquals(day("2021-03-04"), ImportFormats.parseDate("04/03/2021"))
        assertEquals(Instant.parse("2021-03-04T10:00:00Z").toEpochMilli(), ImportFormats.parseDate("2021-03-04T10:00:00Z"))
        assertNull(ImportFormats.parseDate("bientôt"))
    }
}
