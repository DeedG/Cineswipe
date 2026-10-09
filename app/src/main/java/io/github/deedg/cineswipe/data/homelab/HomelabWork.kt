package io.github.deedg.cineswipe.data.homelab

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deedg.cineswipe.CineSwipeApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Tâches de fond du homelab : synchro périodique et envoi des demandes en attente. */
interface HomelabScheduler {
    fun schedulePeriodicSync()

    fun cancelPeriodicSync()

    /**
     * Envoi de la file d'attente dans [delayMs] au plus tôt, dès que le réseau est là : filet de
     * sécurité si l'appli est fermée avant l'envoi.
     */
    fun scheduleOutboxFlush(delayMs: Long = 0)

    /** Rien en arrière-plan (tests). */
    object None : HomelabScheduler {
        override fun schedulePeriodicSync() = Unit

        override fun cancelPeriodicSync() = Unit

        override fun scheduleOutboxFlush(delayMs: Long) = Unit
    }
}

/** WorkManager : les tâches survivent à la fermeture de l'appli et attendent le réseau. */
class WorkManagerScheduler(private val context: Context) : HomelabScheduler {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodicSync() = withWorkManager {
        it.enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HomelabSyncWorker>(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
    }

    override fun cancelPeriodicSync() = withWorkManager { it.cancelUniqueWork(SYNC_WORK) }

    override fun scheduleOutboxFlush(delayMs: Long) = withWorkManager {
        it.enqueueUniqueWork(
            OUTBOX_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(connected)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    private inline fun withWorkManager(block: (WorkManager) -> Unit) {
        // WorkManager s'initialise au démarrage de l'appli ; une erreur ici ne doit rien casser.
        runCatching { block(WorkManager.getInstance(context)) }
    }

    private companion object {
        const val SYNC_WORK = "homelab-sync"
        const val OUTBOX_WORK = "homelab-outbox"
        const val SYNC_INTERVAL_HOURS = 6L
    }
}

/** Synchro périodique (films vus, disponibilités, demandes), même appli fermée. */
class HomelabSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Toujours les vraies données, même si la démo tourne au premier plan.
        val homelab = (applicationContext as CineSwipeApp).mainContainer.homelab
        return try {
            homelab.sync()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.retry()
        }
    }
}

/** Envoie la file d'attente ; réessaie plus tard tant qu'il reste des demandes à envoyer. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val homelab = (applicationContext as CineSwipeApp).mainContainer.homelab
        return try {
            if (homelab.flushOutbox() > 0) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.retry()
        }
    }
}
