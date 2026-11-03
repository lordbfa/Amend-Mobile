package app.amend.messenger.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Scheduled messages: they wait on this phone and are sent at their time. While amend is
 * open (or the live connection runs) the repository sends them itself; this wakes the phone
 * for the next one when amend is closed.
 */
object ScheduledSend {
    private const val WORK = "amend-scheduled-send"

    /** Points the wake-up at the earliest pending scheduled message (or cancels it). */
    fun reschedule(context: Context, repo: AmendRepository) {
        val wm = WorkManager.getInstance(context)
        val next = repo.nextScheduledAt()
        if (next == null) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val delaySecs = (next - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay(delaySecs, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }
}

class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AmendApp
        val repo = app.repository.value ?: return Result.success()
        val wasOnline = repo.isOnline()
        if (!wasOnline) {
            repo.goOnline()
            repo.awaitOnline(20_000)
        }
        runCatching { repo.sendDueScheduled() }
        if (!wasOnline && !AppVisibility.isForeground && !LiveService.isRunning) {
            delay(4_000) // let the relays confirm
            repo.goOffline()
        }
        ScheduledSend.reschedule(app, repo)
        return Result.success()
    }
}
