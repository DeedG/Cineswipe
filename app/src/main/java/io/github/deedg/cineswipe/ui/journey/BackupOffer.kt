package io.github.deedg.cineswipe.ui.journey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.backup.BackupRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object BackupOfferTestTags {
    const val SHEET = "backup_offer"
    const val CHOOSE = "backup_offer_choose"
    const val LATER = "backup_offer_later"
}

/**
 * Proposée une fois, quand les recos s'ouvrent pour la première fois : une sauvegarde automatique,
 * pour ne pas perdre tout ce qui vient d'être recensé et classé (changement de téléphone…).
 */
class BackupOfferViewModel(
    private val settings: SettingsRepository,
    private val backup: BackupRepository,
    enabled: Boolean,
) : ViewModel() {
    val show: StateFlow<Boolean> = if (!enabled) {
        flowOf(false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    } else {
        combine(settings.backupOfferSeen, backup.folder) { seen, folder -> !seen && folder == null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    fun choose(folder: String) {
        viewModelScope.launch {
            settings.setBackupOfferSeen()
            backup.setFolder(folder)
        }
    }

    fun later() {
        viewModelScope.launch { settings.setBackupOfferSeen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupOffer() {
    val viewModel = cineViewModel(key = "backup-offer") { BackupOfferViewModel(it.settings, it.backup, enabled = it.offersBackup) }
    val show by viewModel.show.collectAsStateWithLifecycle()
    if (!show) return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.choose(uri.toString())
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::later,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CineColors.Surface,
        modifier = Modifier.testTag(BackupOfferTestTags.SHEET),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CineIcons.Download, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(26.dp))
                Text(
                    stringResource(R.string.backup_offer_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(stringResource(R.string.backup_offer_body), color = CineColors.TextSoft, fontSize = 15.sp, lineHeight = 21.sp)
            CineButton(
                text = stringResource(R.string.backup_choose),
                onClick = { runCatching { picker.launch(null) } },
                modifier = Modifier.fillMaxWidth().testTag(BackupOfferTestTags.CHOOSE),
            )
            TextButton(
                onClick = viewModel::later,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag(BackupOfferTestTags.LATER),
            ) {
                Text(stringResource(R.string.backup_offer_later), color = CineColors.TextMuted, fontSize = 14.sp)
            }
        }
    }
}
