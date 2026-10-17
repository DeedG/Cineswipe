package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.TvSeason
import io.github.deedg.cineswipe.domain.SeriesTime
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un film vu, avec ce qui sert aux stats de goûts. */
data class StatsRow(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val keywords: List<TmdbTag>,
    val directors: List<TmdbTag>,
    val cast: List<CastMember>,
    val originalLanguage: String?,
    val tmdbRating: Double?,
    val voteCount: Int?,
    val rating: Double,
    /** Série : saisons, avancement et saison en cours (le temps passé s'estime par épisodes). */
    val seasonCount: Int? = null,
    val seasons: List<TvSeason> = emptyList(),
    val progress: SeriesProgress? = null,
    val currentSeason: Int? = null,
) {
    /** Minutes passées devant : la durée du film, ou les épisodes vus d'une série. */
    val minutes: Int get() = SeriesTime.minutes(runtimeMinutes, seasonCount, seasons, progress, currentSeason)

    val isSeries: Boolean get() = SeriesTime.isSeries(seasonCount, seasons, progress)
}

/**
 * Un groupe de films (un genre, un réalisateur, une décennie…) : nombre de films vus et
 * étoiles moyennes, ramenées vers la moyenne générale quand il y a peu de films.
 */
data class StatsGroup(
    val key: String,
    val name: String,
    val films: Int,
    val averageStars: Double,
    /** Note de classement : moyenne prudente (peu de films = proche de la moyenne). */
    val score: Double,
    /** Identifiant TMDB d'une personne (réalisateur, acteur), pour ouvrir sa fiche. */
    val personId: Long? = null,
    val imagePath: String? = null,
)

/** Un film où l'avis de l'utilisateur et celui du public divergent. */
data class StatsFilm(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val stars: Float,
    val tmdbRating: Double,
)

data class TasteStats(
    val watched: Int,
    val hours: Int,
    val duels: Int,
    val directorCount: Int,
    val genres: List<StatsGroup>,
    val directors: List<StatsGroup>,
    val actors: List<StatsGroup>,
    /** Toutes les décennies, dans l'ordre chronologique. */
    val decades: List<StatsGroup>,
    val languages: List<StatsGroup>,
    val themes: List<StatsGroup>,
    /** Adorés malgré une note TMDB moyenne. */
    val lovedAgainst: List<StatsFilm>,
    /** Peu aimés malgré une bonne note TMDB. */
    val dislikedAgainst: List<StatsFilm>,
    val mostWatchedDirector: StatsGroup?,
    val favoriteDecade: StatsGroup?,
    /** Séries : combien de finies, en cours, vues par épisodes et décrochées. */
    val progress: Map<SeriesProgress, Int> = emptyMap(),
    /** Séries : épisodes vus (estimés). */
    val episodes: Int = 0,
) {
    companion object {
        /** Prudence : un groupe compte comme s'il avait ce nombre de films « moyens » en plus. */
        private const val SHRINK = 3.0
        private const val TOP = 10

        fun compute(rows: List<StatsRow>, duels: Int): TasteStats {
            val stars = StarScale.computeStars(rows.associate { it.tmdbId to it.rating })
            val starsOf = { row: StatsRow -> (stars[row.tmdbId] ?: 3f).toDouble() }
            val mean = if (rows.isEmpty()) 3.0 else rows.sumOf(starsOf) / rows.size

            fun group(
                minFilms: Int,
                keys: (StatsRow) -> List<Triple<String, String, Pair<Long?, String?>>>,
            ): List<StatsGroup> {
                val members = LinkedHashMap<String, MutableList<StatsRow>>()
                val labels = HashMap<String, Triple<String, Long?, String?>>()
                rows.forEach { row ->
                    keys(row).distinctBy { it.first }.forEach { (key, name, extra) ->
                        members.getOrPut(key) { ArrayList() } += row
                        if (key !in labels) labels[key] = Triple(name, extra.first, extra.second)
                    }
                }
                return members.filter { it.value.size >= minFilms }.map { (key, films) ->
                    val sum = films.sumOf(starsOf)
                    val label = labels.getValue(key)
                    StatsGroup(
                        key = key,
                        name = label.first,
                        films = films.size,
                        averageStars = sum / films.size,
                        score = (sum + SHRINK * mean) / (films.size + SHRINK),
                        personId = label.second,
                        imagePath = label.third,
                    )
                }
            }
            fun List<StatsGroup>.best(count: Int = TOP) = sortedWith(compareByDescending<StatsGroup> { it.score }.thenByDescending { it.films }).take(count)

            val genres = group(3) { row -> row.genres.map { Triple(it, it, null to null) } }.best(20)
            val directorsAll = group(1) { row -> row.directors.map { Triple(it.id.toString(), it.name, it.id to null) } }
            val directors = directorsAll.filter { it.films >= 2 }.best()
            val actors = group(3) { row ->
                row.cast.take(5).mapNotNull { member -> member.id?.let { Triple(it.toString(), member.name, it to member.profilePath) } }
            }.best()
            val decades = group(1) { row -> row.year?.takeIf { it in 1890..2200 }?.let { listOf((it / 10 * 10).toString().let { d -> Triple(d, d, null to null) }) }.orEmpty() }
                .sortedBy { it.key }
            val languages = group(3) { row -> listOfNotNull(row.originalLanguage?.let { Triple(it, it, null to null) }) }
                .sortedByDescending { it.films }.take(8)
            val themes = group(4) { row -> row.keywords.map { Triple(it.id.toString(), it.name, null to null) } }.best(15)

            fun film(row: StatsRow) = StatsFilm(row.tmdbId, row.title, row.year, row.posterPath, stars[row.tmdbId] ?: 3f, row.tmdbRating ?: 0.0)
            val rated = rows.filter { it.tmdbRating != null && (it.voteCount ?: 0) >= 50 }
            val lovedAgainst = rated.filter { (stars[it.tmdbId] ?: 0f) >= 4f && it.tmdbRating!! <= 6.6 }
                .sortedByDescending { starsOf(it) * 2 - it.tmdbRating!! }.take(6).map(::film)
            val dislikedAgainst = rated.filter { (stars[it.tmdbId] ?: 5f) <= 2f && it.tmdbRating!! >= 7.4 }
                .sortedByDescending { it.tmdbRating!! - starsOf(it) * 2 }.take(6).map(::film)

            return TasteStats(
                watched = rows.size,
                hours = rows.sumOf { it.minutes } / 60,
                duels = duels,
                directorCount = directorsAll.size,
                genres = genres,
                directors = directors,
                actors = actors,
                decades = decades,
                languages = languages,
                themes = themes,
                lovedAgainst = lovedAgainst,
                dislikedAgainst = dislikedAgainst,
                mostWatchedDirector = directorsAll.maxWithOrNull(compareBy<StatsGroup> { it.films }.thenBy { it.score })?.takeIf { it.films >= 2 },
                favoriteDecade = decades.filter { it.films >= 5 }.maxByOrNull { it.score },
                progress = rows.filter { it.isSeries }.groupingBy { it.progress ?: SeriesProgress.FINISHED }.eachCount(),
                episodes = rows.filter { it.isSeries }.sumOf { SeriesTime.episodes(it.seasonCount, it.seasons, it.progress, it.currentSeason) },
            )
        }
    }
}

/** Stats de goûts, calculées à partir des films vus et de leur classement. */
class StatsRepository(private val database: CineSwipeDatabase) {
    suspend fun load(): TasteStats = withContext(Dispatchers.Default) {
        val rows = database.userMovieDao().statsRows()
        val duels = database.diagnosticsDao().duelCount() + database.diagnosticsDao().inheritedDuels() / 2
        TasteStats.compute(rows, duels)
    }
}
