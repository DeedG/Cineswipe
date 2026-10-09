package io.github.deedg.cineswipe.ui.arena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.MovieDetails
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.components.BlurredBackdrop
import io.github.deedg.cineswipe.ui.components.EloBadge
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.RatingPill
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.theme.CineColors
import java.util.Locale

/** Fiche rapide d'un combattant, sans quitter l'Arène. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieInfoSheet(details: MovieDetails, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CineColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CineColors.BorderSoft) },
    ) {
        Box(Modifier.fillMaxWidth()) {
            BlurredBackdrop(path = details.movie.posterPath, modifier = Modifier.matchParentSize(), dim = 0.75f)
            MovieInfoContent(details)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieInfoContent(details: MovieDetails) {
    val movie = details.movie
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosterImage(
            path = movie.posterPath,
            imageSize = TmdbImageSize.Poster,
            contentDescription = stringResource(R.string.poster_description, movie.title),
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .aspectRatio(2f / 3f)
                .shadow(20.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            text = movie.year?.let { "${movie.title} ($it)" } ?: movie.title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp,
        )
        movie.director?.let {
            Text(
                text = stringResource(R.string.directed_by, it),
                color = CineColors.TextSecondary,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            details.user?.let { EloBadge(rating = it.rating, large = true) }
            movie.tmdbRating?.let {
                RatingPill(label = stringResource(R.string.tmdb_label), value = String.format(Locale.FRANCE, "%.1f", it))
            }
        }
        details.stars?.let { StarRow(stars = it, provisional = details.provisional, starSize = 20.dp) }
        Text(
            text = movie.overview ?: stringResource(R.string.detail_no_overview),
            color = CineColors.TextSoft,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
