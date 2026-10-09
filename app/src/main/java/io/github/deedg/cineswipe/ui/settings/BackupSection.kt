package io.github.deedg.cineswipe.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.deedg.cineswipe.data.backup.BackupResult
import io.github.deedg.cineswipe.data.repository.BackupStatus
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupTestTags {
    const val CHOOSE = "backup_choose"
    const val NOW = "backup_now"
    const val DISABLE = "backup_disable"
    const val STATUS = "backup_status"
}

data class BackupUiState(
    val folder: String? = null,
    val folderName: String? = null,
    val status: BackupStatus = BackupStatus(),
    val running: Boolean = false,
)

class BackupViewModel(private val backup: BackupRepository) : ViewModel() {
    private val running = MutableStateFlow(false)
    private val results = Channel<BackupResult>(Channel.BUFFERED)
    val events: Flow<BackupResult> = results.receiveAsFlow()

    private val folder = backup.folder.map { folder -> folder to folder?.let(backup::folderName) }.flowOn(Dispatchers.IO)

    val state: StateFlow<BackupUiState> = combine(folder, backup.status, running) { (folder, name), status, running ->
        BackupUiState(folder, name, status, running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun choose(folder: String) = run { backup.setFolder(folder) }

    fun backupNow() = run { backup.backupNow() }

    fun disable() {
        viewModelScope.launch { backup.setFolder(null) }
    }

    private fun run(block: suspend () -> BackupResult) {
        if (running.value) return
        running.value = true
        viewModelScope.launch {
            try {
                results.send(block())
            } finally {
                running.value = false
            }
        }
    }
}

/** Sauvegarde automatique, dans les réglages (sous « Mes données »). */
@Composable
fun BackupSectionRoute() {
    val viewModel = cineViewModel { BackupViewModel(it.backup) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val done = stringResource(R.string.backup_done_toast)
    val failed = stringResource(R.string.backup_failed_toast)
    val skipped = stringResource(R.string.backup_skipped_empty)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { result ->
            when (result) {
                BackupResult.DONE -> toaster.show(done)
                BackupResult.FAILED -> toaster.show(failed)
                BackupResult.DISABLED -> Unit
                BackupResult.SKIPPED -> toaster.show(skipped)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.choose(uri.toString())
    }
    BackupSection(
        state = state,
        onChoose = { runCatching { picker.launch(null) } },
        onBackupNow = viewModel::backupNow,
        onDisable = viewModel::disable,
    )
}

@Composable
fun BackupSection(state: BackupUiState, onChoose: () -> Unit, onBackupNow: () -> Unit, onDisable: () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Upload, stringResource(R.string.backup_title))
            if (state.folder == null) {
                BodyText(stringResource(R.string.backup_body_off))
                CineButton(
                    text = stringResource(R.string.backup_choose),
                    onClick = onChoose,
                    modifier = Modifier.fillMaxWidth().testTag(BackupTestTags.CHOOSE),
                )
                return@Column
            }
            BodyText(stringResource(R.string.backup_body_on, state.folderName ?: stringResource(R.string.backup_folder_unknown)))
            val status = state.status
            val error = status.error
            val lastAt = status.lastAt
            val (text, color) = when {
                state.running -> stringResource(R.string.backup_running) to CineColors.TextSecondary
                error == BackupRepository.ERROR_ACCESS -> stringResource(R.string.backup_error_access) to CineColors.Red
                error == BackupRepository.ERROR_EMPTY -> stringResource(R.string.backup_skipped_empty) to CineColors.TextSecondary
                error != null -> stringResource(R.string.backup_error, error) to CineColors.Red
                lastAt != null -> stringResource(R.string.backup_last, formatDate(lastAt)) to Color.White
                else -> stringResource(R.string.backup_never) to CineColors.TextSecondary
            }
            Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(BackupTestTags.STATUS))
            CineSecondaryButton(
                text = stringResource(R.string.backup_now),
                icon = CineIcons.Upload,
                onClick = onBackupNow,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth().testTag(BackupTestTags.NOW),
            )
            CineSecondaryButton(
                text = stringResource(R.string.backup_change),
                onClick = onChoose,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            )
            BodyText(stringResource(R.string.backup_restore_hint))
            TextButton(onClick = onDisable, modifier = Modifier.testTag(BackupTestTags.DISABLE)) {
                Text(stringResource(R.string.backup_disable), color = CineColors.TextMuted)
            }
        }
    }
}

private fun formatDate(at: Long): String = SimpleDateFormat("EEEE d MMMM 'à' HH:mm", Locale.FRANCE).format(Date(at))
