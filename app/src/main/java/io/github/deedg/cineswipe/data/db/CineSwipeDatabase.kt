package io.github.deedg.cineswipe.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.deedg.cineswipe.data.model.MediaKind

/**
 * - Version 2 (étape 3) : file d'attente `outbox` des demandes au homelab.
 * - Version 3 : classement Bradley-Terry (incertitude, confiance, note de départ), égalités dans
 *   les duels, profil de goût des fiches (mots-clés, réalisateurs…) et tables du moteur de recos.
 * - Version 4 : recensement (réponses « pas vu », premières impressions, catalogue à parcourir,
 *   sagas des films).
 * - Version 5 : notes reprises d'autres applis (import Letterboxd, IMDb, Trakt…).
 * - Version 6 : listes perso.
 * - Version 7 : séries (saisons, statut de diffusion, saison en cours, série finie ou abandonnée).
 *   Les séries ont leur propre fichier ([SERIES_NAME]) avec le même schéma.
 * - Version 8 : saisons demandées d'une série (file d'attente des demandes).
 *
 * Migrations automatiques grâce aux schémas versionnés dans `app/schemas`.
 */
@Database(
    entities = [
        MovieEntity::class,
        UserMovieEntity::class,
        DuelEntity::class,
        OutboxEntity::class,
        RecoLinkEntity::class,
        RecoSeedEntity::class,
        RecoSourceEntity::class,
        CensusPoolEntity::class,
        UserListEntity::class,
        UserListItemEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = CineSwipeDatabase.Migration2To3::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
)
@TypeConverters(Converters::class)
abstract class CineSwipeDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao
    abstract fun userMovieDao(): UserMovieDao
    abstract fun arenaDao(): ArenaDao
    abstract fun libraryDao(): LibraryDao
    abstract fun outboxDao(): OutboxDao
    abstract fun homelabDao(): HomelabDao
    abstract fun recoDao(): RecoDao
    abstract fun censusDao(): CensusDao
    abstract fun diagnosticsDao(): DiagnosticsDao
    abstract fun importDao(): ImportDao
    abstract fun listDao(): ListDao

    /**
     * Passage au classement recalculé : la note de chaque film avant son premier duel du journal
     * devient sa note de départ, et les duels comptés sans journal (anciennes versions) restent
     * comptés dans cette note de départ. Les incertitudes sont calculées au démarrage suivant.
     */
    class Migration2To3 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL(PRIORS_FROM_ELO)
        }
    }

    companion object {
        private const val NAME = "cineswipe.db"

        /** Base du monde des séries : même schéma, fichier à part (les identifiants TMDB des séries et des films se chevauchent). */
        const val SERIES_NAME = "cineswipe-series.db"

        internal const val PRIORS_FROM_ELO = """
            UPDATE user_movie SET
                priorRating = COALESCE(
                    (SELECT CASE WHEN d.winnerId = user_movie.tmdbId THEN d.winnerRatingBefore ELSE d.loserRatingBefore END
                     FROM duel AS d
                     WHERE d.winnerId = user_movie.tmdbId OR d.loserId = user_movie.tmdbId
                     ORDER BY d.id
                     LIMIT 1),
                    rating),
                priorDuels = MAX(0, duels - (SELECT COUNT(*) FROM duel AS d WHERE d.winnerId = user_movie.tmdbId OR d.loserId = user_movie.tmdbId))
        """

        fun create(context: Context, kind: MediaKind = MediaKind.FILMS): CineSwipeDatabase =
            Room.databaseBuilder(context.applicationContext, CineSwipeDatabase::class.java, fileName(kind))
                .build()

        fun fileName(kind: MediaKind): String = if (kind == MediaKind.SERIES) SERIES_NAME else NAME

        /** Base de la démo : en mémoire, elle disparaît en quittant la démo. */
        fun createInMemory(context: Context): CineSwipeDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CineSwipeDatabase::class.java)
                .build()
    }
}
