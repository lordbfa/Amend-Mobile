package app.amend.messenger.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.amend.messenger.AmendApp
import app.amend.messenger.data.EmergencyNumbers
import app.amend.messenger.data.SosLocation
import app.amend.messenger.data.SosPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** User settings for SOS, kept in plain SharedPreferences so services and receivers can read them. */
class SosPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("amend_sos", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var volumeTrigger: Boolean
        get() = p.getBoolean("volume", true)
        set(v) = p.edit().putBoolean("volume", v).apply()

    var powerTrigger: Boolean
        get() = p.getBoolean("power", true)
        set(v) = p.edit().putBoolean("power", v).apply()

    var autoDial: Boolean
        get() = p.getBoolean("autoDial", true)
        set(v) = p.edit().putBoolean("autoDial", v).apply()

    /** Whether we've already shown the one-time "allow location for SOS" prompt. */
    var askedLocation: Boolean
        get() = p.getBoolean("askedLocation", false)
        set(v) = p.edit().putBoolean("askedLocation", v).apply()

    /** Empty = use the number for the detected country. */
    var customNumber: String
        get() = p.getString("number", "") ?: ""
        set(v) = p.edit().putString("number", v.filter { it.isDigit() || it == '+' }.take(15)).apply()

    var excluded: Set<String>
        get() = p.getStringSet("excluded", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("excluded", v).apply()

    /** Persisted so an SOS survives the app process being restarted. */
    var activeSince: Long
        get() = p.getLong("activeSince", 0)
        set(v) = p.edit().putLong("activeSince", v).apply()

    var activeRecipients: Set<String>
        get() = p.getStringSet("recipients", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("recipients", v).apply()

    /** Also alert amend users physically nearby over the offline mesh (anonymous, no internet needed). Off by default. */
    var nearbyBeacon: Boolean
        get() = p.getBoolean("nearbyBeacon", false)
        set(v) = p.edit().putBoolean("nearbyBeacon", v).apply()

    /** Show "someone nearby needs help" alerts that arrive over the mesh. */
    var nearbyAlerts: Boolean
        get() = p.getBoolean("nearbyAlerts", true)
        set(v) = p.edit().putBoolean("nearbyAlerts", v).apply()
}

/** Detects N presses within a time window (e.g. 4 presses in 3 seconds). */
class PressPattern(private val presses: Int = 4, private val windowMs: Long = 3_000) {
    private val times = ArrayDeque<Long>()

    /** Returns true when the pattern completes (and resets). */
    @Synchronized
    fun press(now: Long = System.currentTimeMillis()): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= presses) {
            times.clear()
            return true
        }
        return false
    }

    @Synchronized
    fun count(now: Long = System.currentTimeMillis()): Int {
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        return times.size
    }
}

sealed class SosState {
    data object Idle : SosState()
    data class Countdown(val secondsLeft: Int, val recipients: Int, val practice: Boolean) : SosState()
    data class Active(
        val startedAt: Long,
        val recipients: Int,
        val reached: Int,
        val location: SosLocation?,
        val lastSentAt: Long,
        val updates: Int,
        val practice: Boolean,
    ) : SosState()
}

/**
 * Runs the SOS flow: trigger → 5-second countdown (cancellable) → active
 * (live location to chosen contacts + emergency dialer) → stopped by the user.
 */
object SosManager {
    const val COUNTDOWN_SECONDS = 5
    private const val MIN_UPDATE_INTERVAL_MS = 15_000L
    private const val HEARTBEAT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var heartbeatJob: Job? = null
    private var recipients: List<String> = emptyList()
    private var lastSentAt = 0L
    private var latest: SosLocation? = null
    private var sending = false
    /** A newer fix arrived while sending; send it as soon as the current send finishes. */
    private var pendingUpdate = false
    /** Whether any location has gone out yet in this SOS — the first fix is sent immediately. */
    private var locationSent = false

    val isActive: Boolean get() = _state.value is SosState.Active && !(_state.value as SosState.Active).practice

    private fun app(context: Context) = context.applicationContext as AmendApp

    fun emergencyNumber(context: Context): String {
        val custom = SosPrefs(context).customNumber
        if (custom.isNotBlank()) return custom
        return EmergencyNumbers.forCountry(countryIso(context))
    }

    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }?.uppercase() ?: ""
    }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called by any trigger (volume, power, on-screen button). */
    /** Returns false if no SOS was started (switched off, one already running, or no identity yet). */
    fun trigger(context: Context, practice: Boolean = false, force: Boolean = false): Boolean {
        // [force]: a missed safety check-in starts the SOS even if the SOS triggers are switched off
        if (!practice && !force && !SosPrefs(context).enabled) return false
        if (_state.value !is SosState.Idle) return false
        val repo = app(context).repository.value ?: return false
        recipients = repo.sosRecipients(SosPrefs(context).excluded)
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (s in COUNTDOWN_SECONDS downTo 1) {
                _state.value = SosState.Countdown(s, recipients.size, practice)
                vibrate(context, if (s == COUNTDOWN_SECONDS) 600L else 180L)
                if (!AppVisibility.isForeground && !practice) Notifications.sosCountdown(context, s)
                delay(1_000)
            }
            Notifications.cancelSosCountdown(context)
            activate(context, practice)
        }
        return true
    }

    fun cancelCountdown(context: Context) {
        if (_state.value !is SosState.Countdown) return
        countdownJob?.cancel()
        _state.value = SosState.Idle
        Notifications.cancelSosCountdown(context)
        vibrate(context, 60)
    }

    private fun activate(context: Context, practice: Boolean) {
        val now = System.currentTimeMillis()
        latest = lastKnownLocation(context)
        _state.value = SosState.Active(now, recipients.size, 0, latest, 0, 0, practice)
        vibrate(context, 900)
        if (practice) return
        val prefs = SosPrefs(context)
        prefs.activeSince = now
        prefs.activeRecipients = recipients.toSet()
        locationSent = latest != null
        pendingUpdate = false
        startTracking(context)
        send(context, SosPhase.START)
        startHeartbeat(context)
        beaconSent = false
        if (prefs.nearbyBeacon) beaconSent = Mesh.broadcastNearbySos(context, latest)
        if (prefs.autoDial) openDialer(context)
    }

    /** Whether the "someone nearby needs help" mesh beacon has gone out with a location. */
    private var beaconSent = false

    /**
     * Starts live tracking if location is allowed. Call again after the user grants
     * permission mid-SOS. (Starting the location service without permission would
     * make Android stop the app, so it is never started then.)
     */
    fun startTracking(context: Context) {
        if (!hasLocationPermission(context)) return
        if (_state.value !is SosState.Active) return
        SosService.start(context)
        requestFreshFix(context)
    }

    /** Asks every available provider for one fresh fix right now (much faster than waiting for updates). */
    @SuppressLint("MissingPermission")
    private fun requestFreshFix(context: Context) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val providers = locationProviders(lm)
        if (Build.VERSION.SDK_INT >= 30) {
            providers.forEach { p ->
                runCatching {
                    lm.getCurrentLocation(p, null, ContextCompat.getMainExecutor(context)) { loc -> if (loc != null) onLocation(context, loc) }
                }
            }
        } else {
            // Full listener: on Android < 11 the extra methods are abstract and must be implemented.
            val single = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) = onLocation(context, location)
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            providers.forEach { p ->
                runCatching {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, single, android.os.Looper.getMainLooper())
                }
            }
        }
    }

    /** Enabled providers, best first. The fused provider (Android 12+) works indoors and is fast. */
    fun locationProviders(lm: LocationManager): List<String> {
        val all = runCatching { lm.allProviders }.getOrDefault(emptyList())
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return wanted.filter { it in all && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Whether the phone's location switch (GPS) is on. */
    fun isLocationOn(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else locationProviders(lm).isNotEmpty()
    }

    /** Re-attaches to an SOS that was running before the app process restarted. */
    fun restoreIfNeeded(context: Context) {
        val prefs = SosPrefs(context)
        if (prefs.activeSince == 0L || _state.value !is SosState.Idle) return
        recipients = prefs.activeRecipients.toList()
        latest = lastKnownLocation(context)
        _state.value = SosState.Active(prefs.activeSince, recipients.size, 0, latest, 0, 0, false)
        startTracking(context)
        startHeartbeat(context)
    }

    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (System.currentTimeMillis() - lastSentAt >= HEARTBEAT_MS) send(context, SosPhase.UPDATE)
            }
        }
    }

    /** New fix. The first one goes out immediately; after that at most every 15 s. */
    fun onLocation(context: Context, location: Location) {
        val s = _state.value as? SosState.Active ?: return
        // Ignore a much worse fix than the one we have, unless ours is getting old
        val current = latest
        if (current != null && location.accuracy > current.accuracyM * 3 && location.time / 1000 - current.time < 60) return
        val loc = SosLocation(location.latitude, location.longitude, location.accuracy, location.time / 1000)
        latest = loc
        _state.value = s.copy(location = loc)
        if (s.practice) return
        if (!beaconSent && SosPrefs(context).nearbyBeacon) beaconSent = Mesh.broadcastNearbySos(context, loc)
        if (!locationSent || System.currentTimeMillis() - lastSentAt >= MIN_UPDATE_INTERVAL_MS) {
            if (sending) pendingUpdate = true else send(context, SosPhase.UPDATE)
        }
    }

    private fun send(context: Context, phase: SosPhase) {
        val repo = app(context).repository.value ?: return
        if (sending && phase == SosPhase.UPDATE) return
        sending = true
        lastSentAt = System.currentTimeMillis()
        val loc = latest
        if (loc != null) locationSent = true
        val to = recipients
        scope.launch {
            try {
                repo.goOnline()
                val reached = repo.sendSos(phase, loc, to)
                (_state.value as? SosState.Active)?.let { s ->
                    _state.value = s.copy(
                        reached = maxOf(s.reached, reached),
                        lastSentAt = System.currentTimeMillis(),
                        updates = s.updates + if (phase == SosPhase.UPDATE) 1 else 0,
                    )
                }
                if (phase != SosPhase.STOP) Notifications.sosActive(context, reached, to.size, emergencyNumber(context))
            } finally {
                sending = false
                // a fix arrived while we were sending — deliver it now
                if (pendingUpdate && _state.value is SosState.Active) {
                    pendingUpdate = false
                    send(context, SosPhase.UPDATE)
                }
            }
        }
    }

    /** Ends the SOS: tells everyone you're safe and stops sharing location. */
    fun stop(context: Context) {
        val s = _state.value as? SosState.Active ?: return
        heartbeatJob?.cancel()
        _state.value = SosState.Idle
        val prefs = SosPrefs(context)
        prefs.activeSince = 0
        SosService.stop(context)
        Notifications.cancelSosActive(context)
        if (!s.practice) {
            sending = false
            send(context, SosPhase.STOP)
        }
        vibrate(context, 120)
    }

    /** Opens the dialer with the national emergency number filled in (Android requires the user to press call). */
    fun openDialer(context: Context) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + emergencyNumber(context))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // no dialer (tablet) — the SOS screen still shows the number
        }
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): SosLocation? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return SosLocation(best.latitude, best.longitude, best.accuracy, best.time / 1000)
    }

    private fun vibrate(context: Context, ms: Long) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
