package io.github.deedg.cineswipe.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.db.UserMovieEntity
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.theme.CineColors

object SeriesTestTags {
    const val INFO = "series_info"
    const val UNKNOWN_SEASON = "series_season_unknown"
    fun progress(progress: SeriesProgress) = "series_progress_${progress.name.lowercase()}"
    fun season(number: Int) = "series_season_$number"
}

/** Années de diffusion, nombre de saisons et durée d'un épisode, puis le statut de diffusion. */
@Composable
fun SeriesInfoLine(movie: MovieEntity) {
    val years = when {
        movie.year == null -> null
        movie.airStatus == "Returning Series" || movie.airStatus == "In Production" -> stringResource(R.string.series_years_running, movie.year)
        movie.endYear != null && movie.endYear != movie.year -> stringResource(R.string.series_years, movie.year, movie.endYear)
        else -> movie.year.toString()
    }
    val parts = listOfNotNull(
        years,
        movie.seasonCount?.let { pluralStringResource(R.plurals.series_seasons, it, it) },
        movie.runtimeMinutes?.let { stringResource(R.string.series_episode_runtime, it) },
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(SeriesTestTags.INFO)) {
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), color = CineColors.TextMuted, fontSize = 17.sp, textAlign = TextAlign.Center)
        airStatusLabel(movie.airStatus)?.let { Text(it, color = CineColors.RedLight, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun airStatusLabel(status: String?): String? = when (status) {
    "Returning Series" -> stringResource(R.string.series_status_returning)
    "Ended" -> stringResource(R.string.series_status_ended)
    "Canceled" -> stringResource(R.string.series_status_canceled)
    "In Production", "Planned", "Pilot" -> stringResource(R.string.series_status_upcoming)
    else -> null
}

/** Libellé court de l'avancement d'une série vue (« En cours · S3 », « Finie »…). */
@Composable
fun progressLabel(user: UserMovieEntity): String = when (user.progress) {
    SeriesProgress.IN_PROGRESS -> user.currentSeason?.let { stringResource(R.string.series_progress_in_season, it) }
        ?: stringResource(R.string.series_progress_in_progress)
    SeriesProgress.ABANDONED -> user.currentSeason?.let { stringResource(R.string.series_progress_abandoned_season, it) }
        ?: stringResource(R.string.series_progress_abandoned)
    SeriesProgress.EPISODES -> stringResource(R.string.series_progress_episodes)
    SeriesProgress.FINISHED, null -> stringResource(R.string.series_progress_finished)
}

/**
 * Où en est l'utilisateur : en cours, finie ou abandonnée, et la saison (en cours ou abandonnée).
 * Une série vue sans avancement précisé compte comme finie.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesProgressSection(
    user: UserMovieEntity,
    seasonCount: Int?,
    onChange: (SeriesProgress, Int?) -> Unit,
) {
    val progress = user.progress ?: SeriesProgress.FINISHED
    val lastSeason = maxOf(seasonCount ?: 0, user.currentSeason ?: 0, 1)
    val withSeason = progress == SeriesProgress.IN_PROGRESS || progress == SeriesProgress.ABANDONED
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.series_progress_title), color = CineColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        // Les quatre états tiennent sur deux lignes au besoin.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeriesProgress.entries.forEach { option ->
                val label = when (option) {
                    SeriesProgress.IN_PROGRESS -> R.string.series_progress_in_progress
                    SeriesProgress.FINISHED -> R.string.series_progress_finished
                    SeriesProgress.EPISODES -> R.string.series_progress_episodes
                    SeriesProgress.ABANDONED -> R.string.series_progress_abandoned
                }
                CineChip(
                    label = stringResource(label),
                    selected = option == progress,
                    // La saison connue est gardée ; sinon « Je ne sais plus ».
                    onClick = { onChange(option, user.currentSeason) },
                    modifier = Modifier.testTag(SeriesTestTags.progress(option)),
                )
            }
        }
        if (withSeason) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CineChip(
                    label = stringResource(R.string.series_season_unknown),
                    selected = user.currentSeason == null,
                    onClick = { onChange(progress, null) },
                    modifier = Modifier.testTag(SeriesTestTags.UNKNOWN_SEASON),
                )
                (1..lastSeason).forEach { number ->
                    CineChip(
                        label = stringResource(R.string.series_season_short, number),
                        selected = number == user.currentSeason,
                        onClick = { onChange(progress, number) },
                        modifier = Modifier.testTag(SeriesTestTags.season(number)),
                    )
                }
            }
        }
    }
}
