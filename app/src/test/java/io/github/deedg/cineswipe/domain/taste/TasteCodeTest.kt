package io.github.deedg.cineswipe.domain.taste

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TasteCodeTest {
    private val random = Random(7)

    /** Un profil plein : 130 titres notés et 40 à voir, comme le code envoyé par l'appli. */
    private fun fullProfile(series: Boolean = false) = TasteCode(
        name = "Alex",
        series = series,
        ratings = (1..130).associate { (random.nextLong(10, 1_200_000)) to random.nextInt(1, 11) },
        watchlist = (1..40).map { random.nextLong(10, 1_200_000) },
    )

    @Test
    fun aCodeSurvivesTheRoundTripAndFitsInAReadableQrCode() {
        val profile = fullProfile(series = true)
        val text = profile.encode()
        assertTrue(text.startsWith(TasteCode.PREFIX))
        // Assez court pour un QR code qu'un téléphone lit sur un autre écran.
        assertTrue("code de ${text.length} caractères", text.length < 1_400)
        assertEquals(profile.copy(watchlist = profile.watchlist.distinct().sorted()), TasteCode.decode("Regarde : $text merci"))

        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        val scale = 3
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { index -> if (matrix.get(index % side / scale, index / side / scale)) 0xFF000000.toInt() else -1 }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
        assertEquals(text, read)
    }

    @Test
    fun brokenCodesAreRefused() {
        assertNull(TasteCode.decode("Bonjour"))
        assertNull(TasteCode.decode(TasteCode.PREFIX + "pas-un-code"))
    }

    @Test
    fun theMatchComparesStarsAndListsWhatToWatch() {
        val friend = TasteCode(
            name = "Sam",
            series = false,
            ratings = mapOf(1L to 10, 2L to 9, 3L to 2, 4L to 8, 5L to 10, 6L to 9),
            watchlist = listOf(7L, 8L),
        )
        val mine = mapOf(1L to 10, 2L to 8, 3L to 9, 4L to 8, 8L to 9)
        val match = TasteMatch.compute(friend, mine, myWatchlist = setOf(7L), myOthers = setOf(6L))
        assertEquals(4, match.common)
        assertTrue(match.score!! in 60..90)
        assertEquals(listOf(1L, 2L, 4L), match.agreements)
        assertEquals(listOf(3L), match.disagreements)
        assertEquals(listOf(7L), match.bothWant)
        // 5 adoré par Sam, pas vu par moi ; 6 écarté : pas proposé.
        assertEquals(listOf(5L), match.theyLoved)
        assertEquals(listOf(8L), match.toRecommend)
        // Trop peu en commun : pas de score.
        assertNull(TasteMatch.compute(friend, mapOf(1L to 10), emptySet()).score)
    }
}
