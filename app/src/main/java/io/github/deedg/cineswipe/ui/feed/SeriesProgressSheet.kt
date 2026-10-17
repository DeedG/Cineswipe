package io.github.deedg.cineswipe.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.SeasonRequest
import io.github.deedg.cineswipe.ui.detail.SeriesProgressSection
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

object SeriesProgressSheetTestTags {
    const val SHEET = "series_progress_sheet"
    const val DONE = "series_progress_done"
}

/**
 * Série marquée vue d'un swipe : où en est l'utilisateur ? Chaque choix est enregistré tout de
 * suite (finie par défaut, comme sur la fiche) ; fermer la feuille garde le dernier choix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesProgressSheet(
    request: SeasonRequest,
    onChange: (SeriesProgress, Int?) -> Unit,
    onDone: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var progress by rememberSaveable(request.tmdbId) { mutableStateOf<SeriesProgress?>(null) }
    var season by rememberSaveable(request.tmdbId) { mutableStateOf<Int?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = sheetState,
        containerColor = CineColors.Surface,
        modifier = Modifier.testTag(SeriesProgressSheetTestTags.SHEET),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.series_progress_sheet_title, request.title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
            Text(stringResource(R.string.series_progress_sheet_body), color = CineColors.TextSecondary, fontSize = 14.sp)
            SeriesProgressSection(
                user = UserMovieEntity(
                    tmdbId = request.tmdbId,
                    status = WatchStatus.WATCHED,
                    updatedAt = 0,
                    progress = progress,
                    currentSeason = season,
                ),
                seasonCount = request.seasonCount,
                onChange = { newProgress, newSeason ->
                    progress = newProgress
                    season = newSeason
                    onChange(newProgress, newSeason)
                },
            )
            CineButton(
                text = stringResource(R.string.series_progress_sheet_done),
                icon = CineIcons.Check,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag(SeriesProgressSheetTestTags.DONE),
            )
        }
    }
}
