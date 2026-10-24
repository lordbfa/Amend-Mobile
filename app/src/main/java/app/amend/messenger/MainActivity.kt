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
import app.amend.messenger.data.AmendEvent
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.AmendRoot
import app.amend.messenger.ui.theme.AmendTheme
import app.amend.messenger.util.AppVisibility
import app.amend.messenger.util.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Peer to open from a notification tap. */
    val pendingPeer = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var offlineJob: Job? = null
    private var eventsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                eventsJob?.cancel()
                if (repo != null) {
                    if (AppVisibility.isForeground) repo.goOnline()
                    eventsJob = launch { forwardNotifications(repo) }
                }
            }
        }
    }

    /** While the app is open, still notify for chats that aren't on screen. */
    private suspend fun forwardNotifications(repo: AmendRepository) {
        repo.events.collect { e ->
            when (e) {
                is AmendEvent.MessageArrived -> if (AppVisibility.openPeer != e.peer && !AppVisibility.isForeground) {
                    Notifications.message(this, e.peer, repo.displayName(e.peer), e.message.text)
                }
                is AmendEvent.TreeGrown -> if (!AppVisibility.isForeground) {
                    Notifications.treeGrown(this, e.tree.species.name, e.tree.index + 1)
                }
                else -> Unit
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_PEER)?.let { pendingPeer.value = it }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
        offlineJob?.cancel()
        (application as AmendApp).repository.value?.goOnline()
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.isForeground = false
        // Stay connected briefly so quick app switches don't drop the connection.
        offlineJob = lifecycleScope.launch {
            delay(30_000)
            (application as AmendApp).repository.value?.goOffline()
        }
    }
}
