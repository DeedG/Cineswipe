package io.github.deedg.cineswipe.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val CineColorScheme = darkColorScheme(
    primary = CineColors.Red,
    onPrimary = Color.White,
    primaryContainer = CineColors.RedDeep,
    onPrimaryContainer = Color.White,
    secondary = CineColors.TextSecondary,
    onSecondary = Color.Black,
    tertiary = CineColors.TmdbBlue,
    background = CineColors.Black,
    onBackground = Color.White,
    surface = CineColors.Surface,
    onSurface = Color.White,
    surfaceVariant = CineColors.SurfaceHigh,
    onSurfaceVariant = CineColors.TextSecondary,
    surfaceContainerLowest = CineColors.Black,
    surfaceContainerLow = CineColors.Card,
    surfaceContainer = CineColors.Surface,
    surfaceContainerHigh = CineColors.SurfaceHigh,
    surfaceContainerHighest = CineColors.Border,
    outline = CineColors.BorderStrong,
    outlineVariant = CineColors.Border,
    error = CineColors.Red,
    onError = Color.White,
)

private val CineTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold),
    )
}

private val CineShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Thème sombre unique, sans couleurs dynamiques : la DA rouge et noire prime. */
@Composable
fun CineSwipeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CineColorScheme,
        typography = CineTypography,
        shapes = CineShapes,
        content = content,
    )
}
