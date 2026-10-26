package app.amend.messenger.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.CallLog
import app.amend.messenger.data.CallOutcome
import app.amend.messenger.data.CallSignal
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val peer: String = "",
    val callId: String = "",
    val video: Boolean = false,
    val outgoing: Boolean = false,
    /** Caller side: the friend's phone confirmed it is ringing. */
    val ringing: Boolean = false,
    val startedAt: Long = 0,
    val connectedAt: Long = 0,
    val reconnecting: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = true,
    val frontCamera: Boolean = true,
    val endReason: String? = null,
) {
    val active: Boolean get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
}

/**
 * Where calls find a path between the phones.
 *
 * STUN (free, built in) is enough when at least one side has an open network.
 * Mobile carriers with carrier-grade NAT (common in Bangladesh, India…) need a
 * TURN server that relays the (still end-to-end encrypted) call. amend can use:
 *   - a Metered "credentials link" (free 20 GB/month),
 *   - a Cloudflare TURN key (generous free tier),
 *   - or any TURN server typed in by hand.
 * Only one of the two phones needs TURN for a call to connect.
 */
class CallPrefs(context: Context) {
    private val p = context.getSharedPreferences("amend_calls", Context.MODE_PRIVATE)
    private fun str(key: String) = p.getString(key, "") ?: ""
    private fun put(key: String, v: String) { p.edit().putString(key, v.trim()).remove("cache").remove("cacheUntil").apply() }

    var turnUrl: String
        get() = str("turnUrl")
        set(v) = put("turnUrl", v)
    var turnUser: String
        get() = str("turnUser")
        set(v) = put("turnUser", v)
    var turnPass: String
        get() = p.getString("turnPass", "") ?: ""
        set(v) { p.edit().putString("turnPass", v).remove("cache").remove("cacheUntil").apply() }
    var meteredLink: String
        get() = str("metered")
        set(v) = put("metered", v)
    var cloudflareKeyId: String
        get() = str("cfKey")
        set(v) = put("cfKey", v)
    var cloudflareToken: String
        get() = str("cfToken")
        set(v) = put("cfToken", v)

    /** amend's own TURN service (a Cloudflare Worker handing out short-lived Cloudflare TURN credentials). */
    var turnService: String
        get() = p.getString("turnService", null) ?: DEFAULT_TURN_SERVICE
        set(v) = put("turnService", v)

    val hasTurn: Boolean get() = turnService.isNotBlank() || meteredLink.isNotBlank() || cloudflareKeyId.isNotBlank() || turnUrl.isNotBlank()

    private fun stun() = listOf(
        IceServerConfig("stun:stun.l.google.com:19302"),
        IceServerConfig("stun:stun1.l.google.com:19302"),
        IceServerConfig("stun:stun.cloudflare.com:3478"),
    )

    /** Hand-typed TURN servers (several URLs may be separated by commas or spaces). */
    private fun manual(): List<IceServerConfig> = turnUrl.split(',', ' ', '\n').map { it.trim() }
        .filter { it.startsWith("turn:") || it.startsWith("turns:") }
        .map { IceServerConfig(it, turnUser, turnPass) }

    /** All servers for the next call. Fetches fresh TURN credentials when needed (cached for a few hours). */
    suspend fun resolveIceServers(onlyTurn: Boolean = false): List<IceServerConfig> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val fetched = cachedOrFetch()
        val turn = manual() + fetched.filter { it.url.startsWith("turn") }
        if (onlyTurn) turn else stun() + fetched.filter { it.url.startsWith("stun") }.take(1) + turn
    }

    private fun cachedOrFetch(): List<IceServerConfig> {
        if (turnService.isBlank() && meteredLink.isBlank() && cloudflareKeyId.isBlank()) return emptyList()
        val until = p.getLong("cacheUntil", 0)
        if (System.currentTimeMillis() < until) parse(p.getString("cache", null))?.let { return it }
        val out = mutableListOf<IceServerConfig>()
        var raw: String? = null
        if (turnService.startsWith("https://")) {
            val svc = turnService.trimEnd('/').let { if (it.endsWith("/ice")) it else "$it/ice" }
            runCatching {
                http.newCall(okhttp3.Request.Builder().url(svc).header("X-Amend-Key", TURN_SERVICE_APP_KEY).build())
                    .execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()?.let { parse(it)?.let { list -> out.addAll(list) } }
        }
        if (meteredLink.startsWith("https://")) {
            raw = runCatching {
                http.newCall(okhttp3.Request.Builder().url(meteredLink).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            raw?.let { parse(it)?.let { list -> out.addAll(list) } }
        }
        if (cloudflareKeyId.isNotBlank() && cloudflareToken.isNotBlank()) {
            val body = """{"ttl": 86400}""".toRequestBody("application/json".toMediaType())
            val r = runCatching {
                http.newCall(
                    okhttp3.Request.Builder()
                        .url("https://rtc.live.cloudflare.com/v1/turn/keys/$cloudflareKeyId/credentials/generate-ice-servers")
                        .header("Authorization", "Bearer $cloudflareToken")
                        .post(body).build()
                ).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            r?.let { parse(it)?.let { list -> out.addAll(list) } }
        }
        if (out.isNotEmpty()) {
            val json = app.amend.messenger.core.json.Json.write(out.map { mapOf("urls" to it.url, "username" to it.username, "credential" to it.password) })
            p.edit().putString("cache", json).putLong("cacheUntil", System.currentTimeMillis() + 5 * 3600_000L).apply()
        }
        return out
    }

    companion object {
        /** Set once the Worker in /turn-worker is deployed: every amend user then gets TURN automatically. */
        const val DEFAULT_TURN_SERVICE = ""
        /** Must match the Worker's APP_KEY secret (keeps casual scrapers away; not a real secret). */
        const val TURN_SERVICE_APP_KEY = "amend-calls-v1"

        private val http by lazy {
            okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        }

        /** Reads the usual `iceServers` JSON (an array, or an object holding one). */
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String?): List<IceServerConfig>? = try {
            val root = app.amend.messenger.core.json.Json.parse(json ?: "")
            val list = when (root) {
                is List<*> -> root
                is Map<*, *> -> (root["iceServers"] as? List<*>) ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
            list.flatMap { e ->
                val m = e as? Map<String, Any?> ?: return@flatMap emptyList()
                val urls = when (val u = m["urls"] ?: m["url"]) {
                    is String -> listOf(u)
                    is List<*> -> u.mapNotNull { it as? String }
                    else -> emptyList()
                }
                val user = m["username"] as? String ?: ""
                val cred = m["credential"] as? String ?: ""
                urls.filter { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") }
                    .map { IceServerConfig(it, user, cred) }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Runs amend's calls: one at a time, set up over Nostr, media over WebRTC.
 * Lives as long as the app process (the live connection keeps it listening).
 */
@SuppressLint("StaticFieldLeak") // only the application context is kept
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var context: Context
    private var repo: AmendRepository? = null
    private var listenJob: Job? = null
    private var timeoutJob: Job? = null
    private var dropJob: Job? = null
    private var session: RtcSession? = null
    private var pendingOffer: CallSignal? = null

    private val _state = MutableStateFlow(CallUi())
    val state: StateFlow<CallUi> = _state.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    /** Set when the user tapped "Answer" in the notification: the call screen answers once permissions are in. */
    val answerRequest = MutableStateFlow(false)

    fun attach(appContext: Context, repository: AmendRepository) {
        context = appContext.applicationContext
        repo = repository
        listenJob?.cancel()
        listenJob = scope.launch { repository.callSignals.collect { onSignal(it) } }
    }

    private fun now() = System.currentTimeMillis() / 1000
    private fun newCallId() = Rand.bytes(12).toHex()

    // ------------------------------------------------------------------ outgoing

    fun startCall(peer: String, video: Boolean) {
        val r = repo ?: return
        if (_state.value.active) return
        val id = newCallId()
        _state.value = CallUi(CallPhase.OUTGOING, peer, id, video, outgoing = true, startedAt = now(), speaker = video)
        CallService.start(context, video)
        startAudio(speakerOn = video)
        Tones.ringback(true)
        scope.launch {
            try {
                val s = newSession(video, CallPrefs(context).resolveIceServers())
                val sdp = s.createOffer()
                if (_state.value.callId != id || !_state.value.active) { // cancelled meanwhile
                    s.close()
                    if (session === s) session = null
                    return@launch
                }
                val sent = r.sendCallSignal(peer, CallSignal(peer, CallSignal.OFFER, id, video, sdp))
                if (sent == 0) finish(CallOutcome.FAILED, "No connection to the network")
            } catch (e: Exception) {
                finish(CallOutcome.FAILED, "Couldn't start the call")
            }
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CallSignal.MAX_RING_SECONDS * 1000)
            val st = _state.value
            if (st.callId == id && st.phase == CallPhase.OUTGOING) {
                // leave a note the friend will see even if their phone was offline
                launchSend(CallSignal(peer, CallSignal.HANGUP, id, video))
                launchSend(CallSignal(peer, CallSignal.MISSED, id, video), stored = true)
                finish(CallOutcome.NO_ANSWER, "No answer")
            }
        }
    }

    // ------------------------------------------------------------------ incoming

    private suspend fun onSignal(sig: CallSignal) {
        val r = repo ?: return
        val st = _state.value
        when (sig.type) {
            CallSignal.OFFER -> {
                if (!sig.isFresh() || sig.sdp.isNullOrBlank()) return
                if (!r.isAccepted(sig.peer)) {
                    // strangers can't ring you — it shows up as a missed call in their request
                    r.logCall(sig.peer, CallLog(sig.callId, false, sig.video, CallOutcome.MISSED, sig.createdAt))
                    return
                }
                if (st.active) {
                    if (st.callId != sig.callId) {
                        launchSend(CallSignal(sig.peer, CallSignal.BUSY, sig.callId, sig.video))
                        r.logCall(sig.peer, CallLog(sig.callId, false, sig.video, CallOutcome.MISSED, sig.createdAt))
                    }
                    return
                }
                pendingOffer = sig
                answerRequest.value = false
                _state.value = CallUi(CallPhase.INCOMING, sig.peer, sig.callId, sig.video, outgoing = false, startedAt = sig.createdAt, speaker = sig.video)
                launchSend(CallSignal(sig.peer, CallSignal.RINGING, sig.callId, sig.video))
                Tones.ring(context, true)
                Notifications.incomingCall(context, sig.peer, r.displayName(sig.peer), sig.video)
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(CallSignal.MAX_RING_SECONDS * 1000)
                    if (_state.value.callId == sig.callId && _state.value.phase == CallPhase.INCOMING) finish(CallOutcome.MISSED, "Missed call")
                }
            }
            CallSignal.MISSED -> {
                // stored note from a call we never saw ring (phone was offline)
                if (st.callId == sig.callId) return
                val known = r.conversations.value[sig.peer]?.messages?.any { it.id == "call-" + sig.callId } == true
                if (!known) r.logCall(sig.peer, CallLog(sig.callId, false, sig.video, CallOutcome.MISSED, sig.createdAt))
            }
            else -> {
                if (st.callId != sig.callId || st.peer != sig.peer) return
                when (sig.type) {
                    CallSignal.RINGING -> if (st.phase == CallPhase.OUTGOING) _state.update { it.copy(ringing = true) }
                    CallSignal.ANSWER -> if (st.phase == CallPhase.OUTGOING && !sig.sdp.isNullOrBlank()) {
                        timeoutJob?.cancel()
                        Tones.ringback(false)
                        _state.update { it.copy(phase = CallPhase.CONNECTING) }
                        runCatching { session?.acceptAnswer(sig.sdp) }.onFailure { finish(CallOutcome.FAILED, "Couldn't connect") }
                        watchConnecting(sig.callId)
                    }
                    CallSignal.REJECT -> if (st.phase == CallPhase.OUTGOING) finish(CallOutcome.DECLINED, "Declined")
                    CallSignal.BUSY -> if (st.phase == CallPhase.OUTGOING) finish(CallOutcome.BUSY, "Busy on another call")
                    CallSignal.HANGUP -> when (st.phase) {
                        CallPhase.INCOMING -> finish(CallOutcome.MISSED, "Missed call")
                        CallPhase.CONNECTED, CallPhase.CONNECTING -> finish(CallOutcome.COMPLETED, "Call ended")
                        CallPhase.OUTGOING -> finish(CallOutcome.DECLINED, "Declined")
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Answer the ringing call. Needs RECORD_AUDIO (and CAMERA for video) already granted. */
    fun answer() {
        val st = _state.value
        val offer = pendingOffer ?: return
        if (st.phase != CallPhase.INCOMING || offer.callId != st.callId) return
        answerRequest.value = false
        timeoutJob?.cancel()
        Tones.ring(context, false)
        Notifications.cancelIncomingCall(context)
        _state.update { it.copy(phase = CallPhase.CONNECTING) }
        CallService.start(context, st.video)
        startAudio(speakerOn = st.video)
        scope.launch {
            try {
                val s = newSession(st.video, CallPrefs(context).resolveIceServers())
                val sdp = s.answer(offer.sdp!!)
                if (_state.value.callId != st.callId || !_state.value.active) {
                    s.close()
                    if (session === s) session = null
                    return@launch
                }
                val sent = repo?.sendCallSignal(st.peer, CallSignal(st.peer, CallSignal.ANSWER, st.callId, st.video, sdp)) ?: 0
                if (sent == 0) finish(CallOutcome.FAILED, "No connection to the network")
                else watchConnecting(st.callId)
            } catch (e: Exception) {
                finish(CallOutcome.FAILED, "Couldn't connect")
            }
        }
    }

    fun decline() {
        val st = _state.value
        if (st.phase != CallPhase.INCOMING) return
        launchSend(CallSignal(st.peer, CallSignal.REJECT, st.callId, st.video))
        finish(CallOutcome.DECLINED, "Declined")
    }

    /** End / cancel whatever call is going on. */
    fun hangUp() {
        val st = _state.value
        when (st.phase) {
            CallPhase.INCOMING -> decline()
            CallPhase.OUTGOING -> {
                launchSend(CallSignal(st.peer, CallSignal.HANGUP, st.callId, st.video))
                finish(CallOutcome.CANCELLED, "Cancelled")
            }
            CallPhase.CONNECTING, CallPhase.CONNECTED -> {
                launchSend(CallSignal(st.peer, CallSignal.HANGUP, st.callId, st.video))
                finish(CallOutcome.COMPLETED, "Call ended")
            }
            else -> Unit
        }
    }

    /** No media after 30 s of "connecting" means the networks can't reach each other (a TURN server would help). */
    private fun watchConnecting(id: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(30_000)
            val st = _state.value
            if (st.callId == id && st.phase == CallPhase.CONNECTING) {
                launchSend(CallSignal(st.peer, CallSignal.HANGUP, id, st.video))
                finish(CallOutcome.FAILED, "Couldn't connect — try adding a TURN server in settings")
            }
        }
    }

    private fun newSession(video: Boolean, servers: List<IceServerConfig>): RtcSession {
        session?.close()
        val s = RtcSession(context, video, servers, object : RtcSession.Listener {
            override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                scope.launch { onConnection(state) }
            }
            override fun onRemoteVideo(track: VideoTrack) {
                scope.launch { _remoteVideo.value = track }
            }
        })
        session = s
        _localVideo.value = s.localVideo
        _state.update { it.copy(frontCamera = s.frontCamera) }
        return s
    }

    private fun onConnection(state: PeerConnection.PeerConnectionState) {
        val st = _state.value
        if (!st.active) return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                timeoutJob?.cancel()
                dropJob?.cancel()
                Tones.ringback(false)
                _state.update { it.copy(phase = CallPhase.CONNECTED, connectedAt = if (it.connectedAt == 0L) now() else it.connectedAt, reconnecting = false) }
                Tones.connected()
                CallService.update(context)
                Proximity.update(context, _state.value)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                // networks switch (Wi-Fi ↔ mobile) — give it a moment to recover
                _state.update { it.copy(reconnecting = true) }
                dropJob?.cancel()
                dropJob = scope.launch {
                    delay(15_000)
                    if (_state.value.reconnecting && _state.value.callId == st.callId) {
                        launchSend(CallSignal(st.peer, CallSignal.HANGUP, st.callId, st.video))
                        finish(CallOutcome.COMPLETED, "Connection lost")
                    }
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                launchSend(CallSignal(st.peer, CallSignal.HANGUP, st.callId, st.video))
                finish(if (st.connectedAt > 0) CallOutcome.COMPLETED else CallOutcome.FAILED,
                    if (st.connectedAt > 0) "Connection lost" else "Couldn't connect — try adding a TURN server in settings")
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ controls

    fun toggleMute() {
        val m = !_state.value.muted
        session?.setMuted(m)
        _state.update { it.copy(muted = m) }
    }

    fun toggleSpeaker() {
        val on = !_state.value.speaker
        setSpeaker(on)
        _state.update { it.copy(speaker = on) }
        Proximity.update(context, _state.value)
    }

    fun toggleCamera() {
        val on = !_state.value.cameraOn
        session?.setCameraOn(on)
        _state.update { it.copy(cameraOn = on) }
    }

    fun switchCamera() {
        session?.switchCamera { front -> scope.launch { _state.update { it.copy(frontCamera = front) } } }
    }

    // ------------------------------------------------------------------ ending

    private fun finish(outcome: CallOutcome, reason: String) {
        val st = _state.value
        if (!st.active) return
        timeoutJob?.cancel()
        dropJob?.cancel()
        Tones.ring(context, false)
        Tones.ringback(false)
        if (outcome != CallOutcome.MISSED || !st.outgoing) Tones.ended()
        session?.close()
        session = null
        pendingOffer = null
        _localVideo.value = null
        _remoteVideo.value = null
        stopAudio()
        Proximity.release()
        CallService.stop(context)
        Notifications.cancelIncomingCall(context)
        val duration = if (st.connectedAt > 0) now() - st.connectedAt else 0
        val log = CallLog(st.callId, st.outgoing, st.video, outcome, st.startedAt, duration)
        repo?.let { r -> scope.launch { r.logCall(st.peer, log) } }
        _state.value = st.copy(phase = CallPhase.ENDED, endReason = reason, reconnecting = false)
        scope.launch {
            delay(1_800)
            if (_state.value.phase == CallPhase.ENDED && _state.value.callId == st.callId) _state.value = CallUi()
        }
    }

    private fun launchSend(signal: CallSignal, stored: Boolean = false) {
        val r = repo ?: return
        scope.launch { runCatching { r.sendCallSignal(signal.peer, signal, stored) } }
    }

    // ------------------------------------------------------------------ audio routing

    private fun audio() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startAudio(speakerOn: Boolean) {
        val am = audio()
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speakerOn)
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        val am = audio()
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                // headset / bluetooth if connected, otherwise the earpiece
                val preferred = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (preferred != null) am.setCommunicationDevice(preferred) else am.clearCommunicationDevice()
            }
        } else {
            am.isSpeakerphoneOn = on
        }
    }

    @Suppress("DEPRECATION")
    private fun stopAudio() {
        val am = audio()
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }
}

/** Ringtone for incoming calls, ring-back tone while calling, short beeps on connect/hang-up. */
private object Tones {
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    fun ring(context: Context, on: Boolean) {
        if (!on) {
            runCatching { ringtone?.stop() }
            ringtone = null
            vibrator?.cancel()
            vibrator = null
            return
        }
        if (ringtone != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600, 800, 1400), 0))
            vibrator = v
        }
    }

    fun ringback(on: Boolean) {
        if (!on) {
            runCatching { ringback?.stopTone(); ringback?.release() }
            ringback = null
            return
        }
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun connected() = beep(ToneGenerator.TONE_PROP_ACK, 150)
    fun ended() = beep(ToneGenerator.TONE_PROP_PROMPT, 250)

    private fun beep(tone: Int, ms: Int) {
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 50)
            g.startTone(tone, ms)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ g.release() }, ms + 100L)
        }
    }
}

/** Turns the screen off when the phone is at your ear during a voice call. */
private object Proximity {
    private var lock: PowerManager.WakeLock? = null

    fun update(context: Context, st: CallUi) {
        val want = st.phase == CallPhase.CONNECTED && !st.video && !st.speaker
        if (want && lock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "amend:call").apply { acquire(4 * 60 * 60 * 1000L) }
            }
        } else if (!want) {
            release()
        }
    }

    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
