package app.amend.messenger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.amend.messenger.data.StepLog
import app.amend.messenger.data.StepReportMessage
import app.amend.messenger.data.StepSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** What the UI shows about walking. */
data class StepsUi(
    val enabled: Boolean = true,
    val hasSensor: Boolean = true,
    val hasPermission: Boolean = true,
    val today: Int = 0,
    val todayKm: Double = 0.0,
    val goal: Int = 8_000,
    val last7: List<Pair<LocalDate, Int>> = emptyList(),
    val monthSteps: Long = 0,
    val monthKm: Double = 0.0,
    val heightCm: Int = 170,
    val reports: List<StepReportMessage> = emptyList(),
    val unreadReports: Int = 0,
)

/**
 * Counts steps with the phone's hardware step counter — the low-power chip that
 * counts even while the screen is off. Readings are taken while amend is open,
 * while the live connection runs, and every 30 minutes in the background.
 * Everything stays on this phone.
 */
object StepTracker {
    /** Pseudo chat id for the "amend · Walking" conversation with the monthly reports. */
    const val REPORTS_PEER = "amend:walking"
    private const val PREFS = "amend_steps"
    private const val WORK = "amend-steps"

    private val lock = Any()
    private var loaded = false
    private var log = StepLog()
    private var settings = StepSettings()
    private var lastSave = 0L
    private val owners = mutableSetOf<String>()
    private var listening = false
    private lateinit var appContext: Context

    private val _ui = MutableStateFlow(StepsUi())
    val ui: StateFlow<StepsUi> = _ui.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) onReading(event.values[0].toLong())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            appContext = context.applicationContext
            val p = prefs()
            settings = StepSettings(
                enabled = p.getBoolean("enabled", true),
                heightCm = p.getInt("height", 170),
                dailyGoal = p.getInt("goal", 8_000),
            )
            log = StepLog.fromJson(p.getString("log", null), settings)
            loaded = true
        }
        publish()
        schedule(context)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasSensor(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Starts continuous counting for [owner] ("ui", "live"…). */
    fun start(context: Context, owner: String) {
        init(context)
        synchronized(lock) { owners += owner }
        register()
    }

    fun stop(owner: String) {
        val empty = synchronized(lock) { owners -= owner; owners.isEmpty() }
        if (empty) unregister()
        save(force = true)
    }

    /** Call after the permission is granted or a setting changed. */
    fun refresh(context: Context) {
        init(context)
        unregister()
        if (synchronized(lock) { owners.isNotEmpty() }) register()
        publish()
    }

    private fun register() {
        if (!settings.enabled || !hasPermission(appContext)) return
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        synchronized(lock) {
            if (listening) return
            // batched: the chip reports at most every 30 s, so the phone can stay asleep
            listening = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000)
        }
    }

    private fun unregister() {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        synchronized(lock) {
            if (!listening) return
            sm.unregisterListener(listener)
            listening = false
        }
    }

    /** One reading (for background work). */
    suspend fun sampleOnce(context: Context) {
        init(context)
        if (!settings.enabled || !hasPermission(context)) { checkReports(); return }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val value = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Long> { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
        if (value != null) onReading(value) else checkReports()
        save(force = true)
    }

    private fun onReading(counter: Long) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            log.record(counter, now)
            log.prune(LocalDate.now())
        }
        checkGoal()
        checkReports()
        publish()
        save(force = false)
    }

    private fun checkGoal() {
        val today = LocalDate.now()
        val steps = synchronized(lock) { log.steps(today) }
        if (steps < settings.dailyGoal) return
        val p = prefs()
        if (p.getString("goalDay", null) == today.toString()) return
        p.edit().putString("goalDay", today.toString()).apply()
        Notifications.stepGoal(appContext, steps, settings.dailyGoal)
    }

    /** Delivers the report for any month that has just ended. */
    fun checkReports() {
        if (!loaded) return
        val due = synchronized(lock) { log.dueReports(LocalDate.now(), settings, System.currentTimeMillis()) }
        if (due.isEmpty()) return
        val p = prefs()
        p.edit().putInt("unread", p.getInt("unread", 0) + due.size).apply()
        due.forEach { Notifications.stepReport(appContext, it) }
        save(force = true)
        publish()
    }

    fun markReportsRead() {
        if (!loaded) return
        prefs().edit().putInt("unread", 0).apply()
        Notifications.cancelStepReport(appContext)
        publish()
    }

    fun setEnabled(context: Context, on: Boolean) = update(context) { it.copy(enabled = on) }
    fun setHeight(context: Context, cm: Int) = update(context) { it.copy(heightCm = cm.coerceIn(100, 230)) }
    fun setGoal(context: Context, goal: Int) = update(context) { it.copy(dailyGoal = goal.coerceIn(1_000, 50_000)) }

    private fun update(context: Context, change: (StepSettings) -> StepSettings) {
        init(context)
        settings = change(settings)
        prefs().edit()
            .putBoolean("enabled", settings.enabled)
            .putInt("height", settings.heightCm)
            .putInt("goal", settings.dailyGoal)
            .apply()
        // reports use the stride, so rebuild their text
        synchronized(lock) { log = StepLog.fromJson(log.toJson(), settings) }
        refresh(context)
    }

    private fun save(force: Boolean) {
        if (!loaded) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 60_000) return
        lastSave = now
        val json = synchronized(lock) { log.toJson() }
        prefs().edit().putString("log", json).apply()
    }

    private fun publish() {
        if (!loaded) return
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val stride = settings.strideMeters
        val ui = synchronized(lock) {
            val t = log.steps(today)
            val m = log.monthTotal(month)
            StepsUi(
                enabled = settings.enabled,
                hasSensor = hasSensor(appContext),
                hasPermission = hasPermission(appContext),
                today = t,
                todayKm = t * stride / 1000.0,
                goal = settings.dailyGoal,
                last7 = (6 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to log.steps(d) } },
                monthSteps = m,
                monthKm = m * stride / 1000.0,
                heightCm = settings.heightCm,
                reports = log.reports.toList(),
                unreadReports = prefs().getInt("unread", 0),
            )
        }
        _ui.value = ui
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<StepWorker>(30, TimeUnit.MINUTES).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Takes a step reading every half hour, and delivers the monthly report on time. */
class StepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { StepTracker.sampleOnce(applicationContext) }
        return Result.success()
    }
}
