package app.amend.messenger.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendEvent
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Background message checks.
 *
 * Carbon-aware mode (inspired by carbon-aware OS updates) only syncs when the
 * phone is charging on Wi-Fi, and less often — trading a little delay for
 * fewer radio wake-ups. Efficiency mode stretches the interval further.
 */
object SyncScheduler {
    private const val WORK = "amend-sync"

    fun schedule(context: Context, carbonAware: Boolean, efficiency: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (carbonAware) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(carbonAware)
            .setRequiresBatteryNotLow(true)
            .build()
        val minutes = when {
            efficiency -> 60L
            carbonAware -> 30L
            else -> 15L
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val app = applicationContext as AmendApp
        val repo = app.repository.value ?: return@coroutineScope Result.success()
        if (AppVisibility.isForeground) return@coroutineScope Result.success() // live connection already running
        val job = repo.events.onEach { e ->
            when (e) {
                is AmendEvent.MessageArrived -> Notifications.message(
                    app, e.peer, repo.displayName(e.peer),
                    if (e.message.type == app.amend.messenger.data.MessageType.ECO_CARD) "🌱 sent you an eco challenge" else e.message.text,
                )
                else -> Unit
            }
        }.launchIn(this)
        runCatching { repo.syncOnce() }
        job.cancel()
        Result.success()
    }
}

/** Set by MainActivity so background work knows when the UI is live. */
object AppVisibility {
    @Volatile var isForeground = false
    @Volatile var openPeer: String? = null
}
