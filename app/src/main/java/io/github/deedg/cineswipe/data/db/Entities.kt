package io.github.deedg.cineswipe.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.domain.Ranking
import io.github.deedg.cineswipe.domain.census.CensusSource
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.serialization.Serializable

/** Ce que l'utilisateur a fait d'un film. */
enum class WatchStatus { NONE, WATCHED, WATCHLIST, DISMISSED }

/** D'où vient l'information « vu ». */
enum class WatchSource { MANUAL, IMPORT, JELLYFIN, PLEX }

/** Présence du film sur le serveur homelab. */
enum class Availability { NONE, REQUESTED, AVAILABLE }

/**
 * Où en est une série vue ([WatchStatus.WATCHED]) : en cours (saison [UserMovieEntity.currentSeason]),
 * finie ou abandonnée. Null pour un film.
 */
enum class SeriesProgress {
    IN_PROGRESS,
    FINISHED,

    /** Vue par épisodes, au hasard (souvent à la télé), sans ordre ni saison. */
    EPISODES,
    ABANDONED,
}

/** Une saison d'une série (fiche série). */
@Serializable
data class TvSeason(
    val number: Int,
    val name: String? = null,
    val episodeCount: Int? = null,
    val year: Int? = null,
)

/** Première impression donnée en marquant un film vu (recensement) : point de départ de l'Arène. */
enum class Impression { LOVED, DISLIKED }

@Serializable
data class CastMember(
    val name: String,
    val profilePath: String? = null,
    /** Identifiant TMDB de la personne (absent des fiches chargées avant la 0.1.12). */
    val id: Long? = null,
)

/**
 * Cache des fiches TMDB. Il peut être vidé sans rien perdre : les données de l'utilisateur
 * vivent dans [UserMovieEntity].
 */
@Entity(tableName = "movie")
data class MovieEntity(
    @PrimaryKey val tmdbId: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val overviewLanguage: String? = null,
    val director: String? = null,
    val cast: List<CastMember> = emptyList(),
    val trailerKey: String? = null,
    val genreIds: List<Int> = emptyList(),
    val genres: List<String> = emptyList(),
    val tmdbRating: Double? = null,
    val voteCount: Int? = null,
    val runtimeMinutes: Int? = null,
    /** Date du dernier enrichissement complet, null si la fiche est partielle. */
    val fetchedAt: Long? = null,
    /** Mots-clés TMDB (« voyage dans le temps », « film noir »…), pour le profil de goût. */
    @ColumnInfo(defaultValue = "'[]'") val keywords: List<TmdbTag> = emptyList(),
    /** Réalisateurs avec leur identifiant ; [director] garde les noms à afficher. */
    @ColumnInfo(defaultValue = "'[]'") val directors: List<TmdbTag> = emptyList(),
    /** Langue originale (« en », « fr », « ko »…). */
    val originalLanguage: String? = null,
    /** Popularité TMDB (audience récente). */
    val popularity: Double? = null,
    /** Version du chargement complet : une fiche d'une version plus ancienne est rechargée. */
    @ColumnInfo(defaultValue = "0") val detailsVersion: Int = 0,
    /** Saga TMDB du film (« Matrix », « Le Parrain »…), pour proposer les autres épisodes. */
    val collectionId: Long? = null,
    /** Série : nombre de saisons, statut de diffusion TMDB, année de la dernière diffusion, saisons. */
    val seasonCount: Int? = null,
    val airStatus: String? = null,
    val endYear: Int? = null,
    @ColumnInfo(defaultValue = "'[]'") val seasons: List<TvSeason> = emptyList(),
)

/**
 * Données de l'utilisateur pour un film. Retirer un film des vus ne supprime jamais la ligne :
 * le classement et les duels sont conservés.
 */
@Entity(
    tableName = "user_movie",
    indices = [Index("status"), Index("availability")],
)
data class UserMovieEntity(
    @PrimaryKey val tmdbId: Long,
    val status: WatchStatus = WatchStatus.NONE,
    val watchedAt: Long? = null,
    val watchedSource: WatchSource? = null,
    /** Note du classement (échelle Elo), recalculée à chaque duel à partir du journal. */
    val rating: Double = Ranking.INITIAL_RATING,
    val duels: Int = 0,
    val availability: Availability = Availability.NONE,
    val availabilityUpdatedAt: Long? = null,
    /** Mémoire du feed : nombre d'affichages sans action. */
    val shownCount: Int = 0,
    val lastShownAt: Long? = null,
    val updatedAt: Long,
    /** Incertitude de la note (un écart type, en points Elo). */
    @ColumnInfo(defaultValue = "350.0") val deviation: Double = Ranking.INITIAL_DEVIATION,
    /** Probabilité que les étoiles affichées soient justes à une demi-étoile près. */
    @ColumnInfo(defaultValue = "0.0") val confidence: Double = 0.0,
    /** Note de départ du classement : 1500, ou la note héritée d'une version sans journal des duels. */
    @ColumnInfo(defaultValue = "1500.0") val priorRating: Double = Ranking.INITIAL_RATING,
    /** Duels comptés dans [priorRating] mais absents du journal. */
    @ColumnInfo(defaultValue = "0") val priorDuels: Int = 0,
    /**
     * Date de la réponse au recensement : le film n'y est plus proposé. Une réponse « pas vu »
     * ne change rien d'autre (statut, recos).
     */
    val censusAt: Long? = null,
    /** Première impression (adoré, bof) : elle décale le point de départ du film dans l'Arène. */
    val impression: Impression? = null,
    /** Note reprise d'une autre appli (Letterboxd, IMDb, Trakt…), sur 10 : point de départ dans l'Arène. */
    val importedRating: Double? = null,
    /** Série vue : en cours, finie ou abandonnée (null pour un film). */
    val progress: SeriesProgress? = null,
    /** Série en cours ou abandonnée : saison où l'utilisateur en est. */
    val currentSeason: Int? = null,
)

/** Journal des duels de l'Arène, en ajout seul : il permet d'annuler et de recalculer. */
@Entity(
    tableName = "duel",
    indices = [Index("winnerId"), Index("loserId")],
)
data class DuelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val winnerId: Long,
    val loserId: Long,
    val createdAt: Long,
    val winnerRatingBefore: Double,
    val loserRatingBefore: Double,
    val winnerRatingAfter: Double,
    val loserRatingAfter: Double,
    /** Égalité : les deux films se valent (l'ordre gagnant/perdant est alors indifférent). */
    @ColumnInfo(defaultValue = "0") val draw: Boolean = false,
)

/** Nature d'une action réseau en attente. */
enum class OutboxType {
    /** Demander un film (Seerr ou Radarr). */
    REQUEST,

    /** Annuler une demande déjà envoyée (bouton Annuler). */
    CANCEL,
}

enum class OutboxState { PENDING, SENT, FAILED }

/**
 * File d'attente des demandes : une demande faite hors réseau part dès que le service répond.
 * Une demande envoyée garde l'identifiant donné par le service ([remoteId]) pour pouvoir être
 * annulée ; elle est purgée au bout d'un jour.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("tmdbId"), Index("state")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: OutboxType,
    val service: HomelabService,
    val tmdbId: Long,
    val title: String,
    val year: Int? = null,
    val state: OutboxState = OutboxState.PENDING,
    val remoteId: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Série : saisons demandées (« 1,3 ») ; null = toute la série. */
    val seasons: String? = null,
) {
    /** Saisons demandées, ou null pour toute la série. */
    val seasonList: List<Int>? get() = seasons?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }

    companion object {
        fun encodeSeasons(seasons: List<Int>?): String? = seasons?.distinct()?.sorted()?.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}

/** Nature d'un lien entre deux films sur TMDB. */
enum class RecoLinkKind {
    /** « Recommandations » : films appréciés par les mêmes spectateurs. */
    RECOMMENDED,

    /** « Similaires » : mêmes mots-clés et genres (repli quand un film n'a pas de recommandations). */
    SIMILAR,
}

/**
 * Lien TMDB « les amateurs de [seedId] aiment aussi [movieId] », à la place [position] de la liste.
 * Le moteur de recos agrège ces liens sur tous les films notés : un film recommandé par
 * plusieurs films aimés remonte, un film recommandé par des films détestés descend.
 */
@Entity(
    tableName = "reco_link",
    primaryKeys = ["seedId", "movieId"],
    indices = [Index("movieId")],
)
data class RecoLinkEntity(
    val seedId: Long,
    val movieId: Long,
    /** Place dans la liste de TMDB (0 = en tête). */
    val position: Int,
    val kind: RecoLinkKind,
)

/** Film dont les recommandations TMDB ont été récupérées (et quand). */
@Entity(tableName = "reco_seed")
data class RecoSeedEntity(
    @PrimaryKey val seedId: Long,
    val fetchedAt: Long,
)

/**
 * Bilan d'une source de recos : cartes montrées, gardées (vu, à voir, demandé) et écartées.
 * Le feed puise davantage dans les sources qui plaisent (bandit de Thompson).
 */
@Entity(tableName = "reco_source")
data class RecoSourceEntity(
    @PrimaryKey val source: RecoSource,
    val shown: Double = 0.0,
    val liked: Double = 0.0,
    val disliked: Double = 0.0,
    val updatedAt: Long = 0,
)

/**
 * Film du catalogue à recenser, avec la liste d'où il vient. [boost] : un autre film de sa saga
 * a été vu, il a de grandes chances d'avoir été vu aussi.
 */
@Entity(tableName = "census_pool")
data class CensusPoolEntity(
    @PrimaryKey val tmdbId: Long,
    val source: CensusSource,
    @ColumnInfo(defaultValue = "0") val boost: Boolean = false,
    val addedAt: Long,
)

/** Liste perso (« Films de Noël », « À montrer à… »), en plus de la liste À voir. */
@Entity(tableName = "user_list")
data class UserListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Un film d'une liste perso. */
@Entity(
    tableName = "user_list_item",
    primaryKeys = ["listId", "tmdbId"],
    indices = [Index("tmdbId")],
)
data class UserListItemEntity(
    val listId: Long,
    val tmdbId: Long,
    val addedAt: Long,
)
