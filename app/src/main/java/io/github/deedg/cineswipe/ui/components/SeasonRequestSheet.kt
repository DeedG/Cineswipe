package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Une série à demander : son nom et son nombre de saisons (null s'il est inconnu). */
data class SeasonRequest(val tmdbId: Long, val title: String, val seasonCount: Int?)

object SeasonRequestTestTags {
    const val SHEET = "season_request_sheet"
    const val ALL = "season_request_all"
    const val CONFIRM = "season_request_confirm"
    const val LATER = "season_request_later"
    fun season(number: Int) = "season_request_$number"
}

/**
 * Demande d'une série à Seerr ou Sonarr : toute la série (par défaut) ou quelques saisons.
 * « Juste à voir » la laisse dans la liste sans rien demander. [position] : « 2 / 5 » quand
 * plusieurs séries attendent leur réponse (recensement).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeasonRequestSheet(
    request: SeasonRequest,
    service: HomelabService,
    onRequest: (seasons: List<Int>?) -> Unit,
    onLater: () -> Unit,
    position: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saisons cochées ; vide = toute la série.
    var chosen by rememberSaveable(request.tmdbId) { mutableStateOf(emptyList<Int>()) }
    ModalBottomSheet(
        onDismissRequest = onLater,
        sheetState = sheetState,
        containerColor = CineColors.Surface,
        modifier = Modifier.testTag(SeasonRequestTestTags.SHEET),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            position?.let { Text(it, color = CineColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Text(
                stringResource(R.string.season_request_title, request.title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
            Text(stringResource(R.string.season_request_body, service.displayName), color = CineColors.TextSecondary, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CineChip(
                    label = stringResource(R.string.season_request_all),
                    selected = chosen.isEmpty(),
                    onClick = { chosen = emptyList() },
                    modifier = Modifier.testTag(SeasonRequestTestTags.ALL),
                )
                (1..(request.seasonCount ?: 0)).forEach { number ->
                    val selected = number in chosen
                    CineChip(
                        label = stringResource(R.string.series_season_short, number),
                        selected = selected,
                        onClick = { chosen = if (selected) chosen - number else (chosen + number).sorted() },
                        modifier = Modifier.testTag(SeasonRequestTestTags.season(number)),
                    )
                }
            }
            if (request.seasonCount == null) {
                Text(stringResource(R.string.season_request_unknown), color = CineColors.TextMuted, fontSize = 13.sp)
            }
            CineButton(
                text = if (chosen.isEmpty()) {
                    stringResource(R.string.season_request_confirm_all, service.displayName)
                } else {
                    pluralStringResource(R.plurals.season_request_confirm_some, chosen.size, chosen.size, service.displayName)
                },
                icon = CineIcons.Download,
                onClick = { onRequest(chosen.takeIf { it.isNotEmpty() }) },
                modifier = Modifier.fillMaxWidth().testTag(SeasonRequestTestTags.CONFIRM),
            )
            Row(Modifier.fillMaxWidth()) {
                CineSecondaryButton(
                    text = stringResource(R.string.season_request_later),
                    icon = CineIcons.Bookmark,
                    onClick = onLater,
                    modifier = Modifier.fillMaxWidth().testTag(SeasonRequestTestTags.LATER),
                )
            }
        }
    }
}
