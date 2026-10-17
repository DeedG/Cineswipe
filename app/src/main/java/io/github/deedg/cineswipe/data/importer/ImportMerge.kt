package io.github.deedg.cineswipe.data.importer

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.DuelEntity
import io.github.deedg.cineswipe.data.db.Impression
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.RecoSourceEntity
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.repository.ExportFile
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.domain.reco.RecoSource
import kotlin.math.abs

/** Film importé, rapproché de sa fiche TMDB. */
data class ImportEntry(
    val movie: MovieEntity,
    val watched: Boolean,
    val watchlist: Boolean,
    val rating10: Double?,
    val watchedAt: Long?,
) {
    val tmdbId: Long get() = movie.tmdbId
}

/** Effet d'un film importé sur la bibliothèque. */
enum class ImportChange { WATCHED, WATCHLIST, RATED, NONE }

/** Bilan de l'écriture d'un import. */
data class ImportCounts(
    val watched: Int = 0,
    val watchlist: Int = 0,
    val rated: Int = 0,
    val unchanged: Int = 0,
)

/**
 * Fusion d'un film importé avec ce qu'on en sait déjà. Rien n'est jamais perdu : un film vu le
 * reste, la date de visionnage connue est gardée, un film écarté ou déjà vu ne repasse pas « à voir ».
 */
object ImportMerger {
    /**
     * Réunit les films importés qui désignent la même fiche : vu si une ligne le dit, à voir
     * seulement s'il n'est pas vu, la note la plus récente connue, la date de visionnage la plus ancienne.
     */
    fun combine(entries: List<ImportEntry>): List<ImportEntry> =
        entries.groupBy { it.tmdbId }.values.map { group ->
            val watched = group.any { it.watched }
            group.first().copy(
                watched = watched,
                watchlist = !watched && group.any { it.watchlist },
                rating10 = group.lastOrNull { it.rating10 != null }?.rating10,
                watchedAt = group.mapNotNull { it.watchedAt }.minOrNull(),
            )
        }

    /** Nouvelle ligne à écrire (null si rien ne change) et nature du changement. */
    fun merge(current: UserMovieEntity?, entry: ImportEntry, now: Long): Pair<UserMovieEntity?, ImportChange> {
        val base = current ?: UserMovieEntity(tmdbId = entry.tmdbId, updatedAt = now)
        var row = base
        var change = ImportChange.NONE
        when {
            entry.watched && base.status != WatchStatus.WATCHED -> {
                row = row.copy(
                    status = WatchStatus.WATCHED,
                    watchedAt = base.watchedAt ?: entry.watchedAt ?: now,
                    watchedSource = base.watchedSource ?: WatchSource.IMPORT,
                )
                change = ImportChange.WATCHED
            }
            entry.watched && base.watchedAt == null && entry.watchedAt != null -> row = row.copy(watchedAt = entry.watchedAt)
            !entry.watched && entry.watchlist && base.status == WatchStatus.NONE -> {
                row = row.copy(status = WatchStatus.WATCHLIST)
                change = ImportChange.WATCHLIST
            }
        }
        val rating = entry.rating10
        if (rating != null && rating != base.importedRating) {
            row = row.copy(importedRating = rating)
            if (change == ImportChange.NONE) change = ImportChange.RATED
        }
        if (row == base && current != null) return null to ImportChange.NONE
        if (current == null && change == ImportChange.NONE) return null to ImportChange.NONE
        return row.copy(updatedAt = now) to change
    }
}

/**
 * Rapprochement d'un titre (et d'une année) avec les résultats de la recherche TMDB : titre
 * identique (traduit ou original, sans accents ni article), année identique ou à un an près
 * (sorties en festival). À défaut de titre identique, le premier résultat de la bonne année.
 */
object ImportMatcher {
    fun best(title: String, year: Int?, candidates: List<TmdbMovieSummaryDto>): TmdbMovieSummaryDto? {
        val wanted = key(title)
        if (wanted.isEmpty()) return null
        return candidates.take(MAX_CANDIDATES).mapIndexedNotNull { index, candidate ->
            if (candidate.adult) return@mapIndexedNotNull null
            val titles = listOfNotNull(candidate.title, candidate.originalTitle).map(::key).filter { it.isNotEmpty() }
            val titleScore = when {
                titles.any { it == wanted } -> 3
                titles.any { " $it ".contains(" $wanted ") || " $wanted ".contains(" $it ") } -> 1
                else -> 0
            }
            val candidateYear = TmdbMapper.year(candidate.releaseDate)
            val yearScore = when {
                year == null || candidateYear == null -> 0
                candidateYear == year -> 2
                abs(candidateYear - year) == 1 -> 1
                else -> -3
            }
            val score = titleScore + yearScore
            val accepted = score >= 3 || (index == 0 && yearScore == 2)
            if (accepted) Triple(candidate, score, index) else null
        }.maxWithOrNull(compareBy<Triple<TmdbMovieSummaryDto, Int, Int>> { it.second }.thenByDescending { it.third })?.first
    }

    private val ARTICLES = listOf("the ", "a ", "an ", "le ", "la ", "les ", "l ", "un ", "une ", "der ", "die ", "das ", "el ", "il ", "lo ")

    /** Titre comparable : normalisé, sans article au début. */
    fun key(title: String): String {
        val normalized = ImportFormats.normalize(title)
        val article = ARTICLES.firstOrNull { normalized.startsWith(it) && normalized.length > it.length }
        return if (article != null) normalized.removePrefix(article) else normalized
    }

    private const val MAX_CANDIDATES = 10
}

/** Liste perso d'une sauvegarde, à reprendre. */
data class ImportedList(val name: String, val createdAt: Long, val items: List<ImportedListItem>)

data class ImportedListItem(val tmdbId: Long, val addedAt: Long)

/** Sauvegarde CineSwipe convertie en lignes de la base, pour une restauration complète. */
class BackupRows(
    val movies: List<MovieEntity>,
    val userMovies: List<UserMovieEntity>,
    val duels: List<DuelEntity>,
    val sources: List<RecoSourceEntity>,
) {
    companion object {
        fun of(backup: ExportFile, now: Long): BackupRows {
            val catalog = backup.catalog.associateBy { it.tmdbId }
            val users = backup.films.map { film ->
                UserMovieEntity(
                    tmdbId = film.tmdbId,
                    status = enumOr(film.status, WatchStatus.NONE),
                    watchedAt = film.watchedAt,
                    watchedSource = film.watchedSource?.let { enumOrNull<WatchSource>(it) },
                    rating = film.rating,
                    duels = film.duels,
                    availability = enumOr(film.availability, Availability.NONE),
                    shownCount = film.shownCount,
                    lastShownAt = film.lastShownAt,
                    updatedAt = film.updatedAt.takeIf { it > 0 } ?: now,
                    deviation = film.deviation,
                    confidence = film.confidence,
                    priorRating = film.priorRating,
                    priorDuels = film.priorDuels,
                    censusAt = film.censusAt,
                    impression = film.impression?.let { enumOrNull<Impression>(it) },
                    importedRating = film.importedRating,
                    progress = film.progress?.let { enumOrNull<SeriesProgress>(it) },
                    currentSeason = film.currentSeason,
                )
            }
            // Fiches du cache exporté (incomplètes : elles seront rechargées), ou titre et année seuls.
            val movies = backup.films.filter { it.status != WatchStatus.NONE.name || it.tmdbId in catalog }.map { film ->
                catalog[film.tmdbId]?.let { movie ->
                    MovieEntity(
                        tmdbId = movie.tmdbId,
                        title = movie.title,
                        originalTitle = movie.originalTitle,
                        year = movie.year,
                        posterPath = movie.posterPath,
                        backdropPath = movie.backdropPath,
                        overview = movie.overview,
                        director = movie.director,
                        genreIds = movie.genreIds,
                        genres = movie.genres,
                        tmdbRating = movie.tmdbRating,
                        voteCount = movie.voteCount,
                        runtimeMinutes = movie.runtimeMinutes,
                        originalLanguage = movie.originalLanguage,
                        popularity = movie.popularity,
                        trailerKey = movie.trailerKey,
                        collectionId = movie.collectionId,
                    )
                } ?: MovieEntity(tmdbId = film.tmdbId, title = film.title ?: "?", year = film.year)
            }
            val duels = backup.duels.map { duel ->
                DuelEntity(
                    id = duel.id,
                    winnerId = duel.winnerId,
                    loserId = duel.loserId,
                    createdAt = duel.createdAt,
                    winnerRatingBefore = duel.winnerRatingBefore,
                    loserRatingBefore = duel.loserRatingBefore,
                    winnerRatingAfter = duel.winnerRatingAfter,
                    loserRatingAfter = duel.loserRatingAfter,
                    draw = duel.draw,
                )
            }
            val sources = backup.recoSources.mapNotNull { source ->
                enumOrNull<RecoSource>(source.source)?.let { RecoSourceEntity(it, source.shown, source.liked, source.disliked, now) }
            }
            return BackupRows(movies, users, duels, sources)
        }

        /**
         * Listes perso d'une sauvegarde et les fiches de leurs films (celles du cache exporté, sinon
         * titre et année : elles seront complétées plus tard).
         */
        fun lists(backup: ExportFile): Pair<List<ImportedList>, List<MovieEntity>> {
            val lists = backup.lists
                .filter { it.name.isNotBlank() }
                .map { list -> ImportedList(list.name.trim(), list.createdAt, list.films.map { ImportedListItem(it.tmdbId, it.addedAt) }) }
            val catalog = backup.catalog.associateBy { it.tmdbId }
            val movies = backup.lists.flatMap { it.films }.distinctBy { it.tmdbId }.map { film ->
                catalog[film.tmdbId]?.let { movie ->
                    MovieEntity(
                        tmdbId = movie.tmdbId,
                        title = movie.title,
                        originalTitle = movie.originalTitle,
                        year = movie.year,
                        posterPath = movie.posterPath,
                    )
                } ?: MovieEntity(tmdbId = film.tmdbId, title = film.title ?: "?", year = film.year)
            }
            return lists to movies
        }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

        private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T = enumOrNull<T>(name) ?: fallback
    }
}
