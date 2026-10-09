package io.github.deedg.cineswipe.ui.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.domain.StarScale
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlin.math.roundToInt

/** Explication du classement : calcul, choix des matchs, barème des étoiles et fiabilité. */
@Composable
fun EloInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CineColors.Surface,
        title = {
            Text(stringResource(R.string.elo_info_title), color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.elo_info_body), color = CineColors.TextSecondary, fontSize = 14.sp)
                Text(stringResource(R.string.elo_info_matches), color = CineColors.TextSecondary, fontSize = 14.sp)
                Text(stringResource(R.string.elo_info_buttons), color = CineColors.TextSecondary, fontSize = 14.sp)
                Text(
                    stringResource(R.string.elo_info_scale_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Color(0xFF0D0D0D))
                        .border(1.dp, CineColors.BorderStrong, shape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StarScale.buckets.forEach { (share, stars) ->
                        ScaleRow(label = stringResource(R.string.elo_info_top, (share * 100).roundToInt()), stars = stars)
                    }
                    ScaleRow(label = stringResource(R.string.elo_info_rest), stars = 1f)
                }
                Text(stringResource(R.string.elo_info_provisional), color = CineColors.TextMuted, fontSize = 13.sp)
                Text(stringResource(R.string.elo_info_reliability), color = CineColors.TextMuted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.elo_info_ok), color = CineColors.Red, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun ScaleRow(label: String, stars: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CineColors.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StarRow(stars = stars)
    }
}
