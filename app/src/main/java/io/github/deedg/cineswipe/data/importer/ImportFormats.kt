package io.github.deedg.cineswipe.data.importer

import io.github.deedg.cineswipe.data.repository.ExportFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/** D'où viennent les films importés. */
enum class ImportSource { CINESWIPE, LETTERBOXD, TRAKT, IMDB, CSV }

/**
 * Un film d'une autre appli : identifiants connus (TMDB, IMDb) ou titre et année, et ce qu'on en
 * sait (vu, à voir, note sur 10, date de visionnage).
 */
data class ImportItem(
    val title: String? = null,
    val year: Int? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val watched: Boolean = false,
    val watchlist: Boolean = false,
    val rating10: Double? = null,
    val watchedAt: Long? = null,
    /** Série (Trakt) : dernière saison commencée et épisodes vus dans cette saison. */
    val season: Int? = null,
    val seasonEpisodes: Int? = null,
) {
    /** Clé de regroupement : le même film peut apparaître dans plusieurs fichiers. */
    internal val key: String
        get() = when {
            tmdbId != null -> "tmdb:$tmdbId"
            imdbId != null -> "imdb:$imdbId"
            else -> "title:${ImportFormats.normalize(title.orEmpty())}|$year"
        }

    internal fun mergedWith(other: ImportItem): ImportItem = ImportItem(
        title = title ?: other.title,
        year = year ?: other.year,
        tmdbId = tmdbId ?: other.tmdbId,
        imdbId = imdbId ?: other.imdbId,
        watched = watched || other.watched,
        watchlist = (watchlist || other.watchlist) && !(watched || other.watched),
        rating10 = other.rating10 ?: rating10,
        season = listOfNotNull(season, other.season).maxOrNull(),
        seasonEpisodes = if ((other.season ?: -1) > (season ?: -1)) other.seasonEpisodes else seasonEpisodes ?: other.seasonEpisodes,
        // Première date connue : le journal Letterboxd a la vraie date, watched.csv celle de l'ajout.
        watchedAt = listOfNotNull(watchedAt, other.watchedAt).minOrNull(),
    )
}

/** Fichier choisi par l'utilisateur (ou extrait d'une archive). */
class ImportFile(val name: String, val bytes: ByteArray)

/** Ce qu'on a lu dans les fichiers, prêt à être rapproché de TMDB. */
data class ImportBatch(
    val sources: Set<ImportSource>,
    val items: List<ImportItem>,
    /** Sauvegarde CineSwipe complète (export JSON), restaurable telle quelle. */
    val backup: ExportFile? = null,
    /** Fichiers qui n'ont pas été reconnus. */
    val ignored: List<String> = emptyList(),
) {
    val watched: Int get() = items.count { it.watched }
    val watchlist: Int get() = items.count { it.watchlist }
    val rated: Int get() = items.count { it.rating10 != null }
}

/**
 * Lecture des exports : sauvegarde CineSwipe (JSON), Letterboxd (archive ZIP ou CSV), Trakt
 * (archive ZIP ou JSON), IMDb (CSV des notes ou de la watchlist), et tout CSV avec une colonne
 * titre (SensCritique, Allociné, TV Time, Simkl… via leurs exports ou des outils tiers).
 */
object ImportFormats {
    private val json = Json { ignoreUnknownKeys = true }

    /** [series] : dans une sauvegarde à deux mondes (films et séries), celle des séries est prise. */
    fun parse(files: List<ImportFile>, series: Boolean = false): ImportBatch {
        val sources = LinkedHashSet<ImportSource>()
        val items = ArrayList<ImportItem>()
        val ignored = ArrayList<String>()
        val backups = ArrayList<ExportFile>()
        files.forEach { original ->
            val expanded = runCatching { expand(original) }.getOrElse {
                ignored += original.name
                return@forEach
            }
            // Une archive contient bien des fichiers sans films (profil, commentaires, séries) :
            // seuls les fichiers choisis un par un sont signalés comme ignorés.
            val archive = expanded.size != 1 || expanded.first() !== original
            var recognised = false
            expanded.forEach { file ->
                val result = runCatching { parseFile(file, series) }.getOrNull()
                when {
                    result == null -> if (!archive) ignored += file.name
                    result.backup != null -> {
                        backups += result.backup
                        sources += ImportSource.CINESWIPE
                        recognised = true
                    }
                    result.source == null -> if (!archive && !result.silent) ignored += file.name
                    else -> {
                        sources += result.source
                        items += result.items
                        recognised = true
                    }
                }
            }
            if (archive && !recognised) ignored += original.name
        }
        val backup = backups.firstOrNull { (it.kind == "SERIES") == series } ?: backups.firstOrNull()
        backup?.let { saved -> items += saved.films.mapNotNull(::backupItem) }
        return ImportBatch(sources, merge(items), backup, ignored)
    }

    /** Regroupe les lignes d'un même film (vu dans un fichier, noté dans un autre…). */
    fun merge(items: List<ImportItem>): List<ImportItem> {
        val byKey = LinkedHashMap<String, ImportItem>()
        items.forEach { item ->
            if (item.tmdbId == null && item.imdbId == null && item.title.isNullOrBlank()) return@forEach
            byKey[item.key] = byKey[item.key]?.mergedWith(item) ?: item
        }
        return byKey.values.toList()
    }

    private class Parsed(
        val source: ImportSource?,
        val items: List<ImportItem> = emptyList(),
        val backup: ExportFile? = null,
        /** Fichier connu mais sans films à reprendre (profil Letterboxd, séries Trakt…). */
        val silent: Boolean = false,
    )

    private fun expand(file: ImportFile): List<ImportFile> {
        val isZip = file.bytes.size > 4 && file.bytes[0] == 'P'.code.toByte() && file.bytes[1] == 'K'.code.toByte()
        if (!isZip) return listOf(file)
        val result = ArrayList<ImportFile>()
        ZipInputStream(ByteArrayInputStream(file.bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result += ImportFile(entry.name, zip.readBytes())
            }
        }
        return result
    }

    private fun parseFile(file: ImportFile, series: Boolean): Parsed {
        val name = file.name.substringAfterLast('/').lowercase(Locale.ROOT)
        val path = file.name.lowercase(Locale.ROOT)
        if (path.contains("deleted/") || path.contains("orphaned/")) return Parsed(null, silent = true)
        val text = file.bytes.decodeToString().removePrefix("﻿")
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(name, json.parseToJsonElement(trimmed), series) else parseCsv(name, path, text, series)
    }

    // --- JSON : CineSwipe, Trakt ---

    /** [series] : les séries de Trakt (« show ») au lieu des films (« movie »). */
    private fun parseJson(name: String, root: JsonElement, series: Boolean = false): Parsed {
        if (root is JsonObject && root.string("app") == "CineSwipe") {
            return Parsed(ImportSource.CINESWIPE, backup = json.decodeFromJsonElement(ExportFile.serializer(), root))
        }
        if (root !is JsonArray) return Parsed(null)
        if (root.isEmpty()) return Parsed(ImportSource.TRAKT, silent = true)
        val entries = root.filterIsInstance<JsonObject>()
        if (entries.none { "movie" in it || "show" in it || "episode" in it }) return Parsed(null)
        if (name.contains("collection") || name.contains("lists")) return Parsed(null, silent = true)
        val watchlistFile = name.contains("watchlist")
        // Notes d'épisodes ou de saisons : pas une note de la série.
        val items = entries.mapNotNull { entry ->
            if (series && entry.string("type").let { it == "episode" || it == "season" }) return@mapNotNull null
            val movie = entry[if (series) "show" else "movie"] as? JsonObject ?: return@mapNotNull null
            val ids = movie["ids"] as? JsonObject
            // Séries vues : la dernière saison commencée et ses épisodes vus.
            val lastSeason = (entry["seasons"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.filter { season -> (season["episodes"] as? JsonArray)?.isNotEmpty() == true }
                ?.maxByOrNull { (it["number"] as? JsonPrimitive)?.intOrNull ?: 0 }
            val rating = (entry["rating"] as? JsonPrimitive)?.doubleOrNull
            val watchedAt = entry.string("last_watched_at") ?: entry.string("watched_at")
            val watched = !watchlistFile && (rating != null || watchedAt != null || "plays" in entry)
            ImportItem(
                title = movie.string("title"),
                year = (movie["year"] as? JsonPrimitive)?.intOrNull,
                tmdbId = (ids?.get("tmdb") as? JsonPrimitive)?.longOrNull,
                imdbId = ids?.string("imdb")?.takeIf { it.startsWith("tt") },
                watched = watched,
                watchlist = watchlistFile,
                rating10 = rating?.takeIf { it in 1.0..10.0 },
                watchedAt = parseInstant(watchedAt ?: entry.string("rated_at")).takeIf { watched },
                season = lastSeason?.let { (it["number"] as? JsonPrimitive)?.intOrNull }?.takeIf { it >= 1 },
                seasonEpisodes = (lastSeason?.get("episodes") as? JsonArray)?.size,
            )
        }
        return Parsed(ImportSource.TRAKT, items, silent = items.isEmpty())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // --- CSV : Letterboxd, IMDb, générique ---

    private fun parseCsv(name: String, path: String, text: String, series: Boolean = false): Parsed {
        if (text.startsWith("Letterboxd list export")) return Parsed(null, silent = true)
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return Parsed(null)
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        // En-tête seul (watchlist vide…) : fichier connu, sans film.
        if (rows.size < 2) return Parsed(null, silent = header.any { it in KNOWN_COLUMNS })
        val data = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        fun column(vararg names: String): Int = names.firstNotNullOfOrNull { wanted -> header.indexOf(wanted).takeIf { it >= 0 } } ?: -1
        fun List<String>.at(index: Int): String? = getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

        if ("letterboxd uri" in header) {
            if (name == "profile.csv" || name == "comments.csv" || path.contains("lists/")) return Parsed(null, silent = true)
            // Letterboxd ne connaît que des films : rien à reprendre en mode séries.
            if (series) return Parsed(ImportSource.LETTERBOXD)
            val title = column("name")
            val year = column("year")
            val rating = column("rating")
            val date = column("watched date", "date")
            val watchlist = name == "watchlist.csv"
            val items = data.map { row ->
                ImportItem(
                    title = row.at(title),
                    year = row.at(year)?.toIntOrNull(),
                    watched = !watchlist,
                    watchlist = watchlist,
                    rating10 = row.at(rating)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.5..5.0 }?.times(2),
                    watchedAt = parseDate(row.at(date)).takeIf { !watchlist },
                )
            }
            return Parsed(ImportSource.LETTERBOXD, items)
        }

        if ("const" in header && ("your rating" in header || "position" in header)) {
            val id = column("const")
            val title = column("title")
            val original = column("original title")
            val year = column("year")
            val type = column("title type")
            val rating = column("your rating")
            val date = column("date rated")
            val watchlist = "position" in header
            val items = data.mapNotNull { row ->
                val kind = row.at(type)?.lowercase(Locale.ROOT)?.replace(" ", "")
                // Films (film, téléfilm, vidéo) ou séries (série, mini-série) selon le monde.
                val wanted = if (series) setOf("tvseries", "tvminiseries") else setOf("movie", "tvmovie", "video")
                if (if (series) kind !in wanted else kind != null && kind !in wanted) return@mapNotNull null
                val score = row.at(rating)?.toDoubleOrNull()?.takeIf { it in 1.0..10.0 }
                val watched = !watchlist || score != null
                ImportItem(
                    title = row.at(original) ?: row.at(title),
                    year = row.at(year)?.toIntOrNull(),
                    imdbId = row.at(id)?.takeIf { it.startsWith("tt") },
                    watched = watched,
                    watchlist = !watched,
                    rating10 = score,
                    watchedAt = parseDate(row.at(date)).takeIf { watched },
                )
            }
            return Parsed(ImportSource.IMDB, items)
        }

        // CSV générique : au moins un titre ou un identifiant.
        val title = column("title", "titre", "name", "nom", "original title", "titre original", "film", "movie")
        val tmdb = column("tmdb", "tmdb_id", "tmdbid", "tmdb id", "id tmdb")
        val imdb = column("imdb", "imdb_id", "imdbid", "imdb id", "const")
        if (title < 0 && tmdb < 0 && imdb < 0) return Parsed(null)
        val year = column("year", "année", "annee", "release year", "année de sortie", "date de sortie")
        val rating = column("rating", "note", "your rating", "ma note", "rating10", "score")
        val date = column("watched date", "watched_at", "date", "date vue", "vu le", "watched")
        val status = column("status", "statut", "list", "liste", "type")
        val scores = data.mapNotNull { it.at(rating)?.replace(',', '.')?.toDoubleOrNull() }
        val scale = when {
            scores.isEmpty() -> 1.0
            scores.max() <= 5.0 -> 2.0
            scores.max() <= 10.0 -> 1.0
            scores.max() <= 20.0 -> 0.5
            else -> 0.1
        }
        val items = data.map { row ->
            val state = row.at(status)?.let(::normalize).orEmpty()
            val wanted = state.contains("watchlist") || state.contains("envie") || state.contains("a voir") || state.contains("plan to")
            ImportItem(
                title = row.at(title),
                year = row.at(year)?.take(4)?.toIntOrNull(),
                tmdbId = row.at(tmdb)?.toLongOrNull(),
                imdbId = row.at(imdb)?.takeIf { it.startsWith("tt") },
                watched = !wanted,
                watchlist = wanted,
                rating10 = row.at(rating)?.replace(',', '.')?.toDoubleOrNull()?.times(scale)?.takeIf { it > 0.0 && it <= 10.0 },
                watchedAt = parseDate(row.at(date)).takeIf { !wanted },
            )
        }
        return Parsed(ImportSource.CSV, items)
    }

    private fun backupItem(film: io.github.deedg.cineswipe.data.repository.ExportFilm): ImportItem? = when (film.status) {
        "WATCHED" -> ImportItem(title = film.title, year = film.year, tmdbId = film.tmdbId, watched = true, watchedAt = film.watchedAt)
        "WATCHLIST" -> ImportItem(title = film.title, year = film.year, tmdbId = film.tmdbId, watchlist = true)
        else -> null
    }

    private val KNOWN_COLUMNS = setOf("letterboxd uri", "const", "title", "titre", "name", "tmdb", "tmdb_id", "imdb", "imdb_id")

    private val DATE_FORMATS = listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy/MM/dd").map { DateTimeFormatter.ofPattern(it) }

    fun parseDate(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseInstant(value)?.let { return it }
        val day = value.take(10)
        return DATE_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { LocalDate.parse(day, format).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    private fun parseInstant(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Titre comparable : sans accents, ponctuation ni casse. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}

/** Lecture CSV (guillemets, retours à la ligne dans un champ, séparateur « , », « ; » ou tabulation). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val separator = listOf(',', ';', '\t').maxBy { candidate -> firstLine.count { it == candidate } }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                !quoted && c == separator -> {
                    row += field.toString()
                    field.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString()
                    field.clear()
                    rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
