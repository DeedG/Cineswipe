package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import java.util.Locale
import kotlin.math.roundToInt

/** Badge de statut (« VU », « DISPO »…), majuscules espacées comme en V4. */
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 5.dp else 10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.7f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 12.dp else 15.dp))
        }
        Text(
            text = text.uppercase(Locale.FRENCH),
            color = color,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
    }
}

/** Pilule de note (« TMDB 7.8 »). */
@Composable
fun RatingPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = CineColors.TmdbBlue,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xCC1E1E1E))
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = labelColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Trophée + classement Elo arrondi. */
@Composable
fun EloBadge(
    rating: Double,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = if (large) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CineIcons.Trophy, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(if (large) 16.dp else 14.dp))
        Text(
            text = stringResource(R.string.elo_value, rating.roundToInt()),
            color = Color.White,
            fontSize = if (large) 17.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
