package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Un film vu, avec sa date, pour la rétro de l'année. */
data class RetroRow(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val directors: List<TmdbTag>,
    val rating: Double,
    val watchedAt: Long?,
    val censusAt: Long?,
    val watchedSource: WatchSource?,
)

/** Un film mis en avant dans la rétro, avec ses étoiles (sur tout le classement). */
data class RetroFilm(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val stars: Float,
)

/**
 * Rétro d'une année. Les films « retrouvés » (recensement, imports sans date, tous datés du même
 * instant) ne disent pas quand ils ont été vus : ils sont comptés à part, hors des mois et du top.
 */
data class YearInReview(
    val year: Int,
    val films: Int,
    /** Films retrouvés cette année (recensement, imports sans date). */
    val backfill: Int,
    val hours: Int,
    /** Films par mois, de janvier à décembre. */
    val months: List<Int>,
    /** Mois le plus chargé (0 = janvier), s'il y a des films. */
    val bestMonth: Int?,
    val duels: Int,
    val top: List<RetroFilm>,
    val genre: String?,
    val genreFilms: Int,
    val director: TmdbTag?,
    val directorFilms: Int,
    val first: RetroFilm?,
    val last: RetroFilm?,
) {
    val empty: Boolean get() = films == 0
}

class RetroRepository(
    private val database: CineSwipeDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Années à revoir (la plus récente d'abord) et la rétro de [year] (par défaut, la plus récente). */
    suspend fun load(year: Int? = null): Pair<List<Int>, YearInReview?> = withContext(Dispatchers.Default) {
        val rows = database.userMovieDao().retroRows()
        val duels = database.diagnosticsDao().duelTimes()
        val years = years(rows, duels, zone())
        val chosen = year?.takeIf { it in years } ?: years.firstOrNull()
        years to chosen?.let { compute(rows, duels, it, zone()) }
    }

    companion object {
        /** Au-delà, des films datés du même instant viennent d'un import en bloc, pas d'un visionnage. */
        const val BULK = 8
        private const val CENSUS_TOLERANCE_MS = 60_000L
        const val TOP = 5

        fun yearOf(at: Long, zone: ZoneId): Int = Instant.ofEpochMilli(at).atZone(zone).year

        private fun monthOf(at: Long, zone: ZoneId): Int = Instant.ofEpochMilli(at).atZone(zone).monthValue - 1

        /** Films retrouvés plutôt que vus : réponses du recensement et lots d'un import sans date. */
        fun backfillIds(rows: List<RetroRow>): Set<Long> {
            val bulk = rows.mapNotNull { it.watchedAt }.groupingBy { it }.eachCount().filterValues { it >= BULK }.keys
            return rows.filter { row ->
                val at = row.watchedAt ?: return@filter false
                val census = row.censusAt
                (census != null && abs(at - census) <= CENSUS_TOLERANCE_MS) || at in bulk
            }.map { it.tmdbId }.toSet()
        }

        fun years(rows: List<RetroRow>, duelTimes: List<Long>, zone: ZoneId): List<Int> =
            (rows.mapNotNull { it.watchedAt } + duelTimes).map { yearOf(it, zone) }.distinct().sortedDescending()

        fun compute(rows: List<RetroRow>, duelTimes: List<Long>, year: Int, zone: ZoneId): YearInReview {
            val stars = StarScale.computeStars(rows.associate { it.tmdbId to it.rating })
            val backfill = backfillIds(rows)
            val ofYear = rows.filter { row -> row.watchedAt?.let { yearOf(it, zone) } == year }
            val seen = ofYear.filter { it.tmdbId !in backfill }.sortedBy { it.watchedAt }
            fun film(row: RetroRow) = RetroFilm(row.tmdbId, row.title, row.year, row.posterPath, stars[row.tmdbId] ?: 0f)
            val months = MutableList(12) { 0 }
            seen.forEach { row -> months[monthOf(row.watchedAt!!, zone)]++ }
            val genre = seen.flatMap { it.genres }.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            val director = seen.flatMap { it.directors }.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<TmdbTag, Int>> { it.value }.thenByDescending { it.key.name })
                ?.takeIf { it.value >= 2 }
            return YearInReview(
                year = year,
                films = seen.size,
                backfill = ofYear.size - seen.size,
                hours = seen.sumOf { it.runtimeMinutes ?: 0 } / 60,
                months = months,
                bestMonth = months.indices.maxByOrNull { months[it] }?.takeIf { months[it] > 0 },
                duels = duelTimes.count { yearOf(it, zone) == year },
                top = seen.sortedWith(compareByDescending<RetroRow> { it.rating }.thenBy { it.title }).take(TOP).map(::film),
                genre = genre?.key,
                genreFilms = genre?.value ?: 0,
                director = director?.key,
                directorFilms = director?.value ?: 0,
                first = seen.firstOrNull()?.let(::film),
                last = seen.lastOrNull()?.takeIf { seen.size > 1 }?.let(::film),
            )
        }
    }
}
