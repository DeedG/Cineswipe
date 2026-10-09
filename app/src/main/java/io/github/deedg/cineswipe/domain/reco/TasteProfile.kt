package io.github.deedg.cineswipe.domain.reco

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Familles de caractéristiques du profil de goût, avec leur poids dans le score et leur
 * prudence : une affinité appuyée sur peu de films est rapetissée d'autant plus que
 * [shrinkage] est grand.
 */
enum class TasteFamily(val weight: Double, val shrinkage: Double) {
    GENRE(0.8, 4.0),
    KEYWORD(0.7, 2.0),
    DIRECTOR(0.9, 1.0),
    CAST(0.5, 2.0),
    DECADE(0.3, 4.0),
    LANGUAGE(0.4, 4.0),
}

/** Affinité pour une caractéristique (un genre, un réalisateur…), appuyée sur [films] films. */
data class Affinity(
    val family: TasteFamily,
    val key: String,
    val name: String,
    val value: Double,
    val films: Int,
)

/** Le réalisateur, l'acteur ou le thème aimé qui pèse le plus pour un film. */
data class TasteHighlight(
    val family: TasteFamily,
    val name: String,
    val contribution: Double,
)

data class ContentScore(
    val value: Double,
    val highlight: TasteHighlight?,
)

/**
 * Profil de goût : pour chaque genre, mot-clé, réalisateur, acteur, décennie et langue, la
 * somme des poids des films qui l'ont, divisée par leur nombre plus une marge de prudence.
 * Un genre ressort s'il revient souvent dans les films aimés ; il plonge s'il revient dans les
 * films écartés ou mal classés.
 */
class TasteProfile(films: List<TasteFilm>) {

    val size: Int = films.size

    private val affinities: Map<TasteFamily, Map<String, Affinity>>

    init {
        val sums = TasteFamily.entries.associateWith { HashMap<String, Triple<String, Double, Int>>() }
        fun add(family: TasteFamily, key: String, name: String, weight: Double) {
            val map = sums.getValue(family)
            val current = map[key]
            map[key] = Triple(name, (current?.second ?: 0.0) + weight, (current?.third ?: 0) + 1)
        }
        films.forEach { film ->
            val features = film.features
            features.genreIds.distinct().forEach { add(TasteFamily.GENRE, it.toString(), it.toString(), film.weight) }
            features.keywords.distinctBy { it.id }.forEach { add(TasteFamily.KEYWORD, it.id.toString(), it.name, film.weight) }
            features.directors.distinctBy { it.id }.forEach { add(TasteFamily.DIRECTOR, it.id.toString(), it.name, film.weight) }
            features.cast.take(MAX_CAST).distinctBy { it.id }.forEach { add(TasteFamily.CAST, it.id.toString(), it.name, film.weight) }
            decade(features.year)?.let { add(TasteFamily.DECADE, it.toString(), it.toString(), film.weight) }
            features.language?.let { add(TasteFamily.LANGUAGE, it, it, film.weight) }
        }
        affinities = sums.mapValues { (family, map) ->
            map.mapValues { (key, value) ->
                Affinity(family, key, value.first, value.second / (value.third + family.shrinkage), value.third)
            }
        }
    }

    fun affinity(family: TasteFamily, key: String): Affinity? = affinities.getValue(family)[key]

    /** Affinités les plus fortes d'une famille, appuyées sur au moins [minFilms] films. */
    fun top(family: TasteFamily, count: Int, minFilms: Int = 1): List<Affinity> =
        affinities.getValue(family).values
            .filter { it.value > 0.0 && it.films >= minFilms }
            .sortedWith(compareByDescending<Affinity> { it.value }.thenBy { it.key })
            .take(count)

    /** Score de contenu d'un film : positif s'il ressemble aux films aimés. */
    fun score(features: FilmFeatures): ContentScore {
        if (size == 0) return ContentScore(0.0, null)
        var total = 0.0
        var highlight: TasteHighlight? = null
        fun consider(family: TasteFamily, affinity: Affinity?) {
            if (affinity == null || affinity.films < MIN_HIGHLIGHT_FILMS) return
            val contribution = family.weight * affinity.value
            if (contribution >= MIN_HIGHLIGHT && contribution > (highlight?.contribution ?: 0.0)) {
                highlight = TasteHighlight(family, affinity.name, contribution)
            }
        }

        val genres = features.genreIds.distinct()
        if (genres.isNotEmpty()) {
            total += TasteFamily.GENRE.weight * genres.sumOf { value(TasteFamily.GENRE, it.toString()) } / genres.size
        }

        val keywords = features.keywords.distinctBy { it.id }
        if (keywords.isNotEmpty()) {
            total += TasteFamily.KEYWORD.weight * keywords.sumOf { value(TasteFamily.KEYWORD, it.id.toString()) } / sqrt(keywords.size.toDouble())
            consider(TasteFamily.KEYWORD, keywords.mapNotNull { affinity(TasteFamily.KEYWORD, it.id.toString()) }.maxByOrNull { it.value })
        }

        val directors = features.directors.distinctBy { it.id }
        if (directors.isNotEmpty()) {
            val best = directors.mapNotNull { affinity(TasteFamily.DIRECTOR, it.id.toString()) }.maxByOrNull { it.value }
            total += TasteFamily.DIRECTOR.weight * (best?.value ?: 0.0)
            consider(TasteFamily.DIRECTOR, best)
        }

        val cast = features.cast.take(MAX_CAST).distinctBy { it.id }
        if (cast.isNotEmpty()) {
            total += TasteFamily.CAST.weight * cast.sumOf { value(TasteFamily.CAST, it.id.toString()) } / sqrt(cast.size.toDouble())
            consider(TasteFamily.CAST, cast.mapNotNull { affinity(TasteFamily.CAST, it.id.toString()) }.maxByOrNull { it.value })
        }

        decade(features.year)?.let { total += TasteFamily.DECADE.weight * value(TasteFamily.DECADE, it.toString()) }
        features.language?.let { total += TasteFamily.LANGUAGE.weight * value(TasteFamily.LANGUAGE, it) }
        return ContentScore(total, highlight)
    }

    private fun value(family: TasteFamily, key: String): Double = affinities.getValue(family)[key]?.value ?: 0.0

    companion object {
        /** Seuls les premiers rôles comptent. */
        const val MAX_CAST = 5

        /** Une affinité mise en avant (« Avec Ryan Gosling ») s'appuie sur au moins deux films. */
        const val MIN_HIGHLIGHT_FILMS = 2
        const val MIN_HIGHLIGHT = 0.3

        /** Poids d'un film vu de note moyenne : l'avoir vu dit déjà un peu d'intérêt. */
        const val NEUTRAL_WATCHED = 0.5

        /** Écart de poids par écart type de note (jusqu'à ± 2 écarts types). */
        const val RATING_SLOPE = 0.6
        const val WATCHLIST_WEIGHT = 0.7
        const val DISMISSED_WEIGHT = -1.0

        /** Écart type minimal des notes (évite de surinterpréter un classement encore plat). */
        private const val MIN_SPREAD = 60.0

        fun decade(year: Int?): Int? = year?.takeIf { it in 1870..2200 }?.let { it / 10 * 10 }

        /**
         * Poids des films vus selon leur note : 0,5 pour un film dans la moyenne, jusqu'à 1,7 pour
         * les mieux classés et −0,7 pour les moins bien classés.
         */
        fun watchedWeights(ratings: Map<Long, Double>): Map<Long, Double> {
            if (ratings.isEmpty()) return emptyMap()
            val mean = ratings.values.average()
            val spread = sqrt(ratings.values.sumOf { (it - mean).pow(2) } / ratings.size).coerceAtLeast(MIN_SPREAD)
            return ratings.mapValues { (_, rating) ->
                NEUTRAL_WATCHED + RATING_SLOPE * ((rating - mean) / spread).coerceIn(-2.0, 2.0)
            }
        }
    }
}
