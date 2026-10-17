package io.github.deedg.cineswipe.domain.taste

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Profil de goût échangé entre amis (QR code ou texte) : un prénom, des titres vus avec leurs
 * étoiles (en demi-étoiles, 1 à 10) et la liste À voir. Assez court pour un QR code lisible.
 */
data class TasteCode(
    val name: String,
    val series: Boolean,
    /** Identifiant TMDB → demi-étoiles (1 = ½★, 10 = 5★). */
    val ratings: Map<Long, Int>,
    val watchlist: List<Long>,
) {
    fun encode(): String {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(if (series) 1 else 0)
        val nameBytes = name.take(MAX_NAME).toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, MAX_NAME_BYTES)) }
        out.write(nameBytes.size)
        out.write(nameBytes)
        writeVarint(out, ratings.size.toLong())
        // Identifiants triés, écrits en écarts : bien plus courts.
        var previous = 0L
        ratings.toSortedMap().forEach { (id, halfStars) ->
            writeVarint(out, id - previous)
            out.write(halfStars.coerceIn(1, 10))
            previous = id
        }
        writeVarint(out, watchlist.size.toLong())
        previous = 0L
        watchlist.distinct().sorted().forEach { id ->
            writeVarint(out, id - previous)
            previous = id
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(out.toByteArray())
        deflater.finish()
        val buffer = ByteArray(8192)
        val compressed = ByteArrayOutputStream()
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray())
    }

    companion object {
        /** Début de tout code CineSwipe (pour le reconnaître dans un texte collé). */
        const val PREFIX = "CINESWIPE1:"
        private const val VERSION = 1
        private const val MAX_NAME = 24
        private const val MAX_NAME_BYTES = 60

        /** Le code dans un texte (QR code, message collé), ou null s'il n'y en a pas ou qu'il est abîmé. */
        fun decode(text: String): TasteCode? = runCatching {
            val start = text.indexOf(PREFIX).takeIf { it >= 0 } ?: return null
            val payload = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            val inflater = Inflater()
            inflater.setInput(Base64.getUrlDecoder().decode(payload))
            val buffer = ByteArray(8192)
            val raw = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                raw.write(buffer, 0, count)
            }
            inflater.end()
            val bytes = raw.toByteArray()
            var index = 0
            fun byte(): Int = bytes[index++].toInt() and 0xFF
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = byte()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            if (byte() != VERSION) return null
            val series = byte() == 1
            val nameLength = byte()
            val name = String(bytes, index, nameLength, Charsets.UTF_8)
            index += nameLength
            val ratings = LinkedHashMap<Long, Int>()
            var id = 0L
            repeat(varint().toInt()) {
                id += varint()
                ratings[id] = byte().coerceIn(1, 10)
            }
            val watchlist = ArrayList<Long>()
            id = 0L
            repeat(varint().toInt()) {
                id += varint()
                watchlist += id
            }
            TasteCode(name, series, ratings, watchlist)
        }.getOrNull()

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var rest = value
            while (rest >= 0x80) {
                out.write(((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
            out.write(rest.toInt())
        }

        /** Étoiles (0,5 à 5) en demi-étoiles. */
        fun halfStars(stars: Float): Int = (stars * 2).toInt().coerceIn(1, 10)
    }
}

/** Comparaison de deux profils de goût. */
data class TasteMatch(
    /** Compatibilité en pour cent, ou null s'il y a trop peu de titres en commun. */
    val score: Int?,
    val common: Int,
    /** Adorés tous les deux (les deux à 4★ ou plus). */
    val agreements: List<Long>,
    /** Plus grands écarts d'étoiles (au moins 2★). */
    val disagreements: List<Long>,
    /** Dans les deux listes À voir. */
    val bothWant: List<Long>,
    /** L'ami a adoré, je ne l'ai pas vu. */
    val theyLoved: List<Long>,
    /** L'ami veut le voir, je l'ai adoré : à lui conseiller. */
    val toRecommend: List<Long>,
) {
    companion object {
        const val MIN_COMMON = 3

        /** Prudence : quelques titres « neutres » en plus ramènent un petit échantillon vers 50 %. */
        private const val SHRINK = 3.0

        /**
         * [mine] : mes titres vus et leurs demi-étoiles ; [myWatchlist] ; [myOthers] : titres que je
         * connais déjà autrement (écartés), à ne pas me proposer.
         */
        fun compute(friend: TasteCode, mine: Map<Long, Int>, myWatchlist: Set<Long>, myOthers: Set<Long> = emptySet()): TasteMatch {
            val common = friend.ratings.keys.filter { it in mine }
            val similarities = common.map { id -> 1.0 - kotlin.math.abs(friend.ratings.getValue(id) - mine.getValue(id)) / 9.0 }
            val score = if (common.size < MIN_COMMON) null else ((similarities.sum() + SHRINK * 0.5) / (common.size + SHRINK) * 100).toInt()
            val agreements = common.filter { friend.ratings.getValue(it) >= 8 && mine.getValue(it) >= 8 }
                .sortedByDescending { friend.ratings.getValue(it) + mine.getValue(it) }
            val disagreements = common.filter { kotlin.math.abs(friend.ratings.getValue(it) - mine.getValue(it)) >= 4 }
                .sortedByDescending { kotlin.math.abs(friend.ratings.getValue(it) - mine.getValue(it)) }
            val known = mine.keys + myWatchlist + myOthers
            val theyLoved = friend.ratings.filter { (id, half) -> half >= 8 && id !in known }.entries
                .sortedByDescending { it.value }.map { it.key }
            return TasteMatch(
                score = score,
                common = common.size,
                agreements = agreements.take(MAX_LISTED),
                disagreements = disagreements.take(MAX_DISAGREEMENTS),
                bothWant = friend.watchlist.filter { it in myWatchlist }.take(MAX_LISTED),
                theyLoved = theyLoved.take(MAX_LISTED),
                toRecommend = friend.watchlist.filter { (mine[it] ?: 0) >= 8 }.sortedByDescending { mine[it] }.take(MAX_LISTED),
            )
        }

        private const val MAX_LISTED = 12
        private const val MAX_DISAGREEMENTS = 5
    }
}
