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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        app.amend.messenger.ui.I18n.init(this)
        app.amend.messenger.ui.theme.AppThemes.load(this)
        app.amend.messenger.util.CheckIn.restore(this)
        app.amend.messenger.util.ScreenProtection.load(this)
        app.amend.messenger.util.AgeCheck.load(this)
        app.amend.messenger.net.TorProxy.enabled = getSharedPreferences("amend_net", MODE_PRIVATE).getBoolean("tor", false)
        Notifications.createChannels(this)
        StepTracker.init(this)
        app.amend.messenger.util.DynamicIcon.schedule(this)
        // OpenStreetMap tiles: identify the app (tile usage policy) and keep the cache private.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = "amend-android/" + packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
        keyStore.load()?.let { openRepository(it) }
        // the identity is loaded now, so a check-in that ran out while amend was closed can start the SOS
        runCatching { app.amend.messenger.util.CheckIn.rearm(this) }
        runCatching { app.amend.messenger.util.FakeCall.rearm(this) }
        checkBurnerExpiry()
        appScope.launch {
            while (true) { kotlinx.coroutines.delay(60_000); runCatching { checkBurnerExpiry() } }
        }
    }

    private fun dataDir() = File(filesDir, "amend")

    fun openRepository(keys: KeyPair): AmendRepository {
        _repository.value?.let { if (it.me == keys.publicKeyHex) return it }
        val repo = AmendRepository(dataDir(), keys)
        repo.blockStrangers = app.amend.messenger.util.AgeCheck.shielded
        repo.externalStats = {
            val mine = app.amend.messenger.util.EcoMap.reports.value.values.count { it.author == repo.me }.toLong()
            mine to app.amend.messenger.util.StepTracker.ui.value.monthKm.toLong()
        }
        // Listen for events BEFORE the repository starts: messages that were waiting on the
        // relays arrive within moments of connecting, and an event emitted before anyone
        // listens is lost (that is how a first message or a message request went unnotified).
        notifySince = notifySinceFor(repo.me)
        eventsJob?.cancel()
        eventsJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.events.collect { notifyFor(repo, it) } }
        repo.start()
        _repository.value = repo
        app.amend.messenger.util.CallManager.attach(this, repo)
        app.amend.messenger.util.EcoMap.attach(this, repo)
        app.amend.messenger.util.Mesh.init(this)
        app.amend.messenger.util.Mesh.attach(repo)
        app.amend.messenger.util.EcoWeather.schedule(this)
        runCatching { app.amend.messenger.util.WearSync.attach(this, repo) }
        rescheduleBackground(repo)
        // keep the wake-up for scheduled messages pointed at the next one
        appScope.launch {
            repo.conversations
                .map { repo.nextScheduledAt() }
                .distinctUntilChanged()
                .collect { runCatching { app.amend.messenger.util.ScheduledSend.reschedule(this@AmendApp, repo) } }
        }
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
                // Don't notify for old history fetched after restoring a key on this phone, but do
                // notify for anything that arrived while the phone was off or offline (up to a week).
                val now = System.currentTimeMillis() / 1000
                val recent = e.message.createdAt >= notifySince - 60 && e.message.createdAt >= now - 7 * 24 * 3600
                if (chatOnScreen || !recent) return
                val base = if (e.message.type == MessageType.ECO_CARD) "🌱 sent you an eco challenge" else e.message.text
                // in a shared garden, say who wrote it
                val text = e.message.author?.takeIf { repo.isGroup(e.peer) }?.let { repo.displayName(it) + ": " + base } ?: base
                val unread = repo.conversations.value[e.peer]?.unread ?: 1
                val name = repo.displayName(e.peer) + if (repo.isAccepted(e.peer)) "" else " (new request)"
                Notifications.message(this, e.peer, name, text, unread.coerceAtLeast(1))
            }
            is AmendEvent.SosReceived -> {
                // Always alert for a friend's SOS, even if their chat is open.
                Notifications.sosAlert(this, e.peer, repo.displayName(e.peer), e.sos, e.phase)
            }
            is AmendEvent.AchievementUnlocked -> if (!AppVisibility.isForeground) {
                app.amend.messenger.eco.Achievements.parse(e.key)?.let { (a, t) ->
                    Notifications.achievement(this, "${a.emoji} ${a.title} · ${t.label}", "Achievement unlocked — ${a.describe(t)}", e.key.hashCode())
                }
            }
            is AmendEvent.TreeGrown -> if (!AppVisibility.isForeground) {
                Notifications.treeGrown(this, e.tree.species.name, e.tree.index + 1)
            }
            else -> Unit
        }
    }

    /** Applies the current notification/sync settings: live connection and/or periodic checks. */
    /** Messages older than this (when this identity was set up on this phone) are history, not news. */
    @Volatile private var notifySince = 0L

    private fun notifySinceFor(me: String): Long {
        val prefs = getSharedPreferences("amend_notify", MODE_PRIVATE)
        val key = "since_$me"
        val saved = prefs.getLong(key, 0L)
        if (saved > 0) return saved
        // First start of this identity here (new install, new identity or restored key).
        // Existing users updating the app keep getting notified for the last 6 hours.
        val now = System.currentTimeMillis() / 1000
        val since = if (prefs.getBoolean("upgraded", false) || !File(dataDir(), "settings.json").exists()) now else now - 6 * 3600
        prefs.edit().putLong(key, since).putBoolean("upgraded", true).apply()
        return since
    }

    fun rescheduleBackground(repository: AmendRepository? = _repository.value) {
        val repo = repository ?: return
        val eco = repo.eco.value
        // Periodic sync always runs as a safety net; it skips itself while the live connection is up.
        SyncScheduler.schedule(this, eco.carbonAwareSync && !eco.instantNotifications, eco.efficiencyMode)
        if (eco.instantNotifications) LiveService.start(this) else LiveService.stop(this)
    }

    fun createIdentity(keys: KeyPair): AmendRepository {
        // An identity is stored but couldn't be opened: keep its chats aside (and the key, see
        // SecureKeyStore.save) instead of mixing them into the new identity or deleting them.
        if (_repository.value == null && keyStore.hasUnreadableIdentity() && dataDir().exists()) {
            dataDir().renameTo(File(filesDir, "amend-unreadable-" + System.currentTimeMillis()))
        }
        keyStore.save(keys)
        return openRepository(keys)
    }

    // ---------------------------------------------------------------- burner identity

    private val burnerPrefs get() = getSharedPreferences("amend_burner", MODE_PRIVATE)

    /** When the current burner identity self-destructs (epoch seconds), or 0 if it isn't one. */
    fun burnerExpiresAt(): Long = burnerPrefs.getLong("expires", 0L)

    /**
     * Throws away this identity and all its data, and starts a fresh random one that
     * deletes itself after [lifetimeSecs] (0 = keep until removed). Nothing links the two.
     */
    fun startBurner(lifetimeSecs: Long): AmendRepository {
        signOut()
        val expires = if (lifetimeSecs > 0) System.currentTimeMillis() / 1000 + lifetimeSecs else 0L
        burnerPrefs.edit().putLong("expires", expires).putBoolean("burner", true).apply()
        val repo = createIdentity(KeyPair.generate())
        repo.goOnline()
        return repo
    }

    fun isBurner(): Boolean = burnerPrefs.getBoolean("burner", false)

    /** Wipes an expired burner identity (checked at start and every minute). */
    fun checkBurnerExpiry() {
        val exp = burnerExpiresAt()
        if (exp > 0 && System.currentTimeMillis() / 1000 >= exp && _repository.value != null) {
            signOut()
        }
    }

    /** Removes the identity and all local data from this device. */
    fun signOut() {
        burnerPrefs.edit().clear().apply()
        // stop anything still running for this identity (tells friends live location stopped)
        runCatching { app.amend.messenger.util.LiveShareManager.shares.value.keys.toList().forEach { app.amend.messenger.util.LiveShareManager.stop(this, it) } }
        runCatching { app.amend.messenger.util.CheckIn.safe(this) }
        runCatching { app.amend.messenger.util.FakeCall.cancelScheduled(this) }
        LiveService.stop(this)
        eventsJob?.cancel()
        _repository.value?.goOffline()
        _repository.value = null
        keyStore.clear()
        dataDir().deleteRecursively()
        wipeIdentityTraces()
        SyncScheduler.cancel(this)
    }

    /**
     * Everything outside the data folder that belongs to the identity: public keys of
     * contacts, chat ids, carried mesh messages, photos and recordings in the cache. Without
     * this a "burner" could be linked to the identity before it. Settings that aren't about
     * who you are (language, theme, steps) stay.
     */
    private fun wipeIdentityTraces() {
        listOf("amend_notify", "amend_live_share", "amend_checkin", "amend_eco_map", "amend_fakecall")
            .forEach { runCatching { getSharedPreferences(it, MODE_PRIVATE).edit().clear().apply() } }
        runCatching {
            val sos = app.amend.messenger.util.SosPrefs(this)
            sos.excluded = emptySet()
            sos.activeRecipients = emptySet()
            sos.activeSince = 0
        }
        runCatching { app.amend.messenger.util.IntentAuth.reset(this) }
        runCatching { app.amend.messenger.util.Mesh.wipe() }
        runCatching { File(filesDir, "mesh.json").delete() }
        // photos, voice and video drafts, avatars; map tiles hold nothing personal and stay
        runCatching { cacheDir.listFiles()?.filter { it.name != "osmdroid" }?.forEach { it.deleteRecursively() } }
        runCatching { Notifications.cancelAll(this) }
    }

    companion object {
        lateinit var instance: AmendApp
            private set
    }
}
