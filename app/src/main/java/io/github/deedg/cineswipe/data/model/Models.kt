package io.github.deedg.cineswipe.data.model

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.domain.StarScale

/** Ligne de la bibliothèque (jointure user_movie + movie). */
data class LibraryItem(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val status: WatchStatus,
    val rating: Double,
    val duels: Int,
    /** Probabilité que les étoiles soient justes à une demi-étoile près. */
    val confidence: Double,
    val availability: Availability,
    val watchedAt: Long?,
)

/** Compteurs du profil. */
data class LibraryStats(
    val watched: Int,
    val watchlist: Int,
    val available: Int,
    val duels: Int,
)

/** Statut d'un film pour les badges des cartes (seuls les films avec un statut sont listés). */
data class CardStatusRow(
    val tmdbId: Long,
    val status: WatchStatus,
    val availability: Availability,
)

/** Fiabilité du classement : confiance moyenne dans les étoiles des [films] films classés. */
data class RankingReliability(
    val films: Int,
    val value: Double,
)

/** Genre TMDB, dans la langue des fiches. */
data class TmdbGenre(
    val id: Int,
    val name: String,
)

data class Fighter(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val rating: Double,
    val duels: Int,
    val stars: Float,
    val provisional: Boolean,
)

data class ArenaMatch(
    val a: Fighter,
    val b: Fighter,
)

data class MovieDetails(
    val movie: MovieEntity,
    val user: UserMovieEntity?,
    /** Étoiles si le film est vu, sinon null. */
    val stars: Float?,
) {
    val isWatched: Boolean get() = user?.status == WatchStatus.WATCHED
    val provisional: Boolean get() = StarScale.isProvisional(user?.confidence ?: 0.0)
}

/** Duel du journal, pour l'historique. */
data class DuelHistoryRow(
    val id: Long,
    val createdAt: Long,
    val winnerId: Long,
    val winnerTitle: String?,
    val winnerPoster: String?,
    val loserId: Long,
    val loserTitle: String?,
    val loserPoster: String?,
    val winnerRatingBefore: Double,
    val winnerRatingAfter: Double,
    val loserRatingBefore: Double,
    val loserRatingAfter: Double,
    /** Égalité : ni vainqueur ni vaincu. */
    val draw: Boolean,
)
