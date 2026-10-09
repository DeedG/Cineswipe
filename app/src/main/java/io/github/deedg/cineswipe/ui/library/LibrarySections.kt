package io.github.deedg.cineswipe.ui.library

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.model.LibraryItem
import io.github.deedg.cineswipe.domain.StarScale
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

enum class LibrarySort {
    AZ,
    ELO,
    YEAR,
    ;

    /** Le bouton de tri passe de A-Z à Elo, puis à l'année, puis revient à A-Z. */
    fun next(): LibrarySort = entries[(ordinal + 1) % entries.size]
}

enum class LibraryFilter { ALL, WATCHED, AVAILABLE, WATCHLIST }

data class LibraryRowUi(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val watched: Boolean,
    val available: Boolean,
    val requested: Boolean,
    val watchlist: Boolean,
    val rating: Int?,
    val stars: Float?,
    val provisional: Boolean,
)

/**
 * Section de la liste. [label] s'affiche dans la bulle de l'index, [indexLabel] dans l'index
 * latéral. [eloFloor] (tri Elo, ex. 1600) et [decade] (tri par année, ex. 1990) servent aux en-têtes.
 */
data class LibrarySection(
    val key: String,
    val label: String,
    val indexLabel: String,
    val eloFloor: Int?,
    val decade: Int? = null,
    val rows: List<LibraryRowUi>,
)

/** Construction des sections de la bibliothèque, sans dépendance Android (testée en local). */
object LibrarySections {
    const val UNRANKED_KEY = "unranked"
    const val UNKNOWN_YEAR_KEY = "year-unknown"
    private const val OTHER_LETTER = "#"

    private val collator: Collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    private val byTitle = Comparator<LibraryRowUi> { a, b -> collator.compare(a.title, b.title) }

    /**
     * [showAvailability] faux (aucun serveur média connecté) : les films seulement « dispo »
     * disparaissent, ainsi que les badges Dispo et Demandé.
     */
    fun build(
        items: List<LibraryItem>,
        query: String,
        sort: LibrarySort,
        filter: LibraryFilter,
        showAvailability: Boolean = true,
    ): List<LibrarySection> {
        val visible = if (showAvailability) {
            items
        } else {
            items.filter { it.status == WatchStatus.WATCHED || it.status == WatchStatus.WATCHLIST }
        }
        val effectiveFilter = if (!showAvailability && filter == LibraryFilter.AVAILABLE) LibraryFilter.ALL else filter
        val stars = StarScale.computeStars(
            visible.filter { it.status == WatchStatus.WATCHED }.associate { it.tmdbId to it.rating },
        )
        val needle = normalize(query.trim())
        val rows = visible
            .filter { it.matches(effectiveFilter) }
            .filter { needle.isEmpty() || normalize(it.title).contains(needle) }
            .map { it.toRow(stars[it.tmdbId], showAvailability) }

        return when (sort) {
            LibrarySort.AZ -> rows
                .sortedWith(byTitle)
                .groupBy { initialOf(it.title) }
                .toSortedMap(compareBy<String> { it == OTHER_LETTER }.thenBy { it })
                .map { (letter, sectionRows) ->
                    LibrarySection(key = "az-$letter", label = letter, indexLabel = letter, eloFloor = null, rows = sectionRows)
                }

            LibrarySort.ELO -> {
                val (ranked, unranked) = rows.partition { it.rating != null }
                val rankedSections = ranked
                    .sortedByDescending { it.rating }
                    .groupBy { (it.rating!! / 100) * 100 }
                    .map { (floor, sectionRows) ->
                        LibrarySection(
                            key = "elo-$floor",
                            label = floor.toString(),
                            indexLabel = (floor / 100).toString(),
                            eloFloor = floor,
                            rows = sectionRows,
                        )
                    }
                val unrankedSection = unranked
                    .sortedWith(byTitle)
                    .takeIf { it.isNotEmpty() }
                    ?.let { LibrarySection(key = UNRANKED_KEY, label = "–", indexLabel = "–", eloFloor = null, rows = it) }
                rankedSections + listOfNotNull(unrankedSection)
            }

            LibrarySort.YEAR -> {
                val (dated, undated) = rows.partition { it.year != null }
                val decadeSections = dated
                    .sortedWith(compareByDescending<LibraryRowUi> { it.year }.then(byTitle))
                    .groupBy { decadeOf(it.year!!) }
                    .map { (decade, sectionRows) ->
                        LibrarySection(
                            key = "year-$decade",
                            label = decade.toString(),
                            indexLabel = decadeIndexLabel(decade),
                            eloFloor = null,
                            decade = decade,
                            rows = sectionRows,
                        )
                    }
                val undatedSection = undated
                    .sortedWith(byTitle)
                    .takeIf { it.isNotEmpty() }
                    ?.let { LibrarySection(key = UNKNOWN_YEAR_KEY, label = "?", indexLabel = "?", eloFloor = null, rows = it) }
                decadeSections + listOfNotNull(undatedSection)
            }
        }
    }

    /** 1994 → 1990. */
    fun decadeOf(year: Int): Int = Math.floorDiv(year, 10) * 10

    /** Libellé court de l'index latéral : 1990 → « ’90 », 2010 → « ’10 ». */
    fun decadeIndexLabel(decade: Int): String = "’" + (Math.floorMod(decade, 100)).toString().padStart(2, '0')

    /** Lettre d'index : première lettre sans accent, « # » pour les chiffres et symboles. */
    fun initialOf(title: String): String {
        val first = normalize(title).firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: return OTHER_LETTER
        return if (first in 'A'..'Z') first.toString() else OTHER_LETTER
    }

    /** Minuscules sans accents, pour la recherche et l'index. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.FRENCH)

    private val DIACRITICS = Regex("\\p{Mn}+")

    private fun LibraryItem.matches(filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.WATCHED -> status == WatchStatus.WATCHED
        LibraryFilter.AVAILABLE -> availability == Availability.AVAILABLE
        LibraryFilter.WATCHLIST -> status == WatchStatus.WATCHLIST
    }

    private fun LibraryItem.toRow(starsValue: Float?, showAvailability: Boolean): LibraryRowUi {
        val watched = status == WatchStatus.WATCHED
        return LibraryRowUi(
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterPath = posterPath,
            watched = watched,
            available = showAvailability && availability == Availability.AVAILABLE,
            requested = showAvailability && availability == Availability.REQUESTED,
            watchlist = status == WatchStatus.WATCHLIST,
            rating = if (watched) rating.roundToInt() else null,
            stars = if (watched) starsValue else null,
            provisional = StarScale.isProvisional(confidence),
        )
    }
}
