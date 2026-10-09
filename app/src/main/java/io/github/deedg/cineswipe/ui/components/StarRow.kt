package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Cinq étoiles, demi-étoiles comprises. Atténuées quand le classement est provisoire. */
@Composable
fun StarRow(
    stars: Float,
    modifier: Modifier = Modifier,
    starSize: Dp = 14.dp,
    color: Color = CineColors.Red,
    provisional: Boolean = false,
) {
    val provisionalLabel = stringResource(R.string.stars_provisional)
    val description = if (provisional) "$stars / 5, $provisionalLabel" else "$stars / 5"
    Row(
        modifier = modifier
            .alpha(if (provisional) 0.5f else 1f)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until 5) {
            val fill = (stars - index).coerceIn(0f, 1f)
            Box(Modifier.size(starSize)) {
                Icon(CineIcons.StarOutline, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
                if (fill > 0f) {
                    Icon(
                        CineIcons.StarFilled,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                            },
                    )
                }
            }
        }
    }
}
