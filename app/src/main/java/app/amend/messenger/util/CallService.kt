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

/**
 * Keeps the microphone (and camera) available while a call runs with the screen
 * off or amend in the background, and shows the "on a call" notification.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, Notifications.CALL_ONGOING_ID, Notifications.ongoingCall(this, CallManager.state.value), type)
        } catch (e: Exception) {
            // e.g. permission missing: the call still works while amend is on screen
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            }
        }

        /** Refreshes the notification text (e.g. "Calling…" → timer). */
        fun update(context: Context) {
            if (!CallManager.state.value.active) return
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(context)
                    .notify(Notifications.CALL_ONGOING_ID, Notifications.ongoingCall(context, CallManager.state.value))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** "Decline" and "Hang up" buttons in call notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> CallManager.decline()
            ACTION_HANGUP -> CallManager.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.amend.messenger.call.DECLINE"
        const val ACTION_HANGUP = "app.amend.messenger.call.HANGUP"
    }
}
