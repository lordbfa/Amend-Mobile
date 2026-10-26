package app.amend.messenger

import android.app.Application
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.NativeSecp
import app.amend.messenger.data.SecureKeyStore
import app.amend.messenger.util.Notifications
import app.amend.messenger.util.SyncScheduler
import app.amend.messenger.data.AmendEvent
import app.amend.messenger.data.MessageType
import app.amend.messenger.util.AppVisibility
import app.amend.messenger.util.LiveService
import app.amend.messenger.util.StepTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AmendApp : Application() {

    lateinit var keyStore: SecureKeyStore
        private set

    private val _repository = MutableStateFlow<AmendRepository?>(null)
    /** Null until the user has created or restored an identity. */
    val repository: StateFlow<AmendRepository?> = _repository

    var nativeCrypto = false
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nativeCrypto = NativeSecp.install()
        keyStore = SecureKeyStore(this)
        Notifications.createChannels(this)
        StepTracker.init(this)
        // OpenStreetMap tiles: identify the app (tile usage policy) and keep the cache private.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = "amend-android/" + packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
        keyStore.load()?.let { openRepository(it) }
    }

    private fun dataDir() = File(filesDir, "amend")

    fun openRepository(keys: KeyPair): AmendRepository {
        _repository.value?.let { if (it.me == keys.publicKeyHex) return it }
        val repo = AmendRepository(dataDir(), keys)
        repo.start()
        _repository.value = repo
        app.amend.messenger.util.CallManager.attach(this, repo)
        app.amend.messenger.util.EcoMap.attach(this, repo)
        app.amend.messenger.util.EcoWeather.schedule(this)
        eventsJob?.cancel()
        eventsJob = appScope.launch { repo.events.collect { notifyFor(repo, it) } }
        rescheduleBackground(repo)
        return repo
    }

    /**
     * The single place that turns events into system notifications — whether they
     * come from the open app, the live connection service or background sync.
     */
    private fun notifyFor(repo: AmendRepository, e: AmendEvent) {
        when (e) {
            is AmendEvent.MessageArrived -> {
                val chatOnScreen = AppVisibility.isForeground && AppVisibility.openPeer == e.peer
                // Don't notify for old history fetched after restoring a key.
                val recent = e.message.createdAt >= System.currentTimeMillis() / 1000 - 6 * 3600
                if (chatOnScreen || !recent) return
                val text = if (e.message.type == MessageType.ECO_CARD) "🌱 sent you an eco challenge" else e.message.text
                val unread = repo.conversations.value[e.peer]?.unread ?: 1
                val name = repo.displayName(e.peer) + if (repo.isAccepted(e.peer)) "" else " (new request)"
                Notifications.message(this, e.peer, name, text, unread.coerceAtLeast(1))
            }
            is AmendEvent.SosReceived -> {
                // Always alert for a friend's SOS, even if their chat is open.
                Notifications.sosAlert(this, e.peer, repo.displayName(e.peer), e.sos, e.phase)
            }
            is AmendEvent.TreeGrown -> if (!AppVisibility.isForeground) {
                Notifications.treeGrown(this, e.tree.species.name, e.tree.index + 1)
            }
            else -> Unit
        }
    }

    /** Applies the current notification/sync settings: live connection and/or periodic checks. */
    fun rescheduleBackground(repository: AmendRepository? = _repository.value) {
        val repo = repository ?: return
        val eco = repo.eco.value
        // Periodic sync always runs as a safety net; it skips itself while the live connection is up.
        SyncScheduler.schedule(this, eco.carbonAwareSync && !eco.instantNotifications, eco.efficiencyMode)
        if (eco.instantNotifications) LiveService.start(this) else LiveService.stop(this)
    }

    fun createIdentity(keys: KeyPair): AmendRepository {
        keyStore.save(keys)
        return openRepository(keys)
    }

    /** Removes the identity and all local data from this device. */
    fun signOut() {
        LiveService.stop(this)
        eventsJob?.cancel()
        _repository.value?.goOffline()
        _repository.value = null
        keyStore.clear()
        dataDir().deleteRecursively()
        SyncScheduler.cancel(this)
    }

    companion object {
        lateinit var instance: AmendApp
            private set
    }
}
