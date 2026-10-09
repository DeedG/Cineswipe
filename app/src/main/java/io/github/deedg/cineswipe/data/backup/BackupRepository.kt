package io.github.deedg.cineswipe.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.data.repository.BackupStatus
import io.github.deedg.cineswipe.data.repository.DiagnosticsRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Un fichier du dossier de sauvegarde. */
data class BackupEntry(val id: String, val name: String)

/** Dossier choisi par l'utilisateur (Storage Access Framework), ou un dossier de test. */
interface BackupStorage {
    /** Garde l'accès au dossier après redémarrage du téléphone. */
    fun keep(folder: String)

    fun release(folder: String)

    /** Nom du dossier à afficher (« Documents », « CineSwipe »…). */
    fun displayName(folder: String): String?

    fun write(folder: String, name: String, mimeType: String, bytes: ByteArray)

    fun list(folder: String): List<BackupEntry>

    fun delete(folder: String, entry: BackupEntry)
}

/** Dossier choisi avec le sélecteur Android (téléphone, carte SD, Nextcloud, Syncthing…). */
class SafBackupStorage(context: Context) : BackupStorage {
    private val resolver = context.applicationContext.contentResolver

    private fun tree(folder: String): Uri = Uri.parse(folder)

    private fun root(folder: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree(folder), DocumentsContract.getTreeDocumentId(tree(folder)))

    override fun keep(folder: String) {
        resolver.takePersistableUriPermission(tree(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override fun release(folder: String) {
        runCatching {
            resolver.releasePersistableUriPermission(tree(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    override fun displayName(folder: String): String? = runCatching {
        resolver.query(root(folder), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun write(folder: String, name: String, mimeType: String, bytes: ByteArray) {
        val document = DocumentsContract.createDocument(resolver, root(folder), mimeType, name)
            ?: throw IllegalStateException("Impossible de créer $name")
        try {
            resolver.openOutputStream(document, "w")?.use { it.write(bytes) } ?: throw IllegalStateException("Écriture impossible")
        } catch (error: Exception) {
            // Un fichier vide ou à moitié écrit compterait parmi les 8 gardés : on le retire.
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw error
        }
    }

    override fun list(folder: String): List<BackupEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree(folder), DocumentsContract.getTreeDocumentId(tree(folder)))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(BackupEntry(cursor.getString(0), cursor.getString(1).orEmpty()))
            }
        }.orEmpty()
    }

    override fun delete(folder: String, entry: BackupEntry) {
        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree(folder), entry.id))
    }
}

/** Planification de la sauvegarde hebdomadaire. */
interface BackupScheduler {
    fun schedule()

    fun cancel()

    object None : BackupScheduler {
        override fun schedule() = Unit

        override fun cancel() = Unit
    }
}

class WorkManagerBackupScheduler(private val context: Context) : BackupScheduler {
    override fun schedule() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(BackupRepository.INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }

    override fun cancel() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    private companion object {
        const val WORK = "auto-backup"
    }
}

/** Sauvegarde de la semaine, même appli fermée (toujours les vraies données, jamais la démo). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as CineSwipeApp).mainContainer.backup
        return when (backup.backupNow()) {
            BackupResult.DONE, BackupResult.DISABLED, BackupResult.SKIPPED -> Result.success()
            BackupResult.FAILED -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

enum class BackupResult { DONE, DISABLED, FAILED, SKIPPED }

/**
 * Sauvegarde automatique : chaque semaine, toutes les données (le même JSON que l'export, dans
 * une archive ZIP) sont écrites dans un dossier choisi par l'utilisateur. Les 8 dernières sont
 * gardées. Une sauvegarde se restaure avec l'import (Réglages › Mes données).
 */
class BackupRepository(
    private val diagnostics: DiagnosticsRepository,
    private val settings: SettingsRepository,
    private val storage: BackupStorage,
    private val scheduler: BackupScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    val folder: Flow<String?> get() = settings.backupFolder
    val status: Flow<BackupStatus> get() = settings.backupStatus

    fun folderName(folder: String): String? = storage.displayName(folder)

    /** Au démarrage : la sauvegarde hebdomadaire est planifiée si elle est activée. */
    suspend fun ensureScheduled() {
        if (settings.backupFolder.first() != null) scheduler.schedule()
    }

    /** Active la sauvegarde dans [folder] (et en fait une tout de suite), ou la désactive (null). */
    suspend fun setFolder(folder: String?): BackupResult {
        val previous = settings.backupFolder.first()
        if (folder == null) {
            settings.setBackupFolder(null)
            previous?.let(storage::release)
            scheduler.cancel()
            return BackupResult.DISABLED
        }
        runCatching { storage.keep(folder) }
        if (previous != null && previous != folder) storage.release(previous)
        settings.setBackupFolder(folder)
        scheduler.schedule()
        return backupNow()
    }

    /** Une sauvegarde maintenant. */
    suspend fun backupNow(): BackupResult = mutex.withLock {
        val folder = settings.backupFolder.first() ?: return@withLock BackupResult.DISABLED
        val now = clock()
        try {
            val data = diagnostics.exportData()
            if (data.films.none { it.status == "WATCHED" || it.status == "WATCHLIST" }) {
                // Bibliothèque vide (tout effacé, nouveau téléphone) : les sauvegardes précédentes restent intactes.
                settings.recordBackup(now, null, ERROR_EMPTY)
                return@withLock BackupResult.SKIPPED
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.FRANCE).format(Date(now))
            val bytes = withContext(Dispatchers.Default) { zip("cineswipe-$stamp.json", diagnostics.encode(data).toByteArray()) }
            val name = "$PREFIX$stamp.zip"
            withContext(Dispatchers.IO) {
                storage.write(folder, name, "application/zip", bytes)
                prune(folder)
            }
            settings.recordBackup(now, name, error = null)
            BackupResult.DONE
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            settings.recordBackup(now, null, ERROR_ACCESS)
            BackupResult.FAILED
        } catch (error: Exception) {
            settings.recordBackup(now, null, error.message ?: error.javaClass.simpleName)
            BackupResult.FAILED
        }
    }

    /** Ne garde que les [KEEP] sauvegardes les plus récentes (les noms se trient par date). */
    private fun prune(folder: String) {
        storage.list(folder)
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(".zip") }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { runCatching { storage.delete(folder, it) } }
    }

    private fun zip(name: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    companion object {
        const val PREFIX = "cineswipe-sauvegarde-"
        const val KEEP = 8
        const val INTERVAL_DAYS = 7L

        /** Le dossier n'est plus accessible (permission perdue, carte retirée…). */
        const val ERROR_ACCESS = "access"

        /** Rien à sauvegarder : la bibliothèque est vide. */
        const val ERROR_EMPTY = "empty"
    }
}
