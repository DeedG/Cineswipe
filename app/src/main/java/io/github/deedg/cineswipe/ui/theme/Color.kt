package io.github.deedg.cineswipe.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.deedg.cineswipe.data.model.MediaKind

/**
 * Couleur d'accent d'un monde : le rouge des films, le violet des séries. [CineColors.Red] et ses
 * variantes suivent l'accent en service, qui peut glisser d'une couleur à l'autre (switch).
 */
data class Accent(val main: Color, val light: Color, val deep: Color) {
    companion object {
        val Films = Accent(main = Color(0xFFED1C24), light = Color(0xFFFF4B52), deep = Color(0xFF1A0505))
        val Series = Accent(main = Color(0xFF7C3AED), light = Color(0xFF9A66F2), deep = Color(0xFF0F0A1F))

        fun of(kind: MediaKind): Accent = if (kind.isSeries) Series else Films

        /** Mélange de deux accents (fondu du switch), [fraction] de 0 (départ) à 1 (arrivée). */
        fun lerp(from: Accent, to: Accent, fraction: Float): Accent = Accent(
            main = androidx.compose.ui.graphics.lerp(from.main, to.main, fraction),
            light = androidx.compose.ui.graphics.lerp(from.light, to.light, fraction),
            deep = androidx.compose.ui.graphics.lerp(from.deep, to.deep, fraction),
        )
    }
}

/** Accent en service (lu par la composition : un changement redessine tout ce qui l'utilise). */
object CineAccent {
    var current: Accent by mutableStateOf(Accent.Films)
}

/** Couleurs de la DA CineSwipe (reprises de la V4). */
object CineColors {
    val Black = Color(0xFF000000)

    /** Accent du monde en service : rouge (films) ou violet (séries). */
    val Red: Color get() = CineAccent.current.main
    val RedLight: Color get() = CineAccent.current.light
    val RedDeep: Color get() = CineAccent.current.deep
    val Surface = Color(0xFF111111)
    val SurfaceHigh = Color(0xFF1A1A1A)
    val Card = Color(0xFF121212)
    val Border = Color(0xFF222222)
    val BorderStrong = Color(0xFF333333)
    val BorderSoft = Color(0xFF444444)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSoft = Color(0xFFDDDDDD)
    val TextSecondary = Color(0xFFAAAAAA)
    val TextMuted = Color(0xFF888888)
    val TextFaint = Color(0xFF666666)
    val SwipeRight = Color(0xFF125B22)
    val SwipeLeft = Color(0xFF003670)
    val Warning = Color(0xFFF57C00)
    val Success = Color(0xFF4CAF50)
    val TmdbBlue = Color(0xFF01B4E4)
    val Lightning = Color(0xFFFFC107)
    val Glass = Color(0x99000000)
    val NavBar = Color(0xF20A0A0A)
    val Toast = Color(0xF2141414)
}
