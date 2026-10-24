package app.amend.messenger.util

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.amend.messenger.AmendApp

/**
 * Keeps amend's relay connection open while the app is in the background, so
 * messages arrive and notify instantly — without Google's push servers.
 *
 * Android requires a visible (but silent, minimised) notification for this.
 * Uses the "remote messaging" foreground-service type made for chat apps.
 */
class LiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * SOS trigger that works even with the phone locked: pressing the power button
     * 4 times quickly toggles the screen 4 times within a few seconds.
     */
    private val powerPattern = PressPattern(presses = 4, windowMs = 5_000)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON && intent.action != Intent.ACTION_SCREEN_OFF) return
            if (!SosPrefs(context).powerTrigger) return
            if (powerPattern.press()) SosManager.trigger(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val repo = (application as AmendApp).repository.value
        if (repo == null || !repo.eco.value.instantNotifications) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        repo.goOnline()
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        try {
            ServiceCompat.startForeground(this, Notifications.LIVE_ID, Notifications.liveConnection(this), type)
        } catch (e: Exception) {
            // Not allowed right now (e.g. started from the background on Android 12+): give up quietly,
            // periodic background sync still delivers messages.
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        isRunning = false
        if (!AppVisibility.isForeground) (application as AmendApp).repository.value?.goOffline()
        super.onDestroy()
    }

    companion object {
        @Volatile var isRunning = false
            private set

        /** Starts the service if instant notifications are on. Safe to call repeatedly. */
        fun start(context: Context) {
            val repo = (context.applicationContext as AmendApp).repository.value ?: return
            if (!repo.eco.value.instantNotifications) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java))
            } catch (e: Exception) {
                // Background start restrictions — the app will start it next time it's opened.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}

/** Restarts the live connection after the phone reboots or amend is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> LiveService.start(context)
        }
    }
}
