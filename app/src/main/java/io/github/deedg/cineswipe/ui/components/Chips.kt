package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Puce de filtre aux couleurs CineSwipe : rouge quand elle est choisie. */
@Composable
fun CineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CineColors.SurfaceHigh,
            labelColor = CineColors.TextSecondary,
            selectedContainerColor = CineColors.Red.copy(alpha = 0.15f),
            selectedLabelColor = CineColors.Red,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = CineColors.BorderStrong,
            selectedBorderColor = CineColors.Red,
        ),
    )
}
