package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPersonPageDto
import io.github.deedg.cineswipe.domain.StarScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Une personne (acteur, réalisateur), pour sa fiche. */
data class Person(
    val id: Long,
    val name: String,
    val profilePath: String?,
    /** « Acting », « Directing »… (département principal chez TMDB). */
    val knownFor: String?,
    val biography: String?,
    val birthYear: Int?,
    val deathYear: Int?,
    val placeOfBirth: String?,
)

/** Une personne trouvée par la recherche, avec quelques-uns de ses films connus. */
data class PersonHit(
    val id: Long,
    val name: String,
    val profilePath: String?,
    val knownFor: String?,
    val works: List<String>,
)

/** Un film d'une personne : joué, réalisé, ou les deux. */
data class PersonFilm(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val releaseDate: String?,
    val posterPath: String?,
    val voteCount: Int,
    val popularity: Double,
    val acted: Boolean,
    val directed: Boolean,
    val character: String?,
)

/** Ce que l'utilisateur a fait d'un film de la filmographie. */
data class PersonFilmStatus(
    val status: WatchStatus,
    val stars: Float?,
    val available: Boolean,
)

data class PersonPage(val person: Person, val films: List<PersonFilm>) {
    /** Elle joue et réalise : chaque film dit ce qu'elle y a fait. */
    val actsAndDirects: Boolean get() = films.any { it.acted } && films.any { it.directed }
}

/** Fiches des acteurs et réalisateurs : leurs films chez TMDB, croisés avec la bibliothèque. */
class PeopleRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
) {
    suspend fun page(personId: Long): PersonPage = toPage(tmdb.person(personId))

    /** Acteurs et réalisateurs dont le nom correspond, les plus pertinents d'abord. */
    suspend fun search(query: String): List<PersonHit> = toHits(tmdb.searchPeople(query))

    /** Statut des films (vu et étoiles, à voir, dispo), mis à jour en direct. */
    fun statuses(ids: List<Long>): Flow<Map<Long, PersonFilmStatus>> {
        if (ids.isEmpty()) return flowOf(emptyMap())
        val dao = database.userMovieDao()
        return combine(dao.observeAll(ids), dao.observeRanked(), settings.homelabConnected) { rows, ranked, homelab ->
            val stars = StarScale.computeStars(ranked.associate { it.tmdbId to it.rating })
            rows.associate { row ->
                row.tmdbId to PersonFilmStatus(
                    status = row.status,
                    stars = stars[row.tmdbId].takeIf { row.status == WatchStatus.WATCHED },
                    available = homelab && row.availability == Availability.AVAILABLE,
                )
            }
        }
    }

    companion object {
        const val MAX_HITS = 6

        /**
         * Résultats de la recherche : sans contenu adulte, ceux qui ont des films connus d'abord
         * (l'ordre de TMDB est gardé à l'intérieur de chaque groupe), au plus [MAX_HITS].
         */
        fun toHits(dto: TmdbPersonPageDto): List<PersonHit> = dto.results
            .filter { !it.adult }
            .mapNotNull { hit ->
                val name = TmdbMapper.cleanText(hit.name) ?: return@mapNotNull null
                val works = hit.works
                    .filter { it.mediaType == null || it.mediaType == "movie" }
                    .mapNotNull { TmdbMapper.cleanText(it.title) ?: TmdbMapper.cleanText(it.name) }
                    .distinct()
                    .take(3)
                PersonHit(hit.id, name, TmdbMapper.imagePath(hit.profilePath), TmdbMapper.cleanText(hit.knownFor), works)
            }
            .sortedBy { it.works.isEmpty() }
            .take(MAX_HITS)

        /** Assemble la filmographie : un film par entrée, rôles et réalisation réunis. */
        fun toPage(dto: TmdbPersonDto): PersonPage {
            val films = LinkedHashMap<Long, PersonFilm>()
            fun add(credit: io.github.deedg.cineswipe.data.tmdb.TmdbPersonCreditDto, acted: Boolean, directed: Boolean) {
                if (credit.adult) return
                val title = TmdbMapper.cleanText(credit.title) ?: TmdbMapper.cleanText(credit.originalTitle) ?: return
                val current = films[credit.id]
                films[credit.id] = PersonFilm(
                    tmdbId = credit.id,
                    title = current?.title ?: title,
                    year = current?.year ?: TmdbMapper.year(credit.releaseDate),
                    releaseDate = current?.releaseDate ?: TmdbMapper.cleanText(credit.releaseDate),
                    posterPath = current?.posterPath ?: TmdbMapper.imagePath(credit.posterPath),
                    voteCount = maxOf(current?.voteCount ?: 0, credit.voteCount ?: 0),
                    popularity = maxOf(current?.popularity ?: 0.0, credit.popularity ?: 0.0),
                    acted = (current?.acted ?: false) || acted,
                    directed = (current?.directed ?: false) || directed,
                    character = current?.character ?: TmdbMapper.cleanText(credit.character).takeIf { acted },
                )
            }
            dto.credits.cast.forEach { add(it, acted = true, directed = false) }
            dto.credits.crew.filter { it.job == "Director" }.forEach { add(it, acted = false, directed = true) }
            val person = Person(
                id = dto.id,
                name = TmdbMapper.cleanText(dto.name) ?: "?",
                profilePath = TmdbMapper.imagePath(dto.profilePath),
                knownFor = TmdbMapper.cleanText(dto.knownFor),
                biography = TmdbMapper.cleanText(dto.biography),
                birthYear = TmdbMapper.year(dto.birthday),
                deathYear = TmdbMapper.year(dto.deathday),
                placeOfBirth = TmdbMapper.cleanText(dto.placeOfBirth),
            )
            return PersonPage(person, films.values.toList())
        }
    }
}
