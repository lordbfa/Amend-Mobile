package app.amend.messenger.util

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that tracks location (GPS + network, no Google services)
 * for as long as an SOS is active, and hands every fix to [SosManager].
 */
class SosService : Service() {
    private var lm: LocationManager? = null

    // All four methods overridden: on Android < 11 the extra ones are abstract and
    // would crash with AbstractMethodError if left to the interface defaults.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = SosManager.onLocation(this@SosService, location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // Restarted by the system after the process died: pick the SOS back up.
        SosManager.restoreIfNeeded(this)
        if (!SosManager.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLocation()
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(
                this, Notifications.SOS_ACTIVE_ID,
                Notifications.sosActiveNotification(this, 0, 0, SosManager.emergencyNumber(this)), type,
            )
        } catch (e: Exception) {
            // Location foreground service not allowed right now (no permission or background start).
            // The SOS still runs with the last known location and periodic updates.
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!SosManager.hasLocationPermission(this)) return
        val manager = getSystemService(LocationManager::class.java) ?: return
        lm = manager
        manager.removeUpdates(listener)
        // Frequent fixes with no distance filter; SosManager decides how often to send.
        SosManager.locationProviders(manager).forEach { p ->
            runCatching { manager.requestLocationUpdates(p, 5_000L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    override fun onDestroy() {
        lm?.removeUpdates(listener)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // Without location permission Android would stop the app for starting a location service.
            if (!SosManager.hasLocationPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, SosService::class.java))
            } catch (e: Exception) {
                // Background start not allowed — SosManager keeps sending heartbeats with the last fix.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SosService::class.java))
        }
    }
}

/** Handles buttons on SOS notifications. */
class SosActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL_COUNTDOWN -> SosManager.cancelCountdown(context)
        }
    }

    companion object {
        const val ACTION_CANCEL_COUNTDOWN = "app.amend.messenger.SOS_CANCEL_COUNTDOWN"
    }
}
