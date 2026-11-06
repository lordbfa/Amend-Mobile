package app.amend.messenger

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.amend.messenger.ui.AmendRoot
import app.amend.messenger.ui.theme.AmendTheme
import app.amend.messenger.util.AppVisibility
import app.amend.messenger.util.LiveService
import app.amend.messenger.util.StepTracker
import app.amend.messenger.util.CallManager
import app.amend.messenger.util.Mesh
import app.amend.messenger.util.Notifications
import app.amend.messenger.util.PressPattern
import app.amend.messenger.util.SosManager
import app.amend.messenger.util.SosPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Peer to open from a notification tap. */
    val pendingPeer = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var offlineJob: Job? = null

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
            AmendTheme(efficiency = eco?.value?.efficiencyMode == true) {
                AmendRoot(app = app, repository = repo, pendingPeer = pendingPeer)
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

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
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
