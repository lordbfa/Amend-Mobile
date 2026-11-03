package app.amend.messenger.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.amend.messenger.AmendApp
import app.amend.messenger.data.LivePhase
import app.amend.messenger.data.SosLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Inexact-but-Doze-proof alarms (no special permission): good to a few minutes, which is fine for safety timers. */
internal object SafetyAlarms {
    fun set(context: Context, action: String, atMs: Long, code: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context, action, code))
    }

    fun cancel(context: Context, action: String, code: Int) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, action, code))
    }

    fun pending(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, SafetyAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

// ------------------------------------------------------------------ live location in a chat

/**
 * "Share live location for 1 hour" in a chat. Uses the same location service as SOS,
 * sends an update at most every 30 s (and a heartbeat every 2 min), and stops by itself.
 */
object LiveShareManager {
    private const val PREFS = "amend_live_share"
    private const val MIN_UPDATE_MS = 30_000L
    private const val HEARTBEAT_MS = 120_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _shares = MutableStateFlow<Map<String, Long>>(emptyMap()) // chat -> until (unix s)
    /** Chats I'm sharing my live location with, and until when. */
    val shares: StateFlow<Map<String, Long>> = _shares.asStateFlow()
    private var latest: SosLocation? = null
    private var lastSentAt = 0L
    private var ticker: Job? = null

    /** True while sharing live location with at least one chat. */
    val sharing: Boolean get() = _shares.value.isNotEmpty()

    private fun repo(context: Context) = (context.applicationContext as AmendApp).repository.value

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("shares", _shares.value.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    fun restoreIfNeeded(context: Context) {
        if (_shares.value.isNotEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("shares", emptySet()).orEmpty()
            .mapNotNull { s -> s.substringBeforeLast("|").takeIf { it.isNotBlank() }?.let { it to (s.substringAfterLast("|").toLongOrNull() ?: 0L) } }
            .filter { it.second > now }.toMap()
        if (saved.isEmpty()) return
        _shares.value = saved
        startTicker(context)
    }

    fun start(context: Context, chat: String, minutes: Int) {
        val until = System.currentTimeMillis() / 1000 + minutes * 60L
        latest = SosManager.lastKnownLocation(context) ?: latest
        _shares.update { it + (chat to until) }
        save(context)
        SosService.start(context)
        startTicker(context)
        val r = repo(context) ?: return
        scope.launch { r.goOnline(); runCatching { r.sendLive(chat, LivePhase.START, latest, until) } }
    }

    fun stop(context: Context, chat: String) {
        val until = _shares.value[chat] ?: return
        _shares.update { it - chat }
        save(context)
        if (!sharing) {
            ticker?.cancel()
            SosService.stop(context)
        }
        val r = repo(context) ?: return
        scope.launch { runCatching { r.sendLive(chat, LivePhase.STOP, null, until) } }
    }

    fun onLocation(context: Context, location: Location) {
        if (!sharing) return
        latest = SosLocation(location.latitude, location.longitude, location.accuracy, location.time / 1000)
        if (System.currentTimeMillis() - lastSentAt >= MIN_UPDATE_MS) sendUpdates(context)
    }

    private fun sendUpdates(context: Context) {
        val r = repo(context) ?: return
        val loc = latest ?: return
        lastSentAt = System.currentTimeMillis()
        val now = lastSentAt / 1000
        _shares.value.filter { it.value > now }.forEach { (chat, until) ->
            scope.launch { runCatching { r.sendLive(chat, LivePhase.UPDATE, loc, until) } }
        }
    }

    private fun startTicker(context: Context) {
        ticker?.cancel()
        ticker = scope.launch {
            while (sharing) {
                delay(15_000)
                val now = System.currentTimeMillis() / 1000
                _shares.value.filter { it.value <= now }.keys.forEach { stop(context, it) } // time's up
                if (sharing && System.currentTimeMillis() - lastSentAt >= HEARTBEAT_MS) sendUpdates(context)
            }
        }
    }
}

// ------------------------------------------------------------------ safety check-in

/**
 * "Check in within 1 hour": if you don't tap "I'm safe" in time, amend starts your SOS
 * (live location to your SOS contacts). A reminder comes 5 minutes before.
 */
object CheckIn {
    private const val PREFS = "amend_checkin"
    const val ACTION_REMIND = "app.amend.messenger.CHECKIN_REMIND"
    const val ACTION_DUE = "app.amend.messenger.CHECKIN_DUE"
    const val ACTION_SAFE = "app.amend.messenger.CHECKIN_SAFE"
    private const val CODE_REMIND = 7101
    private const val CODE_DUE = 7102
    private const val REMIND_BEFORE_MS = 5 * 60_000L

    private val _deadline = MutableStateFlow<Long?>(null) // epoch ms
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()

    fun restore(context: Context) {
        val d = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("deadline", 0)
        _deadline.value = d.takeIf { it > 0 }
    }

    fun start(context: Context, minutes: Int) {
        val d = System.currentTimeMillis() + minutes * 60_000L
        _deadline.value = d
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("deadline", d).apply()
        SafetyAlarms.cancel(context, ACTION_REMIND, CODE_REMIND)
        if (minutes >= 10) SafetyAlarms.set(context, ACTION_REMIND, d - REMIND_BEFORE_MS, CODE_REMIND)
        SafetyAlarms.set(context, ACTION_DUE, d, CODE_DUE)
        Notifications.checkInOngoing(context, d)
    }

    /** "I'm safe": nothing is sent to anyone. */
    fun safe(context: Context) {
        _deadline.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("deadline").apply()
        SafetyAlarms.cancel(context, ACTION_REMIND, CODE_REMIND)
        SafetyAlarms.cancel(context, ACTION_DUE, CODE_DUE)
        Notifications.cancelCheckIn(context)
    }

    fun onAlarm(context: Context, action: String) {
        restore(context)
        val d = _deadline.value ?: return
        when (action) {
            ACTION_REMIND -> Notifications.checkInReminder(context, d)
            ACTION_DUE -> {
                // the alarm can be a little early in Doze; only act once the time has really come
                if (System.currentTimeMillis() < d - 60_000) { SafetyAlarms.set(context, ACTION_DUE, d, CODE_DUE); return }
                safe(context)
                Notifications.checkInMissed(context)
                SosManager.trigger(context, practice = false, force = true)
            }
        }
    }
}

// ------------------------------------------------------------------ fake call

/**
 * A believable incoming call you set up yourself — a polite way out of an uncomfortable
 * situation. It rings like a normal call; answering shows a plain call screen.
 */
object FakeCall {
    private const val PREFS = "amend_fakecall"
    const val ACTION_RING = "app.amend.messenger.FAKE_CALL"
    private const val CODE = 7201
    const val EXTRA = "amend_fake_call"

    private val _ringing = MutableStateFlow<String?>(null)
    /** Caller name while the fake call is ringing. */
    val ringing: StateFlow<String?> = _ringing.asStateFlow()
    private val _answeredAt = MutableStateFlow<Long?>(null)
    val answeredAt: StateFlow<Long?> = _answeredAt.asStateFlow()
    private val _scheduledAt = MutableStateFlow<Long?>(null)
    val scheduledAt: StateFlow<Long?> = _scheduledAt.asStateFlow()

    private var ringtone: Ringtone? = null

    fun callerName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", "Mom") ?: "Mom"

    fun setCallerName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name.trim().take(40).ifBlank { "Mom" }).apply()
    }

    fun schedule(context: Context, seconds: Int) {
        val at = System.currentTimeMillis() + seconds * 1000L
        _scheduledAt.value = at
        SafetyAlarms.set(context, ACTION_RING, at, CODE)
    }

    fun cancelScheduled(context: Context) {
        _scheduledAt.value = null
        SafetyAlarms.cancel(context, ACTION_RING, CODE)
    }

    fun ring(context: Context) {
        _scheduledAt.value = null
        if (_ringing.value != null || _answeredAt.value != null) return
        _ringing.value = callerName(context)
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0))
        if (!AppVisibility.isForeground) Notifications.fakeCall(context, _ringing.value ?: "")
    }

    fun answer(context: Context) {
        stopRinging(context)
        _ringing.value = null
        _answeredAt.value = System.currentTimeMillis()
    }

    fun end(context: Context) {
        stopRinging(context)
        _ringing.value = null
        _answeredAt.value = null
    }

    private fun stopRinging(context: Context) {
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator(context)?.cancel()
        Notifications.cancelFakeCall(context)
    }

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
}

/** Wakes up for check-in reminders/deadlines and scheduled fake calls; handles "I'm safe" from the notification. */
class SafetyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            CheckIn.ACTION_REMIND, CheckIn.ACTION_DUE -> CheckIn.onAlarm(context, action)
            CheckIn.ACTION_SAFE -> CheckIn.safe(context)
            FakeCall.ACTION_RING -> FakeCall.ring(context)
        }
    }
}
