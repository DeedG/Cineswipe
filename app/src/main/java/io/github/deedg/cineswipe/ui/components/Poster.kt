package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.TmdbImages
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Filtre noir et blanc (perdant de l'Arène). */
val GrayscaleFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Affiche TMDB. Sans image, un fond sombre et une icône remplacent l'ancienne image de
 * secours de la V4 (via.placeholder.com, hors service).
 */
@Composable
fun PosterImage(
    path: String?,
    imageSize: TmdbImageSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    colorFilter: ColorFilter? = null,
    alpha: Float = 1f,
) {
    val url = TmdbImages.url(path, imageSize)
    if (url == null) {
        Box(modifier.background(CineColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(CineIcons.Film, contentDescription = contentDescription, tint = CineColors.TextFaint, modifier = Modifier.size(28.dp))
        }
    } else {
        val placeholder = remember { ColorPainter(CineColors.SurfaceHigh) }
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            placeholder = placeholder,
            error = placeholder,
            contentScale = contentScale,
            colorFilter = colorFilter,
            alpha = alpha,
        )
    }
}

/**
 * Affiche floutée et assombrie en fond d'écran (`.poster-blur-bg` en V4). Agrandie pour masquer
 * les bords du flou, puis coupée à son cadre : sans cela, le fond d'une carte débordait sur la
 * carte précédente du feed.
 */
@Composable
fun BlurredBackdrop(
    path: String?,
    modifier: Modifier = Modifier,
    dim: Float = 0.6f,
) {
    Box(modifier.clipToBounds().background(CineColors.Black)) {
        val url = TmdbImages.url(path, TmdbImageSize.Thumb)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }
                    .blur(35.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}
