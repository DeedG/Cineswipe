package io.github.deedg.cineswipe.ui.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.importer.ImportFailure
import io.github.deedg.cineswipe.data.importer.ImportFile
import io.github.deedg.cineswipe.data.importer.ImportRepository
import io.github.deedg.cineswipe.data.importer.ImportSource
import io.github.deedg.cineswipe.data.importer.ImportState
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImportTestTags {
    const val PICK = "import_pick"
    const val START = "import_start"
    const val KEY = "import_key"
    const val DONE = "import_done"
    const val LIBRARY = "import_library"
}

class ImportViewModel(
    private val importer: ImportRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<ImportState> = importer.state
    val hasKey: StateFlow<Boolean> = settings.tmdbApiKey.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Lit les fichiers choisis (hors du fil principal) puis les analyse. */
    fun read(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { readFile(resolver, it) } }
            if (files.isEmpty()) importer.failReading() else importer.load(files)
        }
    }

    fun start() = importer.start()

    fun reset() = importer.reset()

    fun retry() = importer.retry()

    private fun readFile(resolver: ContentResolver, uri: Uri): ImportFile? = runCatching {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "import"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_FILE_BYTES) return null
            }
            output.toByteArray()
        } ?: return null
        ImportFile(name, bytes)
    }.getOrNull()

    private companion object {
        /** Les exports d'applis font quelques Mo ; au-delà, ce n'est pas un export. */
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Import depuis une sauvegarde CineSwipe, Letterboxd, Trakt, IMDb ou un CSV : choix des fichiers,
 * aperçu, progression, bilan. Les explications d'export de chaque appli sont sur l'écran.
 */
@Composable
fun ImportRoute(onBack: () -> Unit, onOpenKeyGuide: () -> Unit, onOpenLibrary: () -> Unit) {
    val viewModel = cineViewModel { ImportViewModel(it.importer, it.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.read(resolver, uris)
    }
    ImportScreen(
        state = state,
        hasKey = hasKey,
        onBack = onBack,
        onPick = { runCatching { picker.launch(arrayOf("*/*")) } },
        onStart = viewModel::start,
        onReset = viewModel::reset,
        onRetry = viewModel::retry,
        onOpenKeyGuide = onOpenKeyGuide,
        onOpenLibrary = {
            viewModel.reset()
            onOpenLibrary()
        },
        onFinish = {
            viewModel.reset()
            onBack()
        },
    )
}

@Composable
fun ImportScreen(
    state: ImportState,
    hasKey: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onOpenKeyGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.import_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
        )
        when (state) {
            ImportState.Idle -> {
                Body(stringResource(R.string.import_intro))
                PickButton(primary = true, onPick = onPick)
                HowTo()
            }
            ImportState.Reading -> Waiting(stringResource(R.string.import_reading))
            is ImportState.Ready -> Ready(state, hasKey, onStart, onPick, onOpenKeyGuide)
            is ImportState.Running -> Running(state)
            is ImportState.Done -> Done(state, onOpenLibrary, onFinish)
            is ImportState.Failed -> Failed(state.reason, onPick, onReset, onRetry, onOpenKeyGuide)
        }
    }
}

@Composable
private fun Body(text: String, color: Color = CineColors.TextSoft) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
private fun PickButton(primary: Boolean, onPick: () -> Unit) {
    if (primary) {
        CineButton(
            text = stringResource(R.string.import_pick),
            icon = CineIcons.Upload,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.PICK),
        )
    } else {
        CineSecondaryButton(
            text = stringResource(R.string.import_pick_other),
            icon = CineIcons.Upload,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.PICK),
        )
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = CineColors.Red, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Body(text, CineColors.TextSecondary)
    }
}

/** Marche à suivre de chaque appli, dépliable. */
@Composable
private fun HowTo() {
    Text(stringResource(R.string.import_how_title), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
    val guides = listOf(
        R.string.import_how_cineswipe_title to R.string.import_how_cineswipe_body,
        R.string.import_how_letterboxd_title to R.string.import_how_letterboxd_body,
        R.string.import_how_trakt_title to R.string.import_how_trakt_body,
        R.string.import_how_imdb_title to R.string.import_how_imdb_body,
        R.string.import_how_other_title to R.string.import_how_other_body,
    )
    var open by rememberSaveable { mutableStateOf(-1) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        guides.forEachIndexed { index, (title, body) ->
            SectionCard(modifier = Modifier.animateContentSize(), onClick = { open = if (open == index) -1 else index }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(
                            CineIcons.ChevronRight,
                            contentDescription = null,
                            tint = CineColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (open == index) Body(stringResource(body), CineColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun sourceName(source: ImportSource): String = stringResource(
    when (source) {
        ImportSource.CINESWIPE -> R.string.import_source_cineswipe
        ImportSource.LETTERBOXD -> R.string.import_source_letterboxd
        ImportSource.TRAKT -> R.string.import_source_trakt
        ImportSource.IMDB -> R.string.import_source_imdb
        ImportSource.CSV -> R.string.import_source_csv
    },
)

@Composable
private fun Ready(state: ImportState.Ready, hasKey: Boolean, onStart: () -> Unit, onPick: () -> Unit, onOpenKeyGuide: () -> Unit) {
    val batch = state.batch
    val names = batch.sources.map { sourceName(it) }
    Text(stringResource(R.string.import_ready_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    if (names.isNotEmpty()) Body(stringResource(R.string.import_sources, names.joinToString(", ")))
    SectionCard {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Figure(batch.watched, stringResource(R.string.import_count_watched))
            Figure(batch.watchlist, stringResource(R.string.import_count_watchlist))
            Figure(batch.rated, stringResource(R.string.import_count_rated))
        }
    }
    if (batch.backup != null) Body(stringResource(if (state.restore) R.string.import_restore_note else R.string.import_merge_note), CineColors.TextSecondary)
    if (batch.ignored.isNotEmpty()) {
        Text(
            stringResource(R.string.import_ignored, batch.ignored.take(MAX_LISTED).joinToString(", ") { it.substringAfterLast('/') }),
            color = CineColors.TextFaint,
            fontSize = 13.sp,
        )
    }
    val blocked = state.needsTmdb && !hasKey
    if (blocked) {
        Body(stringResource(R.string.import_needs_key), CineColors.Red)
        CineButton(
            text = stringResource(R.string.action_open_settings),
            onClick = onOpenKeyGuide,
            modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.KEY),
        )
    } else {
        CineButton(
            text = pluralStringResource(R.plurals.import_start, batch.items.size, batch.items.size),
            icon = CineIcons.Download,
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.START),
        )
    }
    PickButton(primary = false, onPick = onPick)
}

@Composable
private fun Figure(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(label, color = CineColors.TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Running(state: ImportState.Running) {
    Text(stringResource(R.string.import_running_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    LinearProgressIndicator(
        progress = { if (state.total == 0) 0f else (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = CineColors.Red,
        trackColor = CineColors.BorderStrong,
        drawStopIndicator = {},
    )
    Body(stringResource(R.string.import_running_body, state.done, state.total), CineColors.TextSecondary)
}

@Composable
private fun Done(state: ImportState.Done, onOpenLibrary: () -> Unit, onFinish: () -> Unit) {
    val report = state.report
    Text(stringResource(R.string.import_done_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    if (report.restored) Body(stringResource(R.string.import_done_restored, report.restoredFilms, report.restoredDuels))
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Figure(report.counts.watched, stringResource(R.string.import_done_added_watched))
                Figure(report.counts.watchlist, stringResource(R.string.import_done_added_watchlist))
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Figure(report.counts.rated, stringResource(R.string.import_done_rated))
                if (!report.restored) Figure(report.counts.unchanged, stringResource(R.string.import_done_unchanged))
            }
        }
    }
    if (report.counts.rated > 0) Body(stringResource(R.string.import_done_rating_hint), CineColors.TextSecondary)
    if (report.lists > 0) Body(pluralStringResource(R.plurals.import_lists, report.lists, report.lists))
    if (report.skipped.isNotEmpty()) {
        Body(pluralStringResource(R.plurals.import_skipped, report.skipped.size, report.skipped.size), CineColors.Warning)
    }
    if (report.notFound.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.import_not_found, report.notFound.size, report.notFound.size),
                color = CineColors.TextSoft,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            if (expanded) {
                report.notFound.take(MAX_NOT_FOUND).forEach { Text("· $it", color = CineColors.TextMuted, fontSize = 13.sp) }
                if (report.notFound.size > MAX_NOT_FOUND) Text("…", color = CineColors.TextMuted, fontSize = 13.sp)
            }
        }
    }
    CineButton(
        text = stringResource(R.string.import_see_library),
        onClick = onOpenLibrary,
        modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.LIBRARY),
    )
    CineSecondaryButton(
        text = stringResource(R.string.import_finish),
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.DONE),
    )
}

@Composable
private fun Failed(reason: ImportFailure, onPick: () -> Unit, onReset: () -> Unit, onRetry: () -> Unit, onOpenKeyGuide: () -> Unit) {
    Text(stringResource(R.string.import_failed_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    Body(
        stringResource(
            when (reason) {
                ImportFailure.UNREADABLE -> R.string.import_failed_unreadable
                ImportFailure.EMPTY -> R.string.import_failed_empty
                ImportFailure.MISSING_KEY -> R.string.import_failed_missing_key
                ImportFailure.INVALID_KEY -> R.string.import_failed_invalid_key
                ImportFailure.NETWORK -> R.string.import_failed_network
                ImportFailure.ERROR -> R.string.import_failed_error
            },
        ),
        CineColors.TextSecondary,
    )
    when (reason) {
        ImportFailure.MISSING_KEY, ImportFailure.INVALID_KEY -> CineButton(
            text = stringResource(R.string.action_open_settings),
            onClick = {
                onReset()
                onOpenKeyGuide()
            },
            modifier = Modifier.fillMaxWidth().testTag(ImportTestTags.KEY),
        )
        else -> PickButton(primary = true, onPick = onPick)
    }
    if (reason == ImportFailure.NETWORK || reason == ImportFailure.ERROR) {
        CineSecondaryButton(text = stringResource(R.string.import_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
    }
    if (reason == ImportFailure.UNREADABLE) HowTo()
}

private const val MAX_LISTED = 6
private const val MAX_NOT_FOUND = 200
