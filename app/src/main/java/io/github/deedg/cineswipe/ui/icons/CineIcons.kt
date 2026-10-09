package io.github.deedg.cineswipe.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes au trait de la V4 (style Feather), redessinées en vecteurs Compose.
 * La couleur réelle vient du `tint` de `Icon`.
 */
object CineIcons {
    val Recos: ImageVector by lazy {
        stroke("recos", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M17 2l-5 5-5-5")
    }
    val Arena: ImageVector by lazy { stroke("arena", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z") }
    val Search: ImageVector by lazy { stroke("search", "M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "M21 21l-4.35-4.35") }
    val Discover: ImageVector by lazy { stroke("discover", "M22 3H2l8 9.46V19l4 2v-8.54L22 3z") }
    val Profile: ImageVector by lazy {
        stroke("profile", "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M16 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0z")
    }
    val Eye: ImageVector by lazy {
        stroke("eye", "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    }
    val EyeOff: ImageVector by lazy {
        stroke(
            "eye_off",
            "M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24",
            "M1 1l22 22",
        )
    }
    val Server: ImageVector by lazy {
        stroke("server", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8", "M12 17v4")
    }
    val Upload: ImageVector by lazy {
        stroke("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12")
    }
    val Download: ImageVector by lazy {
        stroke("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
    }
    val ChevronRight: ImageVector by lazy { stroke("chevron_right", "M9 18l6-6-6-6") }
    val Link: ImageVector by lazy {
        stroke("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    }
    val Trophy: ImageVector by lazy { stroke("trophy", "M8 21h8M12 17v4M7 4h10M5 4h14v5a7 7 0 0 1-14 0V4z") }
    val Info: ImageVector by lazy {
        stroke("info", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 16v-4", "M12 8h.01")
    }
    val InfoSmall: ImageVector by lazy { stroke("info_small", "M12 16v-5", "M12 8h.01") }
    val Undo: ImageVector by lazy { stroke("undo", "M3 7v6h6", "M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13") }
    val Skip: ImageVector by lazy { stroke("skip", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val Close: ImageVector by lazy { stroke("close", "M18 6L6 18", "M6 6l12 12") }
    val Back: ImageVector by lazy { stroke("back", "M19 12H5", "M12 19l-7-7 7-7") }
    val Check: ImageVector by lazy { stroke("check", "M20 6L9 17l-5-5") }
    val Settings: ImageVector by lazy { stroke("settings", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", GEAR_PATH) }
    val Bookmark: ImageVector by lazy { stroke("bookmark", "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z") }
    val Film: ImageVector by lazy {
        stroke("film", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 3v18")
    }
    val Play: ImageVector by lazy { filled("play", "M5 3l14 9-14 9V3z") }
    val Lightning: ImageVector by lazy { filled("lightning", "M13 2L3 14h9l-1 8 10-12h-9l1-8z") }
    val ListIcon: ImageVector by lazy {
        stroke("list", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    }
    val Plus: ImageVector by lazy { stroke("plus", "M12 5v14", "M5 12h14") }
    val Edit: ImageVector by lazy { stroke("edit", "M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z") }
    val Shuffle: ImageVector by lazy { stroke("shuffle", "M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5") }
    val Calendar: ImageVector by lazy {
        stroke("calendar", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M16 2v4", "M8 2v4", "M3 10h18")
    }
    val Bell: ImageVector by lazy { stroke("bell", "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0") }
    val Trash: ImageVector by lazy {
        stroke("trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }
    val Alert: ImageVector by lazy {
        stroke(
            "alert",
            "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
            "M12 9v4",
            "M12 17h.01",
        )
    }
    val ThumbsDown: ImageVector by lazy {
        stroke(
            "thumbs_down",
            "M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z",
            "M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17",
        )
    }
    val Refresh: ImageVector by lazy { stroke("refresh", "M23 4v6h-6", "M20.49 15a9 9 0 1 1-2.12-9.36L23 10") }
    val Equal: ImageVector by lazy { stroke("equal", "M5 9h14", "M5 15h14") }
    val Grid: ImageVector by lazy {
        stroke("grid", "M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z")
    }
    val Layers: ImageVector by lazy { stroke("layers", "M12 2L2 7l10 5 10-5-10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5") }
    val Help: ImageVector by lazy {
        stroke("help", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    }
    val Tool: ImageVector by lazy {
        stroke(
            "tool",
            "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z",
        )
    }
    val Share: ImageVector by lazy {
        stroke("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    }
    val Heart: ImageVector by lazy {
        stroke(
            "heart",
            "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z",
        )
    }
    val Award: ImageVector by lazy {
        stroke("award", "M19 8a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M8.21 13.89L7 23l5-3 5 3-1.21-9.12")
    }
    val Gem: ImageVector by lazy { stroke("gem", "M6 3h12l4 6-10 12L2 9z", "M2 9h20", "M12 21L8 9l2-6", "M12 21l4-12-2-6") }
    val TrendingUp: ImageVector by lazy { stroke("trending_up", "M23 6l-9.5 9.5-5-5L1 18", "M17 6h6v6") }
    val Hash: ImageVector by lazy { stroke("hash", "M4 9h16", "M4 15h16", "M10 3L8 21", "M16 3l-2 18") }
    val Clapper: ImageVector by lazy {
        stroke(
            "clapper",
            "M20.2 6L3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3z",
            "M6.2 5.3l3.1 3.9",
            "M12.4 3.4l3.1 4",
            "M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }
    val BarChart: ImageVector by lazy { stroke("bar_chart", "M12 20V10", "M18 20V4", "M6 20v-4") }
    val Moon: ImageVector by lazy { stroke("moon", "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z") }
    val Clock: ImageVector by lazy { stroke("clock", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 6v6l4 2") }
    val StarFilled: ImageVector by lazy { filled("star_filled", STAR_PATH) }
    val StarOutline: ImageVector by lazy { stroke("star_outline", STAR_PATH) }
}

private const val STAR_PATH =
    "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"

private const val GEAR_PATH =
    "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"

private fun builder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)

private fun stroke(name: String, vararg paths: String): ImageVector = builder(name).apply {
    paths.forEach { data ->
        addPath(
            pathData = addPathNodes(data),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

private fun filled(name: String, path: String): ImageVector = builder(name).apply {
    addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
}.build()
