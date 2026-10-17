package io.github.deedg.cineswipe.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.BuildConfig
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.ExportEffect
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.common.isSeriesWorld
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.components.WorldSwitch
import io.github.deedg.cineswipe.ui.demo.DemoRestartEffect
import io.github.deedg.cineswipe.ui.demo.demoViewModel
import io.github.deedg.cineswipe.ui.homelab.SeasonNotificationsSection
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.update.UpdateSection
import kotlinx.coroutines.launch

private const val TMDB_API_SETTINGS_URL = "https://www.themoviedb.org/settings/api"

/** Touchers sur le numéro de version qui débloquent les outils de diagnostic. */
private const val DEV_TOOLS_TAPS = 7

object SettingsTestTags {
    const val VERSION = "settings_version"
    const val DEV_TOOLS = "settings_dev_tools"
    const val EXPORT = "settings_export"
    const val IMPORT = "settings_import"
    const val DEMO_TRY = "settings_demo_try"
    const val DEMO_EXIT = "settings_demo_exit"
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenHomelab: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyGuide: () -> Unit = {},
    onOpenImport: () -> Unit = {},
) {
    val viewModel = cineViewModel { SettingsViewModel(it.settings, it.tmdb, it.library, it.crashLog, it.diagnostics, it.isDemo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val demo = demoViewModel()
    DemoRestartEffect(demo)
    val demoStarting by demo.starting.collectAsStateWithLifecycle()
    ExportEffect(viewModel.exports)
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenHomelab = onOpenHomelab,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenKeyGuide = onOpenKeyGuide,
        onOpenImport = onOpenImport,
        onSaveTmdbKey = viewModel::saveTmdbKey,
        onKeyCheckShown = viewModel::acknowledgeKeyCheck,
        onRemoveTmdbKey = viewModel::removeTmdbKey,
        onLanguageChange = viewModel::setLanguage,
        onReset = viewModel::reset,
        onExport = viewModel::exportData,
        onUnlockDevTools = viewModel::unlockDevTools,
        onClearCrash = viewModel::clearCrash,
        demoStarting = demoStarting,
        onStartDemo = demo::start,
        onStopDemo = demo::stop,
        backupSection = { BackupSectionRoute() },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenHomelab: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyGuide: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    onSaveTmdbKey: (String) -> Unit,
    onKeyCheckShown: () -> Unit,
    onRemoveTmdbKey: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onReset: (DataReset) -> Unit,
    onExport: () -> Unit,
    onUnlockDevTools: () -> Unit,
    onClearCrash: () -> Unit,
    demoStarting: Boolean = false,
    onStartDemo: () -> Unit = {},
    onStopDemo: () -> Unit = {},
    backupSection: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.settings_title),
            leading = {
                GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
            },
        )
        if (state.demo) {
            DemoSection(onStopDemo)
        } else {
            ModeSection()
            // Séries : prévenir des nouvelles saisons.
            if (isSeriesWorld()) SeasonNotificationsSection()
            TmdbSection(
                maskedKey = state.tmdbKeyMasked,
                builtinKey = state.builtinKey,
                keyCheck = state.keyCheck,
                onSave = onSaveTmdbKey,
                onKeyCheckShown = onKeyCheckShown,
                onRemove = onRemoveTmdbKey,
                onOpenGuide = onOpenKeyGuide,
            )
        }
        LanguageSection(state.language, onLanguageChange)
        if (!state.demo) HomelabSection(state.homelabReady, onOpenHomelab)
        DataSection(exporting = state.exporting, onExport = onExport, onImport = onOpenImport, onReset = onReset)
        if (!state.demo) backupSection()
        if (state.devTools) DevToolsSection(onOpenDiagnostics)
        CrashSection(state.crashLog, onClearCrash)
        if (!state.demo) UpdateSection()
        AboutSection(
            devTools = state.devTools,
            onUnlockDevTools = onUnlockDevTools,
            showDemo = !state.demo,
            demoStarting = demoStarting,
            onStartDemo = onStartDemo,
        )
    }
}

/** Pendant la démo : ce qu'elle est, et comment en sortir. */
@Composable
private fun DemoSection(onStop: () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Play, stringResource(R.string.settings_demo_title))
            BodyText(stringResource(R.string.settings_demo_body))
            CineButton(
                text = stringResource(R.string.settings_demo_exit),
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.DEMO_EXIT),
            )
        }
    }
}

/**
 * À propos ; sept touchers sur la version débloquent les outils de diagnostic. La démo se relance
 * d'ici.
 */
@Composable
private fun AboutSection(
    devTools: Boolean,
    onUnlockDevTools: () -> Unit,
    showDemo: Boolean,
    demoStarting: Boolean,
    onStartDemo: () -> Unit,
) {
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    var taps by remember { mutableIntStateOf(0) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(CineIcons.Info, stringResource(R.string.settings_about_title))
            Text(
                text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                color = CineColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .testTag(SettingsTestTags.VERSION)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (devTools) {
                            toaster.show(resources.getString(R.string.settings_dev_tools_already))
                            return@clickable
                        }
                        taps++
                        val left = DEV_TOOLS_TAPS - taps
                        when {
                            left <= 0 -> {
                                taps = 0
                                onUnlockDevTools()
                                toaster.show(resources.getString(R.string.settings_dev_tools_unlocked))
                            }
                            left <= 3 -> toaster.show(resources.getQuantityString(R.plurals.settings_dev_tools_taps_left, left, left))
                        }
                    },
            )
            BodyText(stringResource(R.string.settings_about_tmdb))
            if (showDemo) {
                TextButton(
                    onClick = onStartDemo,
                    enabled = !demoStarting,
                    modifier = Modifier.testTag(SettingsTestTags.DEMO_TRY),
                ) {
                    Text(
                        stringResource(if (demoStarting) R.string.settings_demo_starting else R.string.settings_demo_try),
                        color = CineColors.TmdbBlue,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Accès aux outils de diagnostic, une fois débloqués. */
@Composable
private fun DevToolsSection(onOpen: () -> Unit) {
    SectionCard(onClick = onOpen, modifier = Modifier.testTag(SettingsTestTags.DEV_TOOLS)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(CineIcons.Tool, stringResource(R.string.settings_dev_tools_title))
                BodyText(stringResource(R.string.settings_dev_tools_body))
            }
            Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TmdbSection(
    maskedKey: String?,
    builtinKey: Boolean,
    keyCheck: KeyCheckState,
    onSave: (String) -> Unit,
    onKeyCheckShown: () -> Unit,
    onRemove: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val toaster = LocalToaster.current
    val uriHandler = LocalUriHandler.current
    val savedMessage = stringResource(R.string.settings_tmdb_saved_toast)
    val removedMessage = stringResource(R.string.settings_tmdb_removed_toast)
    var input by rememberSaveable { mutableStateOf("") }
    val checking = keyCheck == KeyCheckState.CHECKING
    val save = {
        if (input.isNotBlank() && !checking) onSave(input)
    }
    LaunchedEffect(keyCheck) {
        when (keyCheck) {
            KeyCheckState.SAVED -> {
                input = ""
                toaster.show(savedMessage)
                onKeyCheckShown()
            }
            KeyCheckState.SAVED_UNVERIFIED -> input = ""
            else -> Unit
        }
    }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Film, stringResource(R.string.settings_tmdb_title))
            BodyText(stringResource(if (builtinKey) R.string.settings_tmdb_body_builtin else R.string.settings_tmdb_body))
            Text(
                text = when {
                    maskedKey != null -> stringResource(R.string.settings_tmdb_saved, maskedKey)
                    builtinKey -> stringResource(R.string.settings_tmdb_builtin)
                    else -> stringResource(R.string.settings_tmdb_missing)
                },
                color = if (maskedKey != null || builtinKey) CineColors.Red else CineColors.TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !checking,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_tmdb_hint), color = CineColors.TextFaint) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CineColors.SurfaceHigh,
                    unfocusedContainerColor = CineColors.SurfaceHigh,
                    disabledContainerColor = CineColors.SurfaceHigh,
                    focusedBorderColor = CineColors.Red,
                    unfocusedBorderColor = CineColors.BorderStrong,
                    cursorColor = CineColors.Red,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            when (keyCheck) {
                KeyCheckState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = CineColors.Red, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    BodyText(stringResource(R.string.settings_tmdb_checking))
                }
                KeyCheckState.REJECTED -> Text(
                    stringResource(R.string.settings_tmdb_rejected),
                    color = CineColors.Red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                KeyCheckState.SAVED_UNVERIFIED -> Text(
                    stringResource(R.string.settings_tmdb_unverified),
                    color = CineColors.Warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                KeyCheckState.IDLE, KeyCheckState.SAVED -> Unit
            }
            CineButton(
                text = stringResource(R.string.settings_tmdb_save),
                onClick = save,
                enabled = input.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
            )
            // Trois liens qui passent à la ligne s'ils ne tiennent pas (sinon le dernier s'écrase à la verticale).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenGuide) {
                    Text(stringResource(R.string.settings_tmdb_guide), color = CineColors.TmdbBlue, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(TMDB_API_SETTINGS_URL) } }) {
                    Text(stringResource(R.string.settings_tmdb_get_key), color = CineColors.TmdbBlue, fontWeight = FontWeight.Bold)
                }
                if (maskedKey != null) {
                    TextButton(onClick = {
                        onRemove()
                        toaster.show(removedMessage)
                    }) {
                        Text(stringResource(R.string.settings_tmdb_remove), color = CineColors.TextMuted)
                    }
                }
            }
        }
    }
}

/** Mode Films | Séries : deux mondes séparés. */
@Composable
private fun ModeSection() {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Layers, stringResource(R.string.settings_mode_title))
            BodyText(stringResource(R.string.settings_mode_body))
            WorldSwitch(tabName = Tab.PROFILE.name)
        }
    }
}

/** Accès à l'écran Homelab, avec les services actifs. */
@Composable
private fun HomelabSection(ready: List<HomelabService>, onOpen: () -> Unit) {
    SectionCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(CineIcons.Server, stringResource(R.string.settings_homelab_title))
                BodyText(stringResource(R.string.settings_homelab_body))
                Text(
                    text = if (ready.isEmpty()) {
                        stringResource(R.string.settings_homelab_off)
                    } else {
                        stringResource(R.string.settings_homelab_on, ready.joinToString(", ") { it.displayName })
                    },
                    color = if (ready.isEmpty()) CineColors.TextMuted else CineColors.Success,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun LanguageSection(language: String, onChange: (String) -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Discover, stringResource(R.string.settings_language_title))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsRepository.SUPPORTED_LANGUAGES.forEach { tag ->
                    val label = if (tag.startsWith("fr")) R.string.settings_language_fr else R.string.settings_language_en
                    CineChip(label = stringResource(label), selected = tag == language, onClick = { onChange(tag) })
                }
            }
        }
    }
}

/** « Mes données » : exporter, recommencer le classement, ou repartir de zéro. */
@Composable
private fun DataSection(exporting: Boolean, onExport: () -> Unit, onImport: () -> Unit, onReset: (DataReset) -> Unit) {
    val toaster = LocalToaster.current
    var pending by rememberSaveable { mutableStateOf<DataReset?>(null) }
    val rankingDone = stringResource(R.string.settings_data_ranking_done)
    val everythingDone = stringResource(R.string.settings_data_everything_done)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Trash, stringResource(R.string.settings_data_title))
            BodyText(stringResource(R.string.settings_data_export_body))
            CineSecondaryButton(
                text = stringResource(if (exporting) R.string.settings_data_exporting else R.string.settings_data_export),
                icon = CineIcons.Share,
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.EXPORT),
            )
            // Séries : seule la sauvegarde CineSwipe des séries (textes « _series »).
            BodyText(stringResource(R.string.settings_data_import_body))
            CineSecondaryButton(
                text = stringResource(R.string.settings_data_import),
                icon = CineIcons.Download,
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.IMPORT),
            )
            BodyText(stringResource(R.string.settings_data_body))
            CineSecondaryButton(
                text = stringResource(R.string.settings_data_ranking),
                icon = CineIcons.Arena,
                onClick = { pending = DataReset.RANKING },
                modifier = Modifier.fillMaxWidth(),
            )
            CineSecondaryButton(
                text = stringResource(R.string.settings_data_everything),
                icon = CineIcons.Trash,
                onClick = { pending = DataReset.EVERYTHING },
                contentColor = CineColors.Red,
                borderColor = CineColors.Red.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val kind = pending ?: return
    AlertDialog(
        onDismissRequest = { pending = null },
        containerColor = CineColors.Surface,
        title = {
            Text(
                stringResource(if (kind == DataReset.RANKING) R.string.settings_data_ranking_title else R.string.settings_data_everything_title),
                color = Color.White,
            )
        },
        text = {
            Text(
                stringResource(if (kind == DataReset.RANKING) R.string.settings_data_ranking_message else R.string.settings_data_everything_message),
                color = CineColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                pending = null
                onReset(kind)
                toaster.show(if (kind == DataReset.RANKING) rankingDone else everythingDone)
            }) {
                Text(stringResource(R.string.settings_data_confirm), color = CineColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { pending = null }) {
                Text(stringResource(R.string.action_cancel), color = CineColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun CrashSection(crashLog: String?, onClear: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val copiedMessage = stringResource(R.string.settings_crash_copied)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(CineIcons.Info, stringResource(R.string.settings_crash_title))
            if (crashLog == null) {
                BodyText(stringResource(R.string.settings_crash_none))
            } else {
                Text(
                    text = crashLog.lines().take(14).joinToString("\n"),
                    color = Color(0xFF00FF00),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .padding(12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CineSecondaryButton(
                        text = stringResource(R.string.settings_crash_copy),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CineSwipe", crashLog)))
                                toaster.show(copiedMessage)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CineSecondaryButton(
                        text = stringResource(R.string.settings_crash_clear),
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun BodyText(text: String) {
    Text(text, color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
}
