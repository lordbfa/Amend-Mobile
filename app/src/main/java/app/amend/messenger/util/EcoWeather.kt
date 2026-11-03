package app.amend.messenger.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.amend.messenger.data.AlertLevel
import app.amend.messenger.data.EcoAlert
import app.amend.messenger.data.EcoAlerts
import app.amend.messenger.data.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Weather, air quality and river levels around you, from the free Open-Meteo
 * service (no account, no tracking; only your position rounded to ~1 km is sent).
 */
object EcoWeather {
    private const val PREFS = "amend_weather"
    private const val WORK = "amend-weather"
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).proxySelector(app.amend.messenger.net.TorProxy).build() }

    private val _state = MutableStateFlow<WeatherSnapshot?>(null)
    val state: StateFlow<WeatherSnapshot?> = _state.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun alertsOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("alerts", true)

    fun setAlertsOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("alerts", on).apply()
        schedule(context)
    }

    /** Loads the last saved reading straight away (no network). */
    fun restore(context: Context) {
        if (_state.value != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return
        _state.value = EcoAlerts.parse(lat, lon, p.getString("f", null), p.getString("a", null), p.getString("fl", null), p.getLong("at", 0), p.getString("place", "") ?: "")
    }

    /** Fetches fresh data (at most every 20 minutes unless [force]). Returns the reading, or null without location. */
    suspend fun refresh(context: Context, force: Boolean = false): WeatherSnapshot? = withContext(Dispatchers.IO) {
        restore(context)
        val last = _state.value
        if (!force && last != null && System.currentTimeMillis() - last.fetchedAt < 20 * 60_000) return@withContext last
        val loc = lastLocation(context) ?: return@withContext last
        _loading.value = true
        try {
            val f = get(EcoAlerts.forecastUrl(loc.latitude, loc.longitude))
            val a = get(EcoAlerts.airUrl(loc.latitude, loc.longitude))
            val fl = get(EcoAlerts.floodUrl(loc.latitude, loc.longitude))
            if (f == null && a == null) return@withContext last
            val place = placeName(context, loc) ?: last?.place ?: ""
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lat", loc.latitude.toString()).putString("lon", loc.longitude.toString())
                .putString("f", f).putString("a", a).putString("fl", fl).putLong("at", now).putString("place", place).apply()
            val snap = EcoAlerts.parse(loc.latitude, loc.longitude, f, a, fl, now, place)
            _state.value = snap
            snap
        } finally {
            _loading.value = false
        }
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "amend-android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun placeName(context: Context, loc: Location): String? {
        if (!android.location.Geocoder.isPresent()) return null
        return runCatching {
            val a = android.location.Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull()
    }

    /** Notifies about new warnings (once per alert per day). */
    fun notifyNew(context: Context, snap: WeatherSnapshot) {
        if (!alertsOn(context)) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val seen = p.getStringSet("notified", emptySet())!!.filter { it.startsWith(today) }.toMutableSet()
        EcoAlerts.alerts(snap).filter { it.level != AlertLevel.INFO }.forEach { alert ->
            val key = "$today:${alert.id}"
            if (key !in seen) {
                Notifications.ecoAlert(context, alert, snap.place)
                seen += key
            }
        }
        p.edit().putStringSet("notified", seen).apply()
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!alertsOn(context)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<WeatherWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val snap = runCatching { EcoWeather.refresh(applicationContext, force = true) }.getOrNull() ?: return Result.success()
        EcoWeather.notifyNew(applicationContext, snap)
        return Result.success()
    }
}

@Suppress("unused")
private val keepType: EcoAlert? = null
