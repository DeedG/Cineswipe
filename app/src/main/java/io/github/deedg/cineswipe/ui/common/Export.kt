package io.github.deedg.cineswipe.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.components.LocalToaster
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Résultat d'un export de données, à partager. */
sealed interface ExportEvent {
    data class Ready(val file: File) : ExportEvent

    data object Failed : ExportEvent
}

/** Autorité du FileProvider déclaré dans le manifeste. */
fun Context.fileProviderAuthority(): String = "$packageName.fileprovider"

/**
 * Propose d'envoyer un export JSON (ou une image, selon [mimeType]) (Drive, Gmail, Messages, Quick Share…). Renvoie faux si
 * aucune appli ne peut le recevoir.
 */
fun Context.shareExport(file: File, chooserTitle: String, mimeType: String = "application/json"): Boolean {
    val uri = try {
        FileProvider.getUriForFile(this, fileProviderAuthority(), file)
    } catch (error: IllegalArgumentException) {
        return false
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        startActivity(Intent.createChooser(send, chooserTitle))
        true
    } catch (error: ActivityNotFoundException) {
        false
    }
}

/** Ouvre le partage dès qu'un export est prêt, ou signale l'échec. */
@Composable
fun ExportEffect(events: Flow<ExportEvent>) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val chooserTitle = stringResource(R.string.export_chooser)
    val failed = stringResource(R.string.export_failed)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is ExportEvent.Ready -> if (!context.shareExport(event.file, chooserTitle)) toaster.show(failed)
                ExportEvent.Failed -> toaster.show(failed)
            }
        }
    }
}
