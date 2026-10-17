package io.github.deedg.cineswipe.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.deedg.cineswipe.data.homelab.FreshWatch
import io.github.deedg.cineswipe.data.homelab.HomelabSnapshot
import io.github.deedg.cineswipe.data.homelab.SyncMerger
import io.github.deedg.cineswipe.data.importer.BackupRows
import io.github.deedg.cineswipe.data.importer.ImportChange
import io.github.deedg.cineswipe.data.importer.ImportCounts
import io.github.deedg.cineswipe.data.importer.ImportEntry
import io.github.deedg.cineswipe.data.importer.ImportMerger
import io.github.deedg.cineswipe.data.importer.ImportedList
import io.github.deedg.cineswipe.data.model.CardStatusRow
import io.github.deedg.cineswipe.data.model.DuelHistoryRow
import io.github.deedg.cineswipe.data.model.LibraryItem
import io.github.deedg.cineswipe.data.model.LibraryStats
import io.github.deedg.cineswipe.data.repository.FilmDiagnostic
import io.github.deedg.cineswipe.data.repository.RetroRow
import io.github.deedg.cineswipe.data.repository.StatsRow
import io.github.deedg.cineswipe.domain.ArenaCandidate
import io.github.deedg.cineswipe.domain.DuelOutcome
import io.github.deedg.cineswipe.domain.FilmRating
import io.github.deedg.cineswipe.domain.Ranking
import io.github.deedg.cineswipe.domain.RankingPrior
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusSource
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

private const val LIBRARY_QUERY = """
    SELECT u.tmdbId AS tmdbId, m.title AS title, m.year AS year, m.posterPath AS posterPath,
           u.status AS status, u.rating AS rating, u.duels AS duels, u.confidence AS confidence,
           u.availability AS availability, u.watchedAt AS watchedAt,
           u.progress AS progress, u.currentSeason AS currentSeason
    FROM user_movie AS u
    INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.status IN ('WATCHED', 'WATCHLIST') OR u.availability != 'NONE'
"""

private const val STATS_QUERY = """
    SELECT
        COALESCE(SUM(CASE WHEN status = 'WATCHED' THEN 1 ELSE 0 END), 0) AS watched,
        COALESCE(SUM(CASE WHEN status = 'WATCHLIST' THEN 1 ELSE 0 END), 0) AS watchlist,
        COALESCE(SUM(CASE WHEN availability = 'AVAILABLE' THEN 1 ELSE 0 END), 0) AS available,
        COALESCE(SUM(duels), 0) / 2 AS duels
    FROM user_movie
"""

/**
 * Films classés : les films vus qui ont une fiche. Les étoiles sont calculées selon le rang
 * parmi eux (bibliothèque, profil, Arène, fiche).
 */
private const val RANKED_QUERY = """
    SELECT u.tmdbId AS tmdbId, u.rating AS rating, u.duels AS duels, u.deviation AS deviation, u.confidence AS confidence
    FROM user_movie AS u
    INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.status = 'WATCHED'
"""

/**
 * Films vus qui peuvent combattre : l'Arène a besoin du titre et de l'affiche. Un film tout juste
 * venu de Jellyfin ou Plex (fiche partielle, sans affiche) attend d'être complété depuis TMDB.
 */
private const val WATCHED_CANDIDATES_QUERY = """
    $RANKED_QUERY AND (m.posterPath IS NOT NULL OR m.fetchedAt IS NOT NULL)
"""

/**
 * Délai avant de reproposer un film montré sans réaction : [cooldown] après le premier
 * affichage, puis le double à chaque fois (1, 2, 4, 8, 16 mois au plus). Un film ignoré n'est
 * jamais exclu pour de bon : il revient s'il correspond toujours aux goûts.
 */
private const val STILL_COOLING = """
    (lastShownAt IS NOT NULL AND lastShownAt + :cooldown * (1 << MIN(MAX(shownCount, 1) - 1, 4)) > :now)
"""

/**
 * Films à ne pas proposer dans les recos : déjà traités (vu, à voir, pas pour moi), montrés
 * récemment (délai croissant), ou présents sur le serveur quand le homelab est actif. Une
 * réponse « pas vu » au recensement ne compte pas.
 */
private const val RECOS_EXCLUDED_QUERY = """
    SELECT tmdbId FROM user_movie
    WHERE status != 'NONE'
       OR $STILL_COOLING
       OR (:excludeAvailable AND availability != 'NONE')
"""

/** Films déjà traités, exclus de la découverte et des recos Éclair. */
private const val ACTED_QUERY = """
    SELECT tmdbId FROM user_movie
    WHERE status != 'NONE' OR (:excludeAvailable AND availability != 'NONE')
"""

private const val CARD_STATUSES_QUERY =
    "SELECT tmdbId, status, availability FROM user_movie WHERE status != 'NONE' OR availability != 'NONE'"

@Dao
interface MovieDao {
    @Query("SELECT * FROM movie WHERE tmdbId = :tmdbId")
    fun observe(tmdbId: Long): Flow<MovieEntity?>

    @Query("SELECT * FROM movie WHERE tmdbId = :tmdbId")
    suspend fun get(tmdbId: Long): MovieEntity?

    @Upsert
    suspend fun upsert(movie: MovieEntity)
}

@Dao
interface UserMovieDao {
    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    fun observe(tmdbId: Long): Flow<UserMovieEntity?>

    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    suspend fun get(tmdbId: Long): UserMovieEntity?

    @Query("SELECT * FROM user_movie WHERE tmdbId IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<UserMovieEntity>

    /** Films vus, pour les stats de goûts. */
    @Query(
        """
        SELECT u.tmdbId AS tmdbId, m.title AS title, m.year AS year, m.posterPath AS posterPath,
               m.runtimeMinutes AS runtimeMinutes, m.genres AS genres, m.keywords AS keywords,
               m.directors AS directors, m.`cast` AS `cast`, m.originalLanguage AS originalLanguage,
               m.tmdbRating AS tmdbRating, m.voteCount AS voteCount, u.rating AS rating,
               m.seasonCount AS seasonCount, m.seasons AS seasons, u.progress AS progress, u.currentSeason AS currentSeason
        FROM user_movie AS u
        INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
        WHERE u.status = 'WATCHED'
        """,
    )
    suspend fun statsRows(): List<StatsRow>

    /** Films vus et leur date, pour la rétro de l'année. */
    @Query(
        """
        SELECT u.tmdbId AS tmdbId, m.title AS title, m.year AS year, m.posterPath AS posterPath,
               m.runtimeMinutes AS runtimeMinutes, m.genres AS genres, m.directors AS directors,
               u.rating AS rating, u.watchedAt AS watchedAt, u.censusAt AS censusAt, u.watchedSource AS watchedSource,
               m.seasonCount AS seasonCount, m.seasons AS seasons, u.progress AS progress, u.currentSeason AS currentSeason
        FROM user_movie AS u
        INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
        WHERE u.status = 'WATCHED'
        """,
    )
    suspend fun retroRows(): List<RetroRow>

    @Query("SELECT * FROM user_movie WHERE tmdbId IN (:ids)")
    fun observeAll(ids: List<Long>): Flow<List<UserMovieEntity>>

    @Query(LIBRARY_QUERY)
    fun observeLibrary(): Flow<List<LibraryItem>>

    @Query(STATS_QUERY)
    fun observeStats(): Flow<LibraryStats>

    @Query(WATCHED_CANDIDATES_QUERY)
    suspend fun watchedCandidates(): List<ArenaCandidate>

    @Query("SELECT COUNT(*) FROM ($WATCHED_CANDIDATES_QUERY)")
    suspend fun watchedCount(): Int

    @Query(RANKED_QUERY)
    suspend fun ranked(): List<ArenaCandidate>

    @Query(RANKED_QUERY)
    fun observeRanked(): Flow<List<ArenaCandidate>>

    @Query(RECOS_EXCLUDED_QUERY)
    suspend fun recosExcludedIds(cooldown: Long, now: Long, excludeAvailable: Boolean): List<Long>

    @Query(ACTED_QUERY)
    suspend fun actedIds(excludeAvailable: Boolean): List<Long>

    @Query(CARD_STATUSES_QUERY)
    fun observeCardStatuses(): Flow<List<CardStatusRow>>
}

/** Ce que le calcul du classement lit pour chaque film. */
data class RankingRow(
    val tmdbId: Long,
    val rating: Double,
    val deviation: Double,
    val confidence: Double,
    val duels: Int,
    val priorRating: Double,
    val priorDuels: Int,
    val impression: Impression?,
    val importedRating: Double?,
)

/**
 * Films à classer : les films vus. Un film retiré des vus (ou finalement « pas vu ») sort du
 * calcul : sa note et ses duels restent tels quels, et comptent de nouveau s'il redevient vu.
 */
private const val RANKING_ROWS_QUERY = """
    SELECT tmdbId, rating, deviation, confidence, duels, priorRating, priorDuels, impression, importedRating FROM user_movie
    WHERE status = 'WATCHED'
"""

/** Duels du journal entre deux films vus : un duel contre un film qui n'est plus vu ne compte pas. */
private const val WATCHED_DUELS = """
    FROM duel AS d
    INNER JOIN user_movie AS w ON w.tmdbId = d.winnerId AND w.status = 'WATCHED'
    INNER JOIN user_movie AS l ON l.tmdbId = d.loserId AND l.status = 'WATCHED'
"""

/**
 * Échelle des notes reprises : chaque note est comparée à la moyenne et à la dispersion de tes
 * autres notes (un 7/10 chez qui note tout 8 est une déception), au plus ± 2,5 écarts types.
 */
internal class ImportedScale(private val mean: Double, private val spread: Double) {
    fun shift(rating: Double): Double = ((rating - mean) / spread).coerceIn(-MAX_Z, MAX_Z) * POINTS_PER_SPREAD

    companion object {
        private const val MAX_Z = 2.5
        private const val POINTS_PER_SPREAD = 200.0
        private const val MIN_RATINGS = 5
        private const val MIN_SPREAD = 0.8

        fun of(ratings: List<Double>): ImportedScale {
            if (ratings.size < MIN_RATINGS) return ImportedScale(6.5, 1.8)
            val mean = ratings.average()
            val variance = ratings.sumOf { (it - mean) * (it - mean) } / ratings.size
            return ImportedScale(mean, kotlin.math.sqrt(variance).coerceAtLeast(MIN_SPREAD))
        }
    }
}

/** Écritures de l'Arène, chacune dans une transaction avec le recalcul du classement. */
@Dao
abstract class ArenaDao {
    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    abstract suspend fun userMovie(tmdbId: Long): UserMovieEntity?

    @Insert
    abstract suspend fun insertDuel(duel: DuelEntity): Long

    @Query("SELECT * FROM duel ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastDuel(): DuelEntity?

    @Query(DUEL_HISTORY_QUERY)
    abstract fun observeHistory(limit: Int): Flow<List<DuelHistoryRow>>

    @Query("SELECT COUNT(*) FROM duel")
    abstract fun observeDuelCount(): Flow<Int>

    @Query("DELETE FROM duel WHERE id = :id")
    abstract suspend fun deleteDuel(id: Long)

    @Query(RANKING_ROWS_QUERY)
    abstract suspend fun rankingRows(): List<RankingRow>

    @Query("SELECT d.winnerId AS winnerId, d.loserId AS loserId, d.draw AS draw $WATCHED_DUELS ORDER BY d.id")
    abstract suspend fun duelOutcomes(): List<DuelOutcome>

    /** Nombre de duels du journal par paire de films (la plus petite id d'abord). */
    @Query(
        """
        SELECT MIN(winnerId, loserId) AS lowId, MAX(winnerId, loserId) AS highId, COUNT(*) AS duels
        FROM duel GROUP BY MIN(winnerId, loserId), MAX(winnerId, loserId)
        """,
    )
    abstract suspend fun pairCounts(): List<PairCount>

    @Query("SELECT u.tmdbId FROM user_movie AS u INNER JOIN movie AS m ON m.tmdbId = u.tmdbId WHERE u.status = 'WATCHED'")
    abstract suspend fun rankedIds(): List<Long>

    @Query("UPDATE user_movie SET rating = :rating, deviation = :deviation, confidence = :confidence, duels = :duels WHERE tmdbId = :tmdbId")
    abstract suspend fun updateRanking(tmdbId: Long, rating: Double, deviation: Double, confidence: Double, duels: Int)

    @Query("UPDATE user_movie SET updatedAt = :now WHERE tmdbId IN (:ids)")
    abstract suspend fun touch(ids: List<Long>, now: Long)

    @Query("UPDATE duel SET winnerRatingAfter = :winnerAfter, loserRatingAfter = :loserAfter WHERE id = :id")
    abstract suspend fun setDuelResult(id: Long, winnerAfter: Double, loserAfter: Double)

    /**
     * Enregistre un duel puis recalcule tout le classement. [draw] : égalité, les deux films se
     * valent. Renvoie le duel avec les notes d'avant et d'après.
     */
    @Transaction
    open suspend fun recordDuel(winnerId: Long, loserId: Long, draw: Boolean, now: Long): DuelEntity? {
        if (winnerId == loserId) return null
        val winner = userMovie(winnerId) ?: return null
        val loser = userMovie(loserId) ?: return null
        val pending = DuelEntity(
            winnerId = winnerId,
            loserId = loserId,
            createdAt = now,
            winnerRatingBefore = winner.rating,
            loserRatingBefore = loser.rating,
            winnerRatingAfter = winner.rating,
            loserRatingAfter = loser.rating,
            draw = draw,
        )
        val id = insertDuel(pending)
        val ratings = rerank()
        val winnerAfter = ratings[winnerId]?.rating ?: winner.rating
        val loserAfter = ratings[loserId]?.rating ?: loser.rating
        setDuelResult(id, winnerAfter, loserAfter)
        touch(listOf(winnerId, loserId), now)
        return pending.copy(id = id, winnerRatingAfter = winnerAfter, loserRatingAfter = loserAfter)
    }

    @Query("SELECT * FROM duel WHERE id = :id")
    abstract suspend fun duel(id: Long): DuelEntity?

    /** Annule ce duel précis (et pas un autre enregistré entre-temps), puis recalcule le classement. */
    @Transaction
    open suspend fun undoDuel(id: Long, now: Long): DuelEntity? {
        val duel = duel(id) ?: return null
        deleteDuel(id)
        rerank()
        touch(listOf(duel.winnerId, duel.loserId), now)
        return duel
    }

    /** Annule le duel le plus récent : il quitte le journal et le classement est recalculé sans lui. */
    @Transaction
    open suspend fun undoLastDuel(now: Long): DuelEntity? {
        val duel = lastDuel() ?: return null
        deleteDuel(duel.id)
        rerank()
        touch(listOf(duel.winnerId, duel.loserId), now)
        return duel
    }

    /**
     * Recalcule les notes, incertitudes et confiances de tous les films à partir du journal
     * (classement de Bradley-Terry, voir [Ranking]). Seules les lignes qui changent sont écrites.
     */
    @Transaction
    open suspend fun rerank(): Map<Long, FilmRating> {
        val rows = rankingRows()
        if (rows.isEmpty()) return emptyMap()
        val imported = ImportedScale.of(rows.mapNotNull { it.importedRating })
        val ratings = Ranking.compute(rows.map { prior(it, imported) }, duelOutcomes())
        val ranked = rankedIds().mapNotNull { id -> ratings[id]?.let { id to it } }.toMap()
        val confidence = Ranking.starConfidence(ranked)
        rows.forEach { row ->
            val rating = ratings[row.tmdbId] ?: return@forEach
            val newConfidence = confidence[row.tmdbId] ?: row.confidence
            val changed = abs(rating.rating - row.rating) >= RATING_EPSILON ||
                abs(rating.deviation - row.deviation) >= RATING_EPSILON ||
                abs(newConfidence - row.confidence) >= CONFIDENCE_EPSILON ||
                rating.duels != row.duels
            if (changed) updateRanking(row.tmdbId, rating.rating, rating.deviation, newConfidence, rating.duels)
        }
        return ratings
    }

    /**
     * Point de départ d'un film : sa note héritée, décalée par sa première impression (adoré,
     * bof) ou, à défaut, par la note reprise d'une autre appli (comparée à tes autres notes), ce
     * qui réduit aussi un peu l'incertitude d'un film encore jamais classé.
     */
    private fun prior(row: RankingRow, imported: ImportedScale): RankingPrior {
        val importedRating = row.importedRating
        val (shift, deviation) = when {
            row.impression == Impression.LOVED -> IMPRESSION_SHIFT to IMPRESSION_DEVIATION
            row.impression == Impression.DISLIKED -> -IMPRESSION_SHIFT to IMPRESSION_DEVIATION
            importedRating != null -> imported.shift(importedRating) to IMPORTED_DEVIATION
            else -> 0.0 to null
        }
        return RankingPrior(
            row.tmdbId,
            rating = row.priorRating + shift,
            duels = row.priorDuels,
            current = row.rating,
            deviation = deviation.takeIf { row.priorDuels == 0 },
        )
    }

    private companion object {
        /** En dessous d'un vingtième de point, inutile de réécrire la ligne. */
        const val RATING_EPSILON = 0.05
        const val CONFIDENCE_EPSILON = 0.005

        /** Une première impression vaut environ un demi-écart type de classement. */
        const val IMPRESSION_SHIFT = 180.0
        const val IMPRESSION_DEVIATION = 300.0

        /** Une note reprise renseigne un peu moins qu'une impression donnée ici. */
        const val IMPORTED_DEVIATION = 290.0
    }
}

/** Nombre de duels entre deux films ([lowId] < [highId]). */
data class PairCount(
    val lowId: Long,
    val highId: Long,
    val duels: Int,
)

/** Journal des duels, du plus récent au plus ancien, avec les titres et affiches. */
private const val DUEL_HISTORY_QUERY = """
    SELECT d.id AS id, d.createdAt AS createdAt,
           d.winnerId AS winnerId, w.title AS winnerTitle, w.posterPath AS winnerPoster,
           d.loserId AS loserId, l.title AS loserTitle, l.posterPath AS loserPoster,
           d.winnerRatingBefore AS winnerRatingBefore, d.winnerRatingAfter AS winnerRatingAfter,
           d.loserRatingBefore AS loserRatingBefore, d.loserRatingAfter AS loserRatingAfter,
           d.draw AS draw
    FROM duel AS d
    LEFT JOIN movie AS w ON w.tmdbId = d.winnerId
    LEFT JOIN movie AS l ON l.tmdbId = d.loserId
    ORDER BY d.id DESC
    LIMIT :limit
"""

/** Écritures de la bibliothèque. */
@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    abstract suspend fun userMovie(tmdbId: Long): UserMovieEntity?

    @Upsert
    abstract suspend fun upsertUserMovie(entity: UserMovieEntity)

    @Upsert
    abstract suspend fun upsertMovie(entity: MovieEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMovieIfAbsent(entity: MovieEntity): Long

    @Query("DELETE FROM user_movie WHERE tmdbId = :tmdbId")
    abstract suspend fun deleteUserMovie(tmdbId: Long)

    /** Série : en cours (saison [season]), finie ou abandonnée. */
    @Query("UPDATE user_movie SET progress = :progress, currentSeason = :season, updatedAt = :now WHERE tmdbId = :tmdbId")
    abstract suspend fun setProgress(tmdbId: Long, progress: SeriesProgress?, season: Int?, now: Long)

    /** Marquer vu / retirer des vus. Le classement et les duels sont toujours conservés. */
    @Transaction
    open suspend fun setWatched(tmdbId: Long, watched: Boolean, now: Long) {
        applyStatus(tmdbId, if (watched) WatchStatus.WATCHED else WatchStatus.NONE, now)
    }

    /**
     * Change le statut d'un film sans toucher à son classement ni à ses duels. Renvoie la ligne
     * d'avant (null s'il n'y en avait pas) pour pouvoir annuler exactement avec [restore].
     */
    @Transaction
    open suspend fun applyStatus(tmdbId: Long, status: WatchStatus, now: Long): UserMovieEntity? {
        val previous = userMovie(tmdbId)
        val current = previous ?: UserMovieEntity(tmdbId = tmdbId, updatedAt = now)
        val updated = if (status == WatchStatus.WATCHED) {
            current.copy(
                status = WatchStatus.WATCHED,
                watchedAt = current.watchedAt ?: now,
                watchedSource = current.watchedSource ?: WatchSource.MANUAL,
                updatedAt = now,
            )
        } else {
            current.copy(status = status, updatedAt = now)
        }
        upsertUserMovie(updated)
        return previous
    }

    /** Action sur une carte : la fiche est d'abord mise en cache si elle n'y est pas encore. */
    @Transaction
    open suspend fun applyCardAction(movie: MovieEntity, status: WatchStatus, now: Long): UserMovieEntity? {
        insertMovieIfAbsent(movie)
        return applyStatus(movie.tmdbId, status, now)
    }

    /** Annulation : remet la ligne telle qu'elle était, ou la supprime si elle n'existait pas. */
    @Transaction
    open suspend fun restore(tmdbId: Long, previous: UserMovieEntity?) {
        if (previous == null) deleteUserMovie(tmdbId) else upsertUserMovie(previous)
    }

    @Query("DELETE FROM duel")
    abstract suspend fun deleteAllDuels()

    @Query(
        """
        UPDATE user_movie SET rating = 1500.0, duels = 0, deviation = 350.0, confidence = 0.0,
            priorRating = 1500.0, priorDuels = 0
        """,
    )
    abstract suspend fun resetRatings()

    @Query("DELETE FROM user_movie")
    abstract suspend fun deleteAllUserMovies()

    @Query("DELETE FROM outbox")
    abstract suspend fun deleteOutbox()

    @Query("DELETE FROM reco_source")
    abstract suspend fun deleteRecoSources()

    /** Classement remis à zéro : films vus gardés, duels et notes héritées effacés. */
    @Transaction
    open suspend fun resetRanking() {
        deleteAllDuels()
        resetRatings()
    }

    /**
     * Tout effacer : films vus, listes, réponses du recensement, classement, demandes en attente
     * et bilan des sources de recos (le cache des fiches et le catalogue du recensement restent).
     */
    @Transaction
    open suspend fun eraseAll() {
        deleteAllDuels()
        deleteAllUserMovies()
        deleteOutbox()
        deleteRecoSources()
    }

    /** Mémoire du feed : le film vient d'être montré. */
    @Transaction
    open suspend fun markShown(tmdbId: Long, now: Long) {
        val current = userMovie(tmdbId)
        val updated = current?.copy(shownCount = current.shownCount + 1, lastShownAt = now)
            ?: UserMovieEntity(tmdbId = tmdbId, shownCount = 1, lastShownAt = now, updatedAt = now)
        upsertUserMovie(updated)
    }
}

/** File d'attente des demandes envoyées à Seerr ou Radarr. */
@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Update
    suspend fun update(entry: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY id")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'FAILED' ORDER BY id")
    suspend fun failed(): List<OutboxEntity>

    /** Films dont la demande n'est pas encore partie : ils restent « demandés » pendant une synchro. */
    @Query("SELECT DISTINCT tmdbId FROM outbox WHERE state = 'PENDING' AND type = 'REQUEST'")
    suspend fun pendingRequestIds(): List<Long>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE state != 'PENDING' AND updatedAt < :before")
    suspend fun purgeFinished(before: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM outbox WHERE state = 'FAILED' ORDER BY id DESC")
    fun observeFailed(): Flow<List<OutboxEntity>>
}

/**
 * Films connus du homelab dont la fiche est partielle (titre et année du serveur) ou absente
 * (Seerr ne donne que l'identifiant TMDB) : à compléter depuis TMDB, les films vus d'abord.
 */
private const val MOVIES_TO_ENRICH_QUERY = """
    SELECT u.tmdbId FROM user_movie AS u
    LEFT JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE (m.tmdbId IS NULL OR (m.fetchedAt IS NULL AND m.posterPath IS NULL))
      AND (u.status != 'NONE' OR u.availability != 'NONE')
    ORDER BY u.status = 'WATCHED' DESC, u.status = 'WATCHLIST' DESC, u.tmdbId
    LIMIT :limit
"""

/** Films disponibles sur le serveur, ni vus ni écartés (Découverte « dispo »), les mieux notés d'abord. */
private const val AVAILABLE_MOVIES_QUERY = """
    SELECT m.* FROM user_movie AS u
    INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.availability = 'AVAILABLE'
      AND u.status NOT IN ('WATCHED', 'DISMISSED')
      AND (:yearMin IS NULL OR m.year >= :yearMin)
      AND (:yearMax IS NULL OR m.year <= :yearMax)
    ORDER BY m.tmdbRating IS NULL, m.tmdbRating DESC, m.title
"""

/** Écritures de la synchronisation avec le homelab. */
@Dao
abstract class HomelabDao {
    @Query("SELECT * FROM user_movie")
    abstract suspend fun allUserMovies(): List<UserMovieEntity>

    @Query("SELECT tmdbId FROM movie")
    abstract suspend fun movieIds(): List<Long>

    @Upsert
    abstract suspend fun upsertUserMovies(rows: List<UserMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMoviesIfAbsent(movies: List<MovieEntity>)

    @Query(MOVIES_TO_ENRICH_QUERY)
    abstract suspend fun moviesToEnrich(limit: Int): List<Long>

    @Query(AVAILABLE_MOVIES_QUERY)
    abstract suspend fun availableMovies(yearMin: Int?, yearMax: Int?): List<MovieEntity>

    /**
     * Applique une synchro en une transaction ; les films inconnus reçoivent une fiche partielle
     * (titre et année du serveur), complétée ensuite depuis TMDB. Renvoie les films ajoutés aux vus.
     */
    @Transaction
    open suspend fun applySync(snapshot: HomelabSnapshot, now: Long): List<FreshWatch> {
        val changes = SyncMerger.merge(allUserMovies(), snapshot, now)
        val known = movieIds().toHashSet()
        val missing = changes.userMovies.mapNotNull { row ->
            if (row.tmdbId in known) return@mapNotNull null
            snapshot.titles[row.tmdbId]?.let { MovieEntity(tmdbId = row.tmdbId, title = it.title.ifBlank { "?" }, year = it.year) }
        }
        insertMoviesIfAbsent(missing)
        upsertUserMovies(changes.userMovies)
        return changes.fresh
    }

    /** Demande faite depuis une carte : le film passe dans la liste À voir et devient « demandé ». */
    @Transaction
    open suspend fun applyRequest(movie: MovieEntity, now: Long): UserMovieEntity? {
        insertMovieIfAbsent(movie)
        val previous = userMovie(movie.tmdbId)
        val current = previous ?: UserMovieEntity(tmdbId = movie.tmdbId, updatedAt = now)
        upsertUserMovie(
            current.copy(
                status = if (current.status == WatchStatus.WATCHED) WatchStatus.WATCHED else WatchStatus.WATCHLIST,
                availability = if (current.availability == Availability.AVAILABLE) Availability.AVAILABLE else Availability.REQUESTED,
                availabilityUpdatedAt = now,
                updatedAt = now,
            ),
        )
        return previous
    }

    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    abstract suspend fun userMovie(tmdbId: Long): UserMovieEntity?

    @Upsert
    abstract suspend fun upsertUserMovie(entity: UserMovieEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMovieIfAbsent(movie: MovieEntity): Long
}

/** Film qui renseigne sur les goûts (vu, à voir ou écarté), avec ce qui le décrit. */
data class TasteRow(
    val tmdbId: Long,
    val title: String,
    val status: WatchStatus,
    val rating: Double,
    val watchedAt: Long?,
    val updatedAt: Long,
    val genreIds: List<Int>,
    val keywords: List<TmdbTag>,
    val directors: List<TmdbTag>,
    val cast: List<CastMember>,
    val year: Int?,
    val originalLanguage: String?,
)

private const val TASTE_ROWS_QUERY = """
    SELECT u.tmdbId AS tmdbId, m.title AS title, u.status AS status, u.rating AS rating,
           u.watchedAt AS watchedAt, u.updatedAt AS updatedAt,
           m.genreIds AS genreIds, m.keywords AS keywords, m.directors AS directors, m.`cast` AS `cast`,
           m.year AS year, m.originalLanguage AS originalLanguage
    FROM user_movie AS u
    INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.status IN ('WATCHED', 'WATCHLIST', 'DISMISSED')
"""

/**
 * Liens TMDB vers des films encore proposables : jamais vus ni listés, pas montrés récemment
 * (même délai croissant que les recos), et absents du serveur quand le homelab est actif.
 */
private const val OPEN_LINKS_QUERY = """
    SELECT l.seedId AS seedId, l.movieId AS movieId, l.position AS position, l.kind AS kind
    FROM reco_link AS l
    LEFT JOIN user_movie AS u ON u.tmdbId = l.movieId
    WHERE u.tmdbId IS NULL
       OR (u.status = 'NONE'
           AND NOT (u.lastShownAt IS NOT NULL AND u.lastShownAt + :cooldown * (1 << MIN(MAX(u.shownCount, 1) - 1, 4)) > :now)
           AND NOT (:excludeAvailable AND u.availability != 'NONE'))
"""

/** Films du profil de goût dont la fiche n'a pas encore les mots-clés et les personnes (version [version]). */
private const val TASTE_TO_ENRICH_QUERY = """
    SELECT u.tmdbId FROM user_movie AS u
    LEFT JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.status IN ('WATCHED', 'WATCHLIST', 'DISMISSED')
      AND (m.tmdbId IS NULL OR m.detailsVersion < :version)
    ORDER BY u.status = 'WATCHED' DESC, ABS(u.rating - 1500.0) DESC, u.updatedAt DESC
    LIMIT :limit
"""

/** Film à portée de main pour « Ce soir » : dans la liste À voir ou disponible sur le serveur. */
data class TonightRow(
    @Embedded val movie: MovieEntity,
    val status: WatchStatus,
    val availability: Availability,
)

/** Série en cours (Ce soir, « À reprendre ») : sa saison et la date du dernier changement. */
data class ResumeRow(
    @Embedded val movie: MovieEntity,
    val availability: Availability,
    val currentSeason: Int?,
    val updatedAt: Long,
)

/**
 * Films ni vus ni écartés, à voir ou disponibles sur le serveur (quand le homelab est actif).
 * Une fiche sans affiche (tout juste venue du serveur) attend d'être complétée.
 */
private const val TONIGHT_QUERY = """
    SELECT m.*, u.status AS status, u.availability AS availability
    FROM user_movie AS u
    INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
    WHERE u.status IN ('NONE', 'WATCHLIST')
      AND (u.status = 'WATCHLIST' OR (:withServer AND u.availability = 'AVAILABLE'))
      AND m.posterPath IS NOT NULL
"""

/** Moteur de recos : profil de goût, liens TMDB entre films et bilan des sources. */
@Dao
abstract class RecoDao {
    @Query(TASTE_ROWS_QUERY)
    abstract suspend fun tasteRows(): List<TasteRow>

    @Query(OPEN_LINKS_QUERY)
    abstract suspend fun openLinks(cooldown: Long, now: Long, excludeAvailable: Boolean): List<RecoLinkEntity>

    @Query("SELECT * FROM reco_seed")
    abstract suspend fun seeds(): List<RecoSeedEntity>

    @Query("SELECT * FROM movie WHERE tmdbId IN (:ids)")
    abstract suspend fun movies(ids: List<Long>): List<MovieEntity>

    @Query(TASTE_TO_ENRICH_QUERY)
    abstract suspend fun tasteToEnrich(version: Int, limit: Int): List<Long>

    @Query("SELECT * FROM reco_source")
    abstract suspend fun sources(): List<RecoSourceEntity>

    @Query(TONIGHT_QUERY)
    abstract suspend fun tonight(withServer: Boolean): List<TonightRow>

    /** Séries suivies pour les nouvelles saisons : vues (finies ou en cours), toujours diffusées ou sans statut connu. */
    @Query(
        """
        SELECT m.* FROM user_movie AS u
        INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
        WHERE u.status = 'WATCHED' AND (u.progress IS NULL OR u.progress IN ('FINISHED', 'IN_PROGRESS'))
          AND (m.airStatus IS NULL OR m.airStatus NOT IN ('Ended', 'Canceled'))
        """,
    )
    abstract suspend fun followedShows(): List<MovieEntity>

    /** Séries en cours, de la plus récemment touchée à la plus ancienne. */
    @Query(
        """
        SELECT m.*, u.availability AS availability, u.currentSeason AS currentSeason, u.updatedAt AS updatedAt
        FROM user_movie AS u
        INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
        WHERE u.status = 'WATCHED' AND u.progress = 'IN_PROGRESS' AND m.posterPath IS NOT NULL
        ORDER BY u.updatedAt DESC
        """,
    )
    abstract suspend fun inProgress(): List<ResumeRow>

    /** Liens TMDB qui mènent à ces films (pour le score collaboratif de « Ce soir »). */
    @Query("SELECT * FROM reco_link WHERE movieId IN (:ids)")
    abstract suspend fun linksTo(ids: List<Long>): List<RecoLinkEntity>

    @Query("SELECT * FROM reco_source WHERE source = :source")
    abstract suspend fun source(source: RecoSource): RecoSourceEntity?

    @Upsert
    abstract suspend fun upsertSource(entity: RecoSourceEntity)

    @Query("DELETE FROM reco_link WHERE seedId = :seedId")
    abstract suspend fun deleteLinks(seedId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLinks(links: List<RecoLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMoviesIfAbsent(movies: List<MovieEntity>)

    @Upsert
    abstract suspend fun upsertSeed(seed: RecoSeedEntity)

    /** Remplace les liens d'une graine ; les films liés reçoivent une fiche partielle s'ils sont inconnus. */
    @Transaction
    open suspend fun saveLinks(seedId: Long, links: List<RecoLinkEntity>, movies: List<MovieEntity>, now: Long) {
        insertMoviesIfAbsent(movies)
        deleteLinks(seedId)
        insertLinks(links)
        upsertSeed(RecoSeedEntity(seedId = seedId, fetchedAt = now))
    }

    /**
     * Ajoute au bilan d'une source (valeurs négatives : annulation). Au-delà de 200 cartes
     * montrées, le bilan est divisé par deux : les goûts récents comptent plus.
     */
    @Transaction
    open suspend fun recordSource(
        source: RecoSource,
        shown: Double,
        liked: Double,
        disliked: Double,
        now: Long,
    ) {
        val current = source(source) ?: RecoSourceEntity(source = source)
        var updated = current.copy(
            shown = (current.shown + shown).coerceAtLeast(0.0),
            liked = (current.liked + liked).coerceAtLeast(0.0),
            disliked = (current.disliked + disliked).coerceAtLeast(0.0),
            updatedAt = now,
        )
        if (updated.shown > SOURCE_WINDOW) {
            updated = updated.copy(shown = updated.shown / 2, liked = updated.liked / 2, disliked = updated.disliked / 2)
        }
        upsertSource(updated)
    }

    private companion object {
        const val SOURCE_WINDOW = 200.0
    }
}

/** Film candidat au recensement, avec ce qui aide à deviner s'il a été vu. */
data class CensusCandidateRow(
    val tmdbId: Long,
    val voteCount: Int?,
    val tmdbRating: Double?,
    val year: Int?,
    val genreIds: List<Int>,
    val originalLanguage: String?,
    val boost: Boolean,
)

/** Nombre de films vus qui recommandent un film. */
data class GraphLinkCount(
    val movieId: Long,
    val links: Int,
)

/** De quoi annuler une page du recensement : les lignes d'avant, et celles qui n'existaient pas. */
data class CensusUndo(
    val previous: List<UserMovieEntity>,
    val created: List<Long>,
)

/** Films du catalogue pas encore recensés (ni vus, ni listés, ni répondus), avec une affiche. */
private const val CENSUS_OPEN = """
    FROM census_pool AS p
    INNER JOIN movie AS m ON m.tmdbId = p.tmdbId
    LEFT JOIN user_movie AS u ON u.tmdbId = p.tmdbId
    WHERE (u.tmdbId IS NULL OR (u.status = 'NONE' AND u.censusAt IS NULL))
      AND m.posterPath IS NOT NULL
      AND (:yearMin IS NULL OR m.year >= :yearMin)
      AND (:yearMax IS NULL OR m.year <= :yearMax)
      AND (:language IS NULL OR m.originalLanguage = :language)
"""

/** Réponses au recensement, et parmi elles les films vus. */
data class CensusAnswerCounts(
    val answered: Int,
    val seen: Int,
)

/** Recensement : catalogue à parcourir, réponses et progression. */
@Dao
abstract class CensusDao {
    @Query(
        """
        SELECT p.tmdbId AS tmdbId, m.voteCount AS voteCount, m.tmdbRating AS tmdbRating, m.year AS year,
               m.genreIds AS genreIds, m.originalLanguage AS originalLanguage, p.boost AS boost
        $CENSUS_OPEN
        """,
    )
    abstract suspend fun candidates(yearMin: Int?, yearMax: Int?, language: String?): List<CensusCandidateRow>

    @Query("SELECT COUNT(*) $CENSUS_OPEN")
    abstract suspend fun candidateCount(yearMin: Int?, yearMax: Int?, language: String?): Int

    @Query(
        """
        SELECT l.movieId AS movieId, COUNT(*) AS links FROM reco_link AS l
        INNER JOIN user_movie AS u ON u.tmdbId = l.seedId
        WHERE u.status = 'WATCHED'
        GROUP BY l.movieId
        """,
    )
    abstract suspend fun graphLinks(): List<GraphLinkCount>

    @Query("SELECT * FROM movie WHERE tmdbId IN (:ids)")
    abstract suspend fun movies(ids: List<Long>): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMoviesIfAbsent(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPool(entries: List<CensusPoolEntity>)

    @Query("UPDATE census_pool SET boost = 1 WHERE tmdbId IN (:ids)")
    abstract suspend fun boost(ids: List<Long>)

    /** Ajoute des films au catalogue (fiche partielle s'ils sont inconnus). */
    @Transaction
    open suspend fun addToPool(movies: List<MovieEntity>, source: CensusSource, boost: Boolean, now: Long) {
        if (movies.isEmpty()) return
        insertMoviesIfAbsent(movies)
        insertPool(movies.map { CensusPoolEntity(tmdbId = it.tmdbId, source = source, boost = boost, addedAt = now) })
        if (boost) boost(movies.map { it.tmdbId })
    }

    @Query("SELECT * FROM user_movie WHERE tmdbId IN (:ids)")
    abstract suspend fun userMovies(ids: List<Long>): List<UserMovieEntity>

    @Upsert
    abstract suspend fun upsertUserMovies(rows: List<UserMovieEntity>)

    @Query("DELETE FROM user_movie WHERE tmdbId IN (:ids)")
    abstract suspend fun deleteUserMovies(ids: List<Long>)

    /**
     * Enregistre des réponses : vu (avec la première impression), à voir, pas pour moi, ou pas vu
     * (seule la date de réponse est notée : rien ne change pour les recos). Renvoie de quoi annuler.
     */
    @Transaction
    open suspend fun answer(answers: Map<Long, CensusChoice>, now: Long): CensusUndo {
        if (answers.isEmpty()) return CensusUndo(emptyList(), emptyList())
        val existing = userMovies(answers.keys.toList()).associateBy { it.tmdbId }
        val updated = answers.map { (id, choice) ->
            val current = existing[id] ?: UserMovieEntity(tmdbId = id, updatedAt = now)
            when {
                choice.seen -> current.copy(
                    status = WatchStatus.WATCHED,
                    watchedAt = current.watchedAt ?: now,
                    watchedSource = current.watchedSource ?: WatchSource.MANUAL,
                    impression = when (choice) {
                        CensusChoice.LOVED -> Impression.LOVED
                        CensusChoice.DISLIKED -> Impression.DISLIKED
                        else -> current.impression
                    },
                    progress = when (choice) {
                        CensusChoice.FINISHED -> SeriesProgress.FINISHED
                        CensusChoice.IN_PROGRESS -> SeriesProgress.IN_PROGRESS
                        CensusChoice.EPISODES -> SeriesProgress.EPISODES
                        CensusChoice.ABANDONED -> SeriesProgress.ABANDONED
                        else -> current.progress
                    },
                    // Une saison n'a de sens qu'en cours ou décrochée (inconnue au recensement).
                    currentSeason = current.currentSeason.takeIf { choice == CensusChoice.IN_PROGRESS || choice == CensusChoice.ABANDONED },
                    censusAt = now,
                    updatedAt = now,
                )
                choice == CensusChoice.WATCHLIST -> current.copy(status = WatchStatus.WATCHLIST, censusAt = now, updatedAt = now)
                choice == CensusChoice.DISMISSED -> current.copy(status = WatchStatus.DISMISSED, censusAt = now, updatedAt = now)
                else -> current.copy(censusAt = now, updatedAt = now)
            }
        }
        upsertUserMovies(updated)
        return CensusUndo(previous = existing.values.toList(), created = answers.keys.filter { it !in existing })
    }

    /** Annule des réponses : les lignes reviennent à l'identique, celles créées disparaissent. */
    @Transaction
    open suspend fun restore(undo: CensusUndo) {
        if (undo.created.isNotEmpty()) deleteUserMovies(undo.created)
        if (undo.previous.isNotEmpty()) upsertUserMovies(undo.previous)
    }

    /** Films passés en revue : répondus au recensement, ou déjà vus, listés ou écartés. */
    @Query("SELECT COUNT(*) FROM user_movie WHERE censusAt IS NOT NULL OR status != 'NONE'")
    abstract fun observeAnswered(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_movie WHERE status = 'WATCHED'")
    abstract fun observeWatched(): Flow<Int>

    /** Réponses données au recensement (vu, pas vu, à voir…). */
    @Query("SELECT COUNT(*) FROM user_movie WHERE censusAt IS NOT NULL")
    abstract fun observeCensusAnswered(): Flow<Int>

    @Query("SELECT COUNT(*) FROM census_pool")
    abstract suspend fun poolSize(): Int

    @Query(
        """
        SELECT COUNT(*) AS answered, COALESCE(SUM(CASE WHEN status = 'WATCHED' THEN 1 ELSE 0 END), 0) AS seen
        FROM user_movie WHERE censusAt IS NOT NULL
        """,
    )
    abstract suspend fun answerCounts(): CensusAnswerCounts

    /** Sagas des films vus. */
    @Query(
        """
        SELECT DISTINCT m.collectionId FROM movie AS m
        INNER JOIN user_movie AS u ON u.tmdbId = m.tmdbId
        WHERE u.status = 'WATCHED' AND m.collectionId IS NOT NULL
        """,
    )
    abstract suspend fun watchedCollections(): List<Long>
}

/** Écritures de l'import (autres applis, sauvegarde CineSwipe). */
@Dao
abstract class ImportDao {
    @Query("SELECT * FROM user_movie WHERE tmdbId = :tmdbId")
    abstract suspend fun userMovie(tmdbId: Long): UserMovieEntity?

    @Query("SELECT * FROM movie WHERE tmdbId = :tmdbId")
    abstract suspend fun movie(tmdbId: Long): MovieEntity?

    /** Vrai s'il y a déjà des films vus, à voir ou écartés, ou des duels. */
    @Query("SELECT EXISTS(SELECT 1 FROM user_movie WHERE status != 'NONE') OR EXISTS(SELECT 1 FROM duel)")
    abstract suspend fun hasUserData(): Boolean

    @Upsert
    abstract suspend fun upsertUserMovies(rows: List<UserMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMoviesIfAbsent(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDuels(duels: List<DuelEntity>)

    @Upsert
    abstract suspend fun upsertSources(sources: List<RecoSourceEntity>)

    /** Fusionne les films importés (voir [ImportMerger]) en une transaction. */
    @Transaction
    open suspend fun applyImport(entries: List<ImportEntry>, now: Long): ImportCounts {
        insertMoviesIfAbsent(entries.map { it.movie })
        val rows = ArrayList<UserMovieEntity>()
        var counts = ImportCounts()
        entries.forEach { entry ->
            val (row, change) = ImportMerger.merge(userMovie(entry.tmdbId), entry, now)
            row?.let { rows += it }
            counts = when (change) {
                ImportChange.WATCHED -> counts.copy(watched = counts.watched + 1, rated = counts.rated + if (entry.rating10 != null) 1 else 0)
                ImportChange.WATCHLIST -> counts.copy(watchlist = counts.watchlist + 1)
                ImportChange.RATED -> counts.copy(rated = counts.rated + 1)
                ImportChange.NONE -> counts.copy(unchanged = counts.unchanged + 1)
            }
        }
        upsertUserMovies(rows)
        return counts
    }

    /**
     * Restauration complète d'une sauvegarde CineSwipe (films, réponses, duels, bilan des recos),
     * seulement si la bibliothèque est vide. Renvoie faux sinon : la sauvegarde est alors fusionnée.
     */
    @Transaction
    open suspend fun restore(backup: BackupRows): Boolean {
        if (hasUserData()) return false
        insertMoviesIfAbsent(backup.movies)
        upsertUserMovies(backup.userMovies)
        insertDuels(backup.duels)
        upsertSources(backup.sources)
        return true
    }
}

/** Lectures des outils de diagnostic et de l'export. */
@Dao
interface DiagnosticsDao {
    @Query(
        """
        SELECT u.tmdbId AS tmdbId, m.title AS title, u.rating AS rating, u.deviation AS deviation,
               u.duels AS duels, u.confidence AS confidence
        FROM user_movie AS u
        INNER JOIN movie AS m ON m.tmdbId = u.tmdbId
        WHERE u.status = 'WATCHED'
        """,
    )
    suspend fun rankedFilms(): List<FilmDiagnostic>

    @Query("SELECT COUNT(*) FROM duel")
    suspend fun duelCount(): Int

    /** Date de chaque duel joué. */
    @Query("SELECT createdAt FROM duel")
    suspend fun duelTimes(): List<Long>

    @Query("SELECT COUNT(*) FROM duel WHERE draw = 1")
    suspend fun drawCount(): Int

    /** Duels gagnés par l'un des deux films, tous deux encore vus. */
    @Query("SELECT COUNT(*) $WATCHED_DUELS WHERE d.draw = 0")
    suspend fun decisiveDuels(): Int

    /** Duels gagnés par le film aujourd'hui le mieux classé des deux. */
    @Query("SELECT COUNT(*) $WATCHED_DUELS WHERE d.draw = 0 AND w.rating > l.rating")
    suspend fun consistentDuels(): Int

    @Query("SELECT COALESCE(SUM(priorDuels), 0) FROM user_movie")
    suspend fun inheritedDuels(): Int

    @Query("SELECT COUNT(*) FROM reco_seed")
    suspend fun seedCount(): Int

    @Query("SELECT COUNT(*) FROM reco_link")
    suspend fun linkCount(): Int

    @Query("SELECT COUNT(*) FROM movie")
    suspend fun movieCount(): Int

    @Query("SELECT * FROM user_movie")
    suspend fun userMovies(): List<UserMovieEntity>

    @Query("SELECT * FROM duel ORDER BY id")
    suspend fun duels(): List<DuelEntity>

    @Query(
        """
        SELECT * FROM movie
        WHERE posterPath IS NOT NULL
          AND (voteCount >= :minVotes OR tmdbId IN (SELECT tmdbId FROM user_movie WHERE status != 'NONE'))
        """,
    )
    suspend fun exportedMovies(minVotes: Int): List<MovieEntity>
}

/** Une liste perso et son nombre de films. */
data class UserListSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val count: Int,
)

/** Affiche d'un film d'une liste, pour sa couverture. */
data class ListCover(val listId: Long, val posterPath: String)

/** Film d'une liste, avec ce que l'utilisateur en a fait. */
data class ListFilmRow(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val runtimeMinutes: Int?,
    val status: WatchStatus?,
    val addedAt: Long,
)

/** Listes perso : création, films ajoutés ou retirés, export et reprise d'une sauvegarde. */
@Dao
abstract class ListDao {
    /** Les listes, la dernière modifiée d'abord. */
    @Query(
        """
        SELECT l.id AS id, l.name AS name, l.updatedAt AS updatedAt, COUNT(i.tmdbId) AS count
        FROM user_list AS l
        LEFT JOIN user_list_item AS i ON i.listId = l.id
        GROUP BY l.id
        ORDER BY l.updatedAt DESC, l.id DESC
        """,
    )
    abstract fun observeLists(): Flow<List<UserListSummary>>

    /** Affiches des films des listes, les derniers ajoutés d'abord. */
    @Query(
        """
        SELECT i.listId AS listId, m.posterPath AS posterPath
        FROM user_list_item AS i
        INNER JOIN movie AS m ON m.tmdbId = i.tmdbId
        WHERE m.posterPath IS NOT NULL
        ORDER BY i.addedAt DESC
        """,
    )
    abstract fun observeCovers(): Flow<List<ListCover>>

    /** Les listes qui contiennent ce film. */
    @Query("SELECT listId FROM user_list_item WHERE tmdbId = :tmdbId")
    abstract fun observeListsOf(tmdbId: Long): Flow<List<Long>>

    @Query("SELECT * FROM user_list WHERE id = :id")
    abstract fun observeList(id: Long): Flow<UserListEntity?>

    /** Films d'une liste, les derniers ajoutés d'abord. */
    @Query(
        """
        SELECT m.tmdbId AS tmdbId, m.title AS title, m.year AS year, m.posterPath AS posterPath,
               m.runtimeMinutes AS runtimeMinutes, u.status AS status, i.addedAt AS addedAt
        FROM user_list_item AS i
        INNER JOIN movie AS m ON m.tmdbId = i.tmdbId
        LEFT JOIN user_movie AS u ON u.tmdbId = i.tmdbId
        WHERE i.listId = :listId
        ORDER BY i.addedAt DESC, m.title
        """,
    )
    abstract fun observeItems(listId: Long): Flow<List<ListFilmRow>>

    @Insert
    abstract suspend fun insertList(list: UserListEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItems(items: List<UserListItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMoviesIfAbsent(movies: List<MovieEntity>)

    @Query("UPDATE user_list SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE user_list SET updatedAt = :now WHERE id = :id")
    protected abstract suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM user_list WHERE id = :id")
    protected abstract suspend fun deleteListRow(id: Long)

    @Query("DELETE FROM user_list_item WHERE listId = :id")
    protected abstract suspend fun clearList(id: Long)

    @Query("DELETE FROM user_list_item WHERE listId = :listId AND tmdbId = :tmdbId")
    protected abstract suspend fun removeItem(listId: Long, tmdbId: Long)

    @Query("SELECT * FROM user_list WHERE name = :name COLLATE NOCASE ORDER BY id LIMIT 1")
    abstract suspend fun byName(name: String): UserListEntity?

    /** Nouvelle liste, avec un premier film si elle est créée depuis sa fiche. */
    @Transaction
    open suspend fun create(name: String, now: Long, firstFilm: Long? = null): Long {
        val id = insertList(UserListEntity(name = name, createdAt = now, updatedAt = now))
        if (firstFilm != null) insertItems(listOf(UserListItemEntity(id, firstFilm, now)))
        return id
    }

    @Transaction
    open suspend fun add(listId: Long, tmdbId: Long, now: Long) {
        insertItems(listOf(UserListItemEntity(listId, tmdbId, now)))
        touch(listId, now)
    }

    @Transaction
    open suspend fun remove(listId: Long, tmdbId: Long, now: Long) {
        removeItem(listId, tmdbId)
        touch(listId, now)
    }

    @Transaction
    open suspend fun delete(id: Long) {
        clearList(id)
        deleteListRow(id)
    }

    @Query("SELECT * FROM user_list ORDER BY id")
    abstract suspend fun lists(): List<UserListEntity>

    @Query("SELECT * FROM user_list_item ORDER BY listId, addedAt")
    abstract suspend fun items(): List<UserListItemEntity>

    /**
     * Listes d'une sauvegarde : réunies par nom avec celles qui existent déjà, films ajoutés sans
     * doublon (fiches minimales si besoin). Renvoie le nombre de listes reprises.
     */
    @Transaction
    open suspend fun mergeImported(lists: List<ImportedList>, movies: List<MovieEntity>, now: Long): Int {
        insertMoviesIfAbsent(movies)
        lists.forEach { list ->
            val id = byName(list.name)?.id ?: insertList(UserListEntity(name = list.name, createdAt = list.createdAt, updatedAt = now))
            insertItems(list.items.map { UserListItemEntity(id, it.tmdbId, it.addedAt) })
        }
        return lists.size
    }
}

/** Arène des saisons : leurs notes et leur journal de duels. */
@Dao
abstract class SeasonArenaDao {
    @Query("SELECT * FROM season_rank")
    abstract suspend fun ranks(): List<SeasonRankEntity>

    @Query("SELECT * FROM season_rank")
    abstract fun observeRanks(): Flow<List<SeasonRankEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRanks(ranks: List<SeasonRankEntity>)

    @Query("UPDATE season_rank SET rating = :rating, deviation = :deviation, confidence = :confidence, duels = :duels, updatedAt = :now WHERE `key` = :key")
    abstract suspend fun updateRank(key: Long, rating: Double, deviation: Double, confidence: Double, duels: Int, now: Long)

    @Query("SELECT * FROM season_duel ORDER BY id")
    abstract suspend fun duels(): List<SeasonDuelEntity>

    @Insert
    abstract suspend fun insertDuel(duel: SeasonDuelEntity): Long

    @Query("DELETE FROM season_duel WHERE id = :id")
    abstract suspend fun deleteDuel(id: Long)

    @Query("SELECT COUNT(*) FROM season_duel")
    abstract fun observeDuelCount(): Flow<Int>
}
