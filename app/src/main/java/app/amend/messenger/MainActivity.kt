package app.amend.messenger

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.amend.messenger.ui.AmendRoot
import app.amend.messenger.ui.screens.LockScreen
import app.amend.messenger.ui.theme.AmendTheme
import app.amend.messenger.util.AppLock
import app.amend.messenger.util.AppVisibility
import app.amend.messenger.util.LiveService
import app.amend.messenger.util.StepTracker
import app.amend.messenger.util.CallManager
import app.amend.messenger.util.CallPhase
import app.amend.messenger.util.Mesh
import app.amend.messenger.util.Notifications
import app.amend.messenger.util.PressPattern
import app.amend.messenger.util.Performance
import app.amend.messenger.util.SosManager
import app.amend.messenger.util.SosPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Peer to open from a notification tap. */
    val pendingPeer = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 8.0–8.1: the phone's own PIN screen (no BiometricPrompt yet). */
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        AppLock.authInProgress = false
        if (r.resultCode == RESULT_OK) AppLock.unlock()
    }
    private var offlineJob: Job? = null

    /** True while amend is a small floating window (picture-in-picture) during a video call. */
    val inPictureInPicture = mutableStateOf(false)

    /** A video call with the friend's picture on screen: leaving amend shrinks it into a floating window. */
    private var pipEligible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Screenshots: your own screens can be captured; chats and other people's profiles
        // can't (see ScreenProtection / SecureScreen). Recent apps shows a blank preview while
        // protection is on. All of it can be switched off in Settings → Privacy & security.
        app.amend.messenger.util.ScreenProtection.applyRecents(this)
        useHighestRefreshRate()
        handleIntent(intent)
        val app = application as AmendApp
        setContent {
            val repo by app.repository.collectAsStateWithLifecycle()
            val eco = repo?.eco?.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            val call by CallManager.state.collectAsStateWithLifecycle()
            AmendTheme(efficiency = eco?.value?.efficiencyMode == true, liteMotion = Performance.liteMotion) {
                Box(Modifier.fillMaxSize()) {
                    AmendRoot(app = app, repository = repo, pendingPeer = pendingPeer)
                    // an incoming or ongoing call can still be answered and ended while locked
                    if (locked && !call.active) {
                        LockScreen(onUnlock = ::requestUnlock, onLeave = { moveTaskToBack(true) })
                    }
                }
            }
        }
        lifecycleScope.launch {
            app.repository.collectLatest { repo ->
                if (repo != null && AppVisibility.isForeground) {
                    repo.goOnline()
                    app.rescheduleBackground(repo)
                }
            }
        }
        lifecycleScope.launch {
            combine(CallManager.state, CallManager.remoteVideo) { st, remote ->
                st.video && st.phase == CallPhase.CONNECTED && remote != null
            }.distinctUntilChanged().collect { eligible ->
                pipEligible = eligible
                updatePictureInPicture()
            }
        }
    }

    private val supportsPip: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the system shrinks amend into the window on its own (smooth gesture-nav animation)
            b.setAutoEnterEnabled(pipEligible).setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun updatePictureInPicture() {
        if (supportsPip) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8–11 have no auto-enter: going home during a video call opens the floating window here
        if (Build.VERSION.SDK_INT < 31 && pipEligible && supportsPip) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // MainActivity is exported (it's the launcher): only act on extras from amend's own notifications
        if (!app.amend.messenger.util.IntentAuth.isOurs(this, intent)) return
        intent?.getStringExtra(Notifications.EXTRA_PEER)?.let { pendingPeer.value = it }
        // a fake call rings over the lock screen like a real one
        if (intent?.getBooleanExtra(app.amend.messenger.util.FakeCall.EXTRA, false) == true) showOverLockScreen(true)
        intent?.getStringExtra(Notifications.EXTRA_CALL)?.let { mode ->
            // an incoming call opens amend over the lock screen
            showOverLockScreen(true)
            if (mode == "answer") CallManager.answerRequest.value = true
        }
    }

    /**
     * Ask for the display's fastest mode (90/120 Hz) at the current resolution, so
     * scrolling and animations look as fluid as Telegram's. Skipped in efficiency mode.
     */
    @Suppress("DEPRECATION")
    private fun useHighestRefreshRate() {
        val app = application as AmendApp
        if (app.repository.value?.eco?.value?.efficiencyMode == true) return
        if (Performance.liteMotion) return // a low-end phone: 60 Hz saves CPU and battery
        runCatching {
            val d: android.view.Display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
            val current = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.modeId != current.modeId || best.refreshRate > 61f) {
                window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** SOS trigger while the app is open: any volume button pressed 4 times within 3 seconds. */
    private val volumePattern = PressPattern(presses = 4, windowMs = 3_000)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val isVolume = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        if (isVolume && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // during a call the volume keys are for the call volume
            if (!CallManager.state.value.active && SosPrefs(this).volumeTrigger && volumePattern.press()) {
                SosManager.trigger(this)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Shows the phone's unlock prompt: fingerprint, face, or the phone's PIN/pattern/password. */
    fun requestUnlock() {
        if (!AppLock.locked.value || AppLock.authInProgress) return
        if (!AppLock.deviceSecure(this)) { AppLock.setEnabled(this, false); return }
        AppLock.authInProgress = true
        val title = app.amend.messenger.ui.tr("Unlock amend")
        if (Build.VERSION.SDK_INT >= 28) {
            val builder = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            runCatching {
                builder.build().authenticate(
                    android.os.CancellationSignal(), mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                            AppLock.unlock()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            AppLock.authInProgress = false
                        }
                    },
                )
            }.onFailure { AppLock.authInProgress = false }
        } else {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = km?.createConfirmDeviceCredentialIntent(title, null)
            if (intent == null) { AppLock.authInProgress = false; return }
            runCatching { confirmCredential.launch(intent) }.onFailure { AppLock.authInProgress = false }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
        AppLock.onStart(this)
        offlineJob?.cancel()
        val app = application as AmendApp
        SosManager.restoreIfNeeded(this)
        StepTracker.start(this, "ui")
        Mesh.resumeIfEnabled(this)
        app.repository.value?.let { repo ->
            repo.goOnline()
            // Starting the live service is only allowed while the app is visible.
            app.rescheduleBackground(repo)
        }
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.isForeground = false
        AppLock.onStop()
        StepTracker.stop("ui")
        // switch the launcher icon while amend is leaving the screen (least disruptive moment)
        runCatching { app.amend.messenger.util.DynamicIcon.update(this) }
        // Stay connected briefly so quick app switches don't drop the connection.
        offlineJob = lifecycleScope.launch {
            delay(30_000)
            val repo = (application as AmendApp).repository.value ?: return@launch
            // With instant notifications the live service keeps the connection open.
            if (!LiveService.isRunning) repo.goOffline()
        }
    }
}
