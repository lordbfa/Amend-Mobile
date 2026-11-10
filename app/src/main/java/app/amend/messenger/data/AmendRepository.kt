package app.amend.messenger.data

import app.amend.messenger.core.RateWindow
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.crypto.hexToBytes
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.isHex64
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.eco.Challenges
import app.amend.messenger.eco.EcoPoints
import app.amend.messenger.eco.PlantedTree
import app.amend.messenger.net.RelayIncoming
import app.amend.messenger.net.RelayPool
import app.amend.messenger.net.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** Things the UI animates or the OS should notify about. */
sealed class AmendEvent {
    data class MessageArrived(val peer: String, val message: ChatMessage) : AmendEvent()
    data class TreeGrown(val tree: PlantedTree) : AmendEvent()
    data class PointsEarned(val points: Int, val reason: String) : AmendEvent()
    data class MessageSent(val peer: String, val relays: Int) : AmendEvent()
    /** A friend started, updated or ended an SOS. */
    data class SosReceived(val peer: String, val sos: IncomingSos, val phase: SosPhase) : AmendEvent()
    /** A new achievement tier ("id:TIER"). */
    data class AchievementUnlocked(val key: String) : AmendEvent()
}

/**
 * The heart of amend: identity, conversations, relay networking and eco progress.
 * Pure Kotlin/JVM (no Android classes), so it's also exercised by the CI relay test.
 */
class AmendRepository(
    private val dir: File,
    val keys: KeyPair,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val me: String = keys.publicKeyHex
    private val store = Store(dir, Store.keyFor(keys.secretKey))
    private val mutex = Mutex()

    private var settings = store.loadSettings(RelayPool.DEFAULT_RELAYS).let { s ->
        // Upgrade installs still on the original relay list (two of those relays proved unreliable).
        if (RelayPool.PREVIOUS_DEFAULTS.any { it.sorted() == s.relays.sorted() }) s.copy(relays = RelayPool.DEFAULT_RELAYS) else s
    }.let { s ->
        // relays saved before TLS was required: drop any ws:// one
        val tls = s.relays.filter { it.startsWith("wss://") }
        if (tls.size == s.relays.size) s else s.copy(relays = tls.ifEmpty { RelayPool.DEFAULT_RELAYS })
    }

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    private val _groups = MutableStateFlow(settings.groups)
    /** Shared gardens (encrypted group chats) by key. */
    val groups: StateFlow<Map<String, GroupInfo>> = _groups.asStateFlow()

    fun isGroup(peer: String) = GroupInfo.isKey(peer)
    fun groupInfo(peer: String): GroupInfo? = _groups.value[peer]

    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    private val _eco = MutableStateFlow(store.loadEco())
    val eco: StateFlow<EcoState> = _eco.asStateFlow()

    private val _friendStats = MutableStateFlow(store.loadFriendStats())
    /** Contacts who share their forest with me (friends' leaderboard). */
    val friendStats: StateFlow<Map<String, FriendStats>> = _friendStats.asStateFlow()

    private val _relays = MutableStateFlow(settings.relays)
    val relayUrls: StateFlow<List<String>> = _relays.asStateFlow()

    private val _events = MutableSharedFlow<AmendEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AmendEvent> = _events

    private val _liveShares = MutableStateFlow<Map<String, LiveShare>>(emptyMap())
    /** Live locations being shared in chats, by "chat|sender". */
    val liveShares: StateFlow<Map<String, LiveShare>> = _liveShares.asStateFlow()

    private val _incomingSos = MutableStateFlow<Map<String, IncomingSos>>(emptyMap())
    /** Friends' SOS alerts (active and recently ended), by public key. */
    val incomingSos: StateFlow<Map<String, IncomingSos>> = _incomingSos.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var inboxRelays = store.loadInboxRelays()
    /** Edits that arrived before their message: id → (author, time, new text). */
    private val pendingEdits = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, String>>()

    /** Under-18 "stranger shield": message requests from people you haven't accepted are dropped. */
    @Volatile var blockStrangers: Boolean = false

    /** Notes or authors hidden from the Grove on this phone (after "Hide" / "Report"). */
    private val hiddenNotes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val _modBlocked = MutableStateFlow(settings.modBlocked)
    /** Accounts blocked for everyone by amend's moderator. */
    val modBlocked: StateFlow<Set<String>> = _modBlocked.asStateFlow()

    private val seenWraps = LinkedHashSet(settings.seenWraps)

    private val pendingBytesSent = AtomicLong()
    private val pendingBytesReceived = AtomicLong()

    val pool = RelayPool(
        scope = scope,
        authSigner = { challenge, url ->
            Event.sign(keys.secretKey, Kinds.CLIENT_AUTH, "", listOf(listOf("relay", url), listOf("challenge", challenge)))
        },
        onBytes = { s, r ->
            if (s > 0) pendingBytesSent.addAndGet(s)
            if (r > 0) pendingBytesReceived.addAndGet(r)
        },
    )
    val relayStatuses: StateFlow<List<RelayStatus>> get() = pool.statuses

    private var flushJob: Job? = null
    private var started = false
    private val eoseFrom = HashSet<String>()

    init {
        // chats with accounts on the moderator's blocklist stay on disk but out of sight
        val chats = store.loadChats().filterKeys { it !in settings.modBlocked }
        _conversations.value = chats.mapValues { (peer, msgs) ->
            Conversation(
                peer = peer,
                messages = msgs.sortedBy { it.createdAt },
                pinned = peer in settings.pinned,
                nickname = settings.nicknames[peer] ?: "",
                disappearSecs = settings.disappear[peer] ?: 0,
            )
        }
        pool.setRelays(settings.relays)
        // files an older version left as plain text are sealed once, in the background
        scope.launch(Dispatchers.IO) { runCatching { store.sealExisting() } }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        scope.launch { pool.incoming.collect { handleIncoming(it) } }
        scope.launch {
            while (true) {
                delay(5_000)
                flushBytes()
            }
        }
        // disappearing messages and scheduled sends
        scope.launch {
            while (true) {
                runCatching { purgeExpired() }
                runCatching { purgeStories() }
                runCatching { sendDueScheduled() }
                runCatching { sendEcoStatsIfDue() }
                runCatching { checkAchievements() }
                delay(20_000)
            }
        }
        subscribeAll()
        markActiveToday()
    }

    fun goOnline() {
        start()
        pool.connect()
    }

    fun goOffline() {
        flushBytes()
        pool.disconnect()
    }

    /** Connects, waits for stored events (or [timeoutMs]), then disconnects. Used by background sync. */
    suspend fun syncOnce(timeoutMs: Long = 25_000): Int {
        val before = _conversations.value.values.sumOf { it.messages.size }
        start()
        _syncing.value = true
        eoseFrom.clear()
        pool.connect()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(500)
                val online = pool.onlineCount()
                if (online > 0 && eoseFrom.size >= online) break
            }
        }
        delay(1_500) // let the last events get processed
        runCatching { flushDelivered() }
        pool.disconnect()
        _syncing.value = false
        flushBytes()
        return _conversations.value.values.sumOf { it.messages.size } - before
    }

    private fun subscribeAll() {
        val since = if (settings.lastSync == 0L) null else settings.lastSync - GiftWrap.TWO_DAYS - 600
        val dmFilter = linkedMapOf<String, Any?>("kinds" to listOf(Kinds.GIFT_WRAP), "#p" to listOf(me))
        if (since != null) dmFilter["since"] = since else dmFilter["limit"] = 1000
        // live call signals: only what's sent from now on
        val liveFilter = linkedMapOf<String, Any?>("kinds" to listOf(Kinds.EPHEMERAL_GIFT_WRAP), "#p" to listOf(me), "since" to Event.nowSeconds() - 30)
        pool.subscribe(SUB_DM, listOf(dmFilter, liveFilter))
        pool.subscribe(Moderation.SUB, listOf(Moderation.filter))
        resubscribeMetadata()
    }

    private fun resubscribeMetadata() {
        val authors = (_conversations.value.keys + settings.accepted + me + _groups.value.values.flatMap { it.members })
            .filter { it.length == 64 }.distinct()
        pool.subscribe(SUB_META, listOf(linkedMapOf("kinds" to listOf(Kinds.METADATA, Kinds.DM_RELAYS), "authors" to authors)))
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun handleIncoming(msg: RelayIncoming) {
        when (msg) {
            is RelayIncoming.EventMsg -> if (msg.subscription == Moderation.SUB) {
                applyModerationList(msg.event)
            } else if (msg.subscription == SUB_FEED) {
                if (msg.event.kind == Kinds.TEXT_NOTE && msg.event.isValid()) addNote(msg.event)
            } else if (msg.subscription.startsWith(SUB_ECO_PREFIX)) {
                // public eco-map reports and their reactions (see EcoMap)
                if (msg.event.isValid()) _ecoEvents.tryEmit(msg.event)
            } else when (msg.event.kind) {
                Kinds.GIFT_WRAP, Kinds.EPHEMERAL_GIFT_WRAP -> handleWrap(msg.event)
                Kinds.METADATA -> handleMetadata(msg.event)
                Kinds.DM_RELAYS -> handleInboxRelays(msg.event)
            }
            is RelayIncoming.EndOfStored -> if (msg.subscription == SUB_DM) {
                eoseFrom.add(msg.relay)
                settings = settings.copy(lastSync = Event.nowSeconds())
                persistSettings()
            }
            else -> Unit
        }
    }

    private suspend fun handleWrap(wrap: Event) {
        mutex.withLock { if (wrap.id in seenWraps) return }
        // Check the id and signature before remembering the id: a forged event that only
        // copies a real gift wrap's id (from a dishonest relay or a nearby mesh phone) must
        // never make us skip the real one when it arrives.
        if (!wrap.isValid()) return
        mutex.withLock {
            if (!seenWraps.add(wrap.id)) return
        }
        val rumor = try {
            GiftWrap.unwrap(wrap, keys, wrapVerified = true)
        } catch (e: Exception) {
            return
        }
        val fromMe = rumor.pubkey == me
        // NIP-17: the room is the sender plus everyone in the "p" tags. Three or more people = a shared garden.
        // only real keys (64 hex digits): a "p" tag is whatever the sender wrote
        val participants = (rumor.tagValues("p").map { it.lowercase() } + rumor.pubkey).filter { it.isHex64() }.distinct()
        val inGroup = participants.size >= 3
        val peer = when {
            inGroup -> GroupInfo.keyFor(participants)
            fromMe -> rumor.tagValues("p").firstOrNull { it != me } ?: me
            else -> rumor.pubkey
        }
        if (peer in settings.blocked || (!fromMe && rumor.pubkey in settings.blocked)) return
        if (!fromMe && rumor.pubkey in settings.modBlocked) return
        // Stranger shield (under-18s): only people you've accepted, or chats you started, can reach you
        if (blockStrangers && !fromMe && peer !in settings.accepted &&
            (if (inGroup) rumor.pubkey !in settings.accepted else _conversations.value[peer]?.messages?.any { it.fromMe } != true)
        ) return
        if (inGroup) registerGroup(peer, participants, rumor)
        val author = if (inGroup) rumor.pubkey else null
        // disappearing messages: drop anything already past its time
        val expiresAt = rumor.firstTag("expiration")?.toLongOrNull() ?: 0L
        if (expiresAt in 1..Event.nowSeconds()) return
        fun finish(m: ChatMessage): ChatMessage {
            var x = m.copy(author = author, expiresAt = expiresAt)
            // this message was unsent before its copy reached us
            if (settings.unsent[x.id] == rumor.pubkey) x = tombstone(x)
            // an edit that arrived before the message itself
            pendingEdits.remove(x.id)?.let { (by, at, text) ->
                if (by == rumor.pubkey && x.type == MessageType.TEXT && at - x.createdAt <= UNSEND_WINDOW + 300) x = x.copy(text = text, editedAt = at)
            }
            return x
        }
        if (rumor.kind == Kinds.EDIT) {
            val text = rumor.content.take(MAX_EDIT_CHARS)
            rumor.firstTag("e")?.let { if (text.isNotBlank()) applyEdit(peer, it, rumor.pubkey, rumor.createdAt, text) }
            return
        }
        if (rumor.kind == Kinds.DELETION && rumor.firstTag("k") == Kinds.STORY.toString()) {
            rumor.tagValues("e").forEach { removeStory(it, rumor.pubkey) }
            return
        }
        if (rumor.kind == Kinds.DELETION) {
            rumor.tagValues("e").forEach { applyUnsend(peer, it, rumor.pubkey, rumor.createdAt) }
            persistSettingsSoon()
            return
        }
        if (rumor.kind == Kinds.CHAT_MESSAGE) {
            LiveProtocol.parse(rumor)?.let { live ->
                handleLive(rumor, peer, fromMe, live)
                persistSettingsSoon()
                return
            }
        }
        if (rumor.kind == Kinds.CHAT_MESSAGE && !inGroup) {
            SosProtocol.parse(rumor)?.let { signal ->
                handleSos(rumor, peer, fromMe, signal)
                persistSettingsSoon()
                return
            }
        }
        when (rumor.kind) {
            Kinds.FILE_MESSAGE -> {
                val media = MediaInfo.fromRumor(rumor) ?: return
                val message = ChatMessage(
                    id = rumor.id, peer = peer, fromMe = fromMe,
                    text = rumor.firstTag("amend-caption")?.take(MAX_CAPTION)?.ifBlank { null } ?: media.kind.label,
                    createdAt = rumor.createdAt,
                    type = media.kind.messageType,
                    replyTo = rumor.firstTag("e"),
                    status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                    extra = media.toJson(),
                ).let(::finish)
                val added = upsertMessage(message, countUnread = !fromMe && message.type != MessageType.UNSENT)
                if (added && !fromMe && message.type != MessageType.UNSENT) {
                    recordMessageStats(received = true)
                    _events.tryEmit(AmendEvent.MessageArrived(peer, message))
                    queueDelivered(message)
                }
            }
            Kinds.CHAT_MESSAGE -> {
                val disappear = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "disappear" }?.get(2)?.toLongOrNull()
                val groupNote = rumor.tags.any { it.size >= 3 && it[0] == "amend" && it[1] == "group" }
                if (disappear != null) setDisappearLocal(peer, disappear, rumor.createdAt)
                if (disappear != null || groupNote) {
                    val note = ChatMessage(
                        id = rumor.id, peer = peer, fromMe = fromMe, text = rumor.content, createdAt = rumor.createdAt,
                        type = MessageType.NOTICE, status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED, receipt = 2,
                    ).let(::finish)
                    upsertMessage(note, countUnread = false)
                    persistSettingsSoon()
                    return
                }
                val achievement = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "achievement" }?.get(2)
                    ?.takeIf { app.amend.messenger.eco.Achievements.parse(it) != null }
                val cardId = achievement?.let { "ach:$it" }
                    ?: rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "card" }?.get(2)
                val proof = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "proof" }?.get(2)
                val message = if (proof != null) ChatMessage(
                    id = rumor.id, peer = peer, fromMe = fromMe, text = rumor.content, createdAt = rumor.createdAt,
                    type = MessageType.ECO_PROOF, replyTo = rumor.firstTag("e"),
                    status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                    extra = app.amend.messenger.core.json.Json.write(
                        linkedMapOf(
                            "a" to proof, "image" to rumor.firstTag("image"), "location" to rumor.firstTag("location"),
                            "event" to rumor.firstTag("amend-event"),
                        )
                    ),
                ) else ChatMessage(
                    id = rumor.id,
                    peer = peer,
                    fromMe = fromMe,
                    text = rumor.content,
                    createdAt = rumor.createdAt,
                    type = if (cardId != null && (achievement != null || Challenges.byId(cardId) != null)) MessageType.ECO_CARD else MessageType.TEXT,
                    cardId = cardId,
                    replyTo = rumor.firstTag("e"),
                    status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                )
                val final = finish(message)
                val added = upsertMessage(final, countUnread = !fromMe && final.type != MessageType.UNSENT)
                if (added && !fromMe && final.type != MessageType.UNSENT) {
                    recordMessageStats(received = true)
                    _events.tryEmit(AmendEvent.MessageArrived(peer, final))
                    queueDelivered(final)
                }
            }
            Kinds.REACTION -> {
                val target = rumor.firstTag("e") ?: return
                applyReaction(peer, target, rumor.pubkey, rumor.content.take(8))
            }
            Kinds.RECEIPT -> if (!fromMe) applyReceipt(peer, rumor)
            Kinds.INSTANT_OPENED -> rumor.firstTag("e")?.let { id -> if (fromMe) consumeInstantLocally(id) else markInstantOpened(id, rumor.pubkey) }
            Kinds.STORY -> if (fromMe || (!inGroup && peer in settings.accepted)) handleStory(rumor, fromMe)
            Kinds.STORY_VIEW -> if (!fromMe) rumor.firstTag("e")?.let { applyStoryView(it, rumor.pubkey, rumor.createdAt) }
            Kinds.ECO_STATS -> if (!fromMe && !inGroup && peer in settings.accepted) applyFriendStats(peer, rumor)
            Kinds.CALL_SIGNAL -> if (!fromMe && !inGroup) {
                CallSignal.parse(peer, rumor, ephemeral = wrap.kind == Kinds.EPHEMERAL_GIFT_WRAP)?.let { _callSignals.tryEmit(it) }
                return
            }
        }
        persistSettingsSoon()
    }

    private suspend fun handleSos(rumor: Event, peer: String, fromMe: Boolean, signal: SosSignal) {
        // Start and stop become visible messages; location updates only refresh the live card.
        if (signal.phase != SosPhase.UPDATE) {
            val msg = ChatMessage(
                id = rumor.id, peer = peer, fromMe = fromMe, text = rumor.content, createdAt = rumor.createdAt,
                type = MessageType.SOS, cardId = signal.phase.tag, extra = signal.location?.encode(),
                status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
            )
            if (upsertMessage(msg, countUnread = !fromMe) && !fromMe) queueDelivered(msg)
        }
        if (fromMe) return
        val now = Event.nowSeconds()
        // A signal dated in the future would outrank every real update that follows it.
        if (rumor.createdAt > now + SOS_MAX_CLOCK_SKEW) return
        // Only people you know can raise the alarm: someone you accepted, or a chat you have
        // written in. From anyone else an SOS is just a message request, with the usual quiet
        // notification — otherwise a stranger could set off alarms on your phone at will.
        val known = peer in settings.accepted || _conversations.value[peer]?.messages?.any { it.fromMe } == true
        if (!known) {
            if (signal.phase == SosPhase.START && rumor.createdAt >= now - 6 * 3600) {
                _conversations.value[peer]?.messages?.firstOrNull { it.id == rumor.id }
                    ?.let { _events.tryEmit(AmendEvent.MessageArrived(peer, it)) }
            }
            return
        }
        val old = _incomingSos.value[peer]
        // Ignore stale updates that arrive after a newer one.
        if (old != null && rumor.createdAt < old.updatedAt) return
        val sos = IncomingSos(
            peer = peer,
            active = signal.phase != SosPhase.STOP,
            startedAt = if (signal.phase == SosPhase.START || old == null || !old.active) rumor.createdAt else old.startedAt,
            updatedAt = rumor.createdAt,
            location = signal.location ?: old?.location,
        )
        // Don't raise alarms for SOS sessions that ended long ago (e.g. history after a restore).
        val fresh = rumor.createdAt >= now - 6 * 3600
        _incomingSos.update { it + (peer to sos.copy(active = sos.active && fresh)) }
        if (!fresh) return
        // No alarm storms: one person can sound a handful of alarms in a while, not hundreds.
        // Past that their SOS still shows, on the alert and in the app — it just arrives the
        // quiet way an update does, so nothing a friend sends is ever dropped.
        val phase = if (signal.phase == SosPhase.START && !sosAlarmAllowed(peer, now)) SosPhase.UPDATE else signal.phase
        _events.tryEmit(AmendEvent.SosReceived(peer, sos, phase))
    }

    /** One limit per person on the SOS alarms they can sound here. */
    private val sosAlarms = java.util.concurrent.ConcurrentHashMap<String, RateWindow>()

    /** At most [SOS_ALARMS_PER_WINDOW] alarms from one person per [SOS_ALARM_WINDOW] seconds. */
    private fun sosAlarmAllowed(peer: String, now: Long): Boolean =
        sosAlarms.getOrPut(peer) { RateWindow(SOS_ALARMS_PER_WINDOW, SOS_ALARM_WINDOW * 1000) }.allow(now * 1000)

    /** People who receive my SOS: everyone I've chatted with, minus [excluded] and blocked contacts. */
    fun sosRecipients(excluded: Set<String>): List<String> =
        _conversations.value.values
            .filter { it.peer != me && !it.isGroup && it.peer !in settings.blocked && it.peer !in excluded }
            .filter { isAccepted(it.peer) || it.messages.any { m -> m.fromMe } }
            .map { it.peer }

    /**
     * Sends one SOS signal to each recipient. Start/stop also appear in my own chats;
     * updates don't, to keep conversations readable. Returns how many people got it on ≥1 relay.
     */
    suspend fun sendSos(phase: SosPhase, location: SosLocation?, recipients: List<String>): Int {
        var delivered = 0
        val results = recipients.map { peer ->
            scope.async {
                val rumor = GiftWrap.rumor(me, Kinds.CHAT_MESSAGE, SosProtocol.content(phase, location), SosProtocol.tags(peer, phase, location))
                if (phase != SosPhase.UPDATE) {
                    upsertMessage(
                        ChatMessage(
                            rumor.id, peer, true, rumor.content, rumor.createdAt, MessageType.SOS, phase.tag,
                            status = MessageStatus.SENDING, extra = location?.encode(),
                        ),
                        countUnread = false,
                    )
                }
                val accepted = runCatching {
                    val wrap = GiftWrap.wrap(rumor, keys, peer)
                    mutex.withLock { seenWraps.add(wrap.id) }
                    // with the offline mesh on, the SOS also hops phone to phone — it can reach a
                    // friend even when there is no internet at all
                    meshOutbox?.invoke(wrap)
                    val n = pool.publish(wrap, inboxFor(peer)).count { it.accepted }
                    if (n == 0 && meshOutbox != null) 1 else n
                }.getOrDefault(0)
                if (phase != SosPhase.UPDATE) {
                    mutex.withLock {
                        _conversations.update { all ->
                            val conv = all[peer] ?: return@update all
                            all + (peer to conv.copy(messages = conv.messages.map {
                                if (it.id == rumor.id) it.copy(status = if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED, relaysAccepted = accepted) else it
                            }))
                        }
                        _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
                    }
                }
                accepted > 0
            }
        }
        results.forEach { if (it.await()) delivered++ }
        return delivered
    }

    /** A live-location start/update/stop in a chat. */
    private suspend fun handleLive(rumor: Event, peer: String, fromMe: Boolean, live: LiveSignal) {
        val key = "$peer|${rumor.pubkey}"
        if (live.phase != LivePhase.UPDATE) {
            val msg = ChatMessage(
                id = rumor.id, peer = peer, fromMe = fromMe, text = rumor.content, createdAt = rumor.createdAt,
                type = if (live.phase == LivePhase.START) MessageType.LIVE else MessageType.NOTICE,
                cardId = live.phase.tag, extra = live.location?.encode(), scheduledAt = live.until,
                status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED, receipt = 2,
                author = if (isGroup(peer)) rumor.pubkey else null,
            )
            upsertMessage(msg, countUnread = !fromMe && live.phase == LivePhase.START)
            if (!fromMe && live.phase == LivePhase.START) _events.tryEmit(AmendEvent.MessageArrived(peer, msg))
        }
        val old = _liveShares.value[key]
        if (old != null && rumor.createdAt < (old.location?.time ?: old.startedAt)) return // stale
        if (live.phase == LivePhase.STOP || live.until <= Event.nowSeconds()) {
            _liveShares.update { it - key }
            return
        }
        _liveShares.update {
            it + (key to LiveShare(peer, rumor.pubkey, live.location ?: old?.location, live.until,
                if (live.phase == LivePhase.START || old == null) rumor.createdAt else old.startedAt))
        }
    }

    /** Sends one live-location signal to a chat. Returns relays that accepted. */
    suspend fun sendLive(peer: String, phase: LivePhase, location: SosLocation?, until: Long): Int {
        val rumor = GiftWrap.rumor(me, Kinds.CHAT_MESSAGE, LiveProtocol.content(phase, until), LiveProtocol.tags(pTags(peer), phase, location, until))
        if (phase != LivePhase.UPDATE) {
            upsertMessage(
                ChatMessage(
                    rumor.id, peer, true, rumor.content, rumor.createdAt,
                    if (phase == LivePhase.START) MessageType.LIVE else MessageType.NOTICE, phase.tag,
                    status = MessageStatus.SENDING, extra = location?.encode(), scheduledAt = until, receipt = 2,
                    author = if (isGroup(peer)) me else null,
                ),
                countUnread = false,
            )
            accept(peer)
        }
        val key = "$peer|$me"
        if (phase == LivePhase.STOP) _liveShares.update { it - key }
        else _liveShares.update { it + (key to LiveShare(peer, me, location ?: it[key]?.location, until, it[key]?.startedAt ?: rumor.createdAt)) }
        val accepted = runCatching { publishRumor(peer, rumor) }.getOrDefault(0)
        if (phase != LivePhase.UPDATE) setMessageStatus(peer, rumor.id, if (accepted > 0 || meshOutbox != null) MessageStatus.SENT else MessageStatus.FAILED, accepted)
        return accepted
    }

    fun dismissIncomingSos(peer: String) {
        _incomingSos.update { all -> all[peer]?.let { all + (peer to it.copy(active = false)) } ?: all }
    }

    private fun handleMetadata(ev: Event) {
        if (!ev.isValid()) return
        val existing = _profiles.value[ev.pubkey]
        if (existing != null && existing.updatedAt >= ev.createdAt) return
        val profile = Profile.fromMetadata(ev.pubkey, ev.content, ev.createdAt) ?: return
        _profiles.update { it + (ev.pubkey to profile) }
        store.saveProfiles(_profiles.value)
    }

    private fun handleInboxRelays(ev: Event) {
        if (!ev.isValid() || ev.pubkey == me) return
        val urls = ev.tagValues("relay").map { RelayPool.normalize(it) }.filter { it.startsWith("wss://") }.take(6)
        if (urls.isEmpty()) return
        inboxRelays = inboxRelays + (ev.pubkey to urls)
        store.saveInboxRelays(inboxRelays)
    }

    /**
     * Where to deliver to [peer]. Until their inbox relay list (kind 10050) has arrived — as
     * with a brand-new contact — also use amend's default relays, so a first message or
     * request still reaches them even if our own relay list differs from theirs.
     */
    private fun inboxFor(peer: String): List<String> = inboxRelays[peer] ?: RelayPool.DEFAULT_RELAYS

    /** Returns true if the message is new. */
    private suspend fun upsertMessage(message: ChatMessage, countUnread: Boolean): Boolean {
        var added = false
        val isNewConversation = !_conversations.value.containsKey(message.peer)
        mutex.withLock {
            _conversations.update { all ->
                added = false
                val conv = all[message.peer] ?: Conversation(
                    peer = message.peer,
                    pinned = message.peer in settings.pinned,
                    nickname = settings.nicknames[message.peer] ?: "",
                    disappearSecs = settings.disappear[message.peer] ?: 0,
                )
                val idx = conv.messages.indexOfFirst { it.id == message.id }
                val newMessages = if (idx >= 0) {
                    // keep local reactions/status, update the rest
                    val old = conv.messages[idx]
                    conv.messages.toMutableList().also {
                        it[idx] = message.copy(
                            reactions = old.reactions + message.reactions,
                            status = old.status.advance(message.status),
                            receipt = maxOf(old.receipt, message.receipt),
                            relaysAccepted = maxOf(old.relaysAccepted, message.relaysAccepted),
                        )
                    }
                } else {
                    added = true
                    (conv.messages + message).sortedBy { it.createdAt }
                }
                all + (message.peer to conv.copy(
                    messages = newMessages,
                    unread = if (added && countUnread) conv.unread + 1 else conv.unread,
                ))
            }
            store.saveChat(message.peer, _conversations.value[message.peer]!!.messages)
        }
        if (added && message.peer !in settings.accepted && message.fromMe) {
            accept(message.peer)
        } else if (isNewConversation) {
            // fetch the new person's public profile (name, photo) so requests aren't anonymous
            resubscribeMetadata()
        }
        return added
    }

    private suspend fun applyReaction(peer: String, messageId: String, reactor: String, emoji: String) {
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map {
                    if (it.id != messageId) it
                    else it.copy(reactions = if (emoji.isBlank() || emoji == "-") it.reactions - reactor else it.reactions + (reactor to emoji))
                }))
            }
            _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
    }

    // ---------------------------------------------------------------- outgoing

    /** Who a rumor in this chat is wrapped for (besides ourselves). */
    private fun recipientsOf(peer: String): List<String> =
        if (isGroup(peer)) groupInfo(peer)?.members.orEmpty().filter { it != me } else listOf(peer)

    /** The "p" tags for a chat: the friend, or every other member of a shared garden. */
    fun pTags(peer: String): List<List<String>> = recipientsOf(peer).map { listOf("p", it) }

    /**
     * Wraps a rumor separately for every recipient and for ourselves, and publishes them.
     * Returns how many relays accepted (the best recipient, for a shared garden).
     */
    private suspend fun publishRumor(peer: String, rumor: Event, expiration: Long? = null): Int {
        val recipients = recipientsOf(peer).ifEmpty { return 0 }
        val wraps = recipients.map { it to GiftWrap.wrap(rumor, keys, it, expiration = expiration) }
        val toSelf = if (peer != me) GiftWrap.wrap(rumor, keys, me, expiration = expiration) else null
        mutex.withLock {
            wraps.forEach { seenWraps.add(it.second.id) }
            toSelf?.let { seenWraps.add(it.id) }
        }
        wraps.forEach { meshOutbox?.invoke(it.second) }
        val counts = wraps.map { (who, w) -> scope.async { runCatching { pool.publish(w, inboxFor(who)).count { it.accepted } }.getOrDefault(0) } }.awaitAll()
        toSelf?.let { scope.launch { pool.publish(it) } }
        return counts.maxOrNull() ?: 0
    }

    // ---------------------------------------------------------------- offline mesh

    /** Set while the offline mesh is on: every gift wrap we send is also handed to nearby phones. */
    @Volatile var meshOutbox: ((Event) -> Unit)? = null

    /** A gift wrap that reached us over the mesh (Bluetooth / Wi-Fi Direct) instead of a relay. */
    suspend fun injectMeshEvent(ev: Event) {
        if (ev.kind == Kinds.GIFT_WRAP && ev.tagValues("p").contains(me)) handleWrap(ev)
    }

    /** Uploads an already-signed event (one carried over the mesh for someone else). */
    suspend fun publishRaw(ev: Event): Int = runCatching { pool.publish(ev).count { it.accepted } }.getOrDefault(0)

    suspend fun sendText(peer: String, text: String, replyTo: String? = null): ChatMessage {
        val tags = pTags(peer).toMutableList()
        replyTo?.let { tags.add(listOf("e", it)) }
        return sendRumor(peer, text.trim(), tags, MessageType.TEXT, null, replyTo)
    }

    suspend fun shareChallenge(peer: String, challengeId: String): ChatMessage {
        val c = Challenges.byId(challengeId) ?: throw IllegalArgumentException("unknown challenge")
        val text = "${c.category.emoji} Eco challenge: ${c.title} — ${c.description}"
        val msg = sendRumor(peer, text, pTags(peer) + listOf(listOf("amend", "card", c.id)), MessageType.ECO_CARD, c.id, null)
        award(EcoPoints.SHARE_CARD, "Shared an eco challenge", isMessage = false)
        return msg
    }

    private suspend fun sendRumor(
        peer: String, content: String, tags: List<List<String>>, type: MessageType, cardId: String?, replyTo: String?,
        noExpiry: Boolean = false,
    ): ChatMessage {
        val timer = _conversations.value[peer]?.disappearSecs ?: settings.disappear[peer] ?: 0L
        val expiresAt = if (timer > 0 && !noExpiry) Event.nowSeconds() + timer else 0L
        val allTags = if (expiresAt > 0) tags + listOf(listOf("expiration", expiresAt.toString())) else tags
        val rumor = GiftWrap.rumor(me, Kinds.CHAT_MESSAGE, content, allTags)
        val local = ChatMessage(
            rumor.id, peer, true, content, rumor.createdAt, type, cardId, replyTo, status = MessageStatus.SENDING,
            receipt = if (type == MessageType.NOTICE) 2 else 0,
            author = if (isGroup(peer)) me else null, expiresAt = expiresAt,
        )
        upsertMessage(local, countUnread = false)
        accept(peer)
        if (type != MessageType.NOTICE) recordMessageStats(received = false)
        val accepted = try {
            publishRumor(peer, rumor, expiresAt.takeIf { it > 0 })
        } catch (e: Exception) {
            0
        }
        // offline with the mesh on: it's travelling phone to phone, not failed
        val final = local.copy(status = if (accepted > 0 || meshOutbox != null) MessageStatus.SENT else MessageStatus.FAILED, relaysAccepted = accepted)
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map { if (it.id == final.id) final.copy(status = it.status.advance(final.status)) else it }))
            }
            store.saveChat(peer, _conversations.value[peer]!!.messages)
        }
        _events.tryEmit(AmendEvent.MessageSent(peer, accepted))
        return final
    }

    // ---------------------------------------------------------------- eco map (public)

    private val _ecoEvents = MutableSharedFlow<Event>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Public eco reports and reactions from "eco-…" subscriptions. */
    val ecoEvents: SharedFlow<Event> = _ecoEvents

    fun subscribeEco(id: String, filters: List<Map<String, Any?>>) = pool.subscribe(SUB_ECO_PREFIX + id, filters)

    /** Signs and publishes a public event with our key. Returns it and how many relays accepted it. */
    suspend fun publishPublic(kind: Int, content: String, tags: List<List<String>>): Pair<Event, Int> {
        val ev = Event.sign(keys.secretKey, kind, content, tags)
        val n = runCatching { pool.publish(ev).count { it.accepted } }.getOrDefault(0)
        _ecoEvents.tryEmit(ev) // show it right away
        return ev to n
    }

    /** Uploads a public (unencrypted) photo. Returns its URLs (main first). */
    suspend fun uploadPublicImage(jpeg: ByteArray): List<String> =
        blossom.uploadAny(mediaServers, jpeg, keys, copies = 2, mime = "image/jpeg").map { it.url }

    /** Points for real-world eco actions (reports, checks, clean-ups, planted trees). */
    fun awardEco(points: Int, reason: String) = award(points, reason, isMessage = false)

    /** Asks a friend to check a tree we planted: a chat card with the photo and a "Verify" button. */
    suspend fun sendTreeProof(peer: String, report: EcoReport): ChatMessage {
        val tags = mutableListOf(listOf("p", peer), listOf("amend", "proof", report.address), listOf("amend-event", report.eventId))
        report.imageUrl?.let { tags.add(listOf("image", it)) }
        tags.add(listOf("location", String.format(java.util.Locale.US, "%.5f,%.5f", report.lat, report.lon)))
        val text = "🌳 I planted a real tree! " + (if (report.note.isNotBlank()) "“${report.note}” " else "") + "Can you check it and verify?"
        tags.removeAll { it.firstOrNull() == "p" }
        tags.addAll(0, pTags(peer))
        return sendRumor(peer, text, tags, MessageType.ECO_PROOF, null, null)
    }

    // ---------------------------------------------------------------- calls

    private val _callSignals = MutableSharedFlow<CallSignal>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Call offers, answers, hang-ups… from friends (see CallManager). */
    val callSignals: SharedFlow<CallSignal> = _callSignals

    /**
     * Sends one call signal. Live signals go as ephemeral gift wraps (forwarded, never stored);
     * [stored] ones (a missed-call note) as normal gift wraps so the friend sees them later.
     */
    suspend fun sendCallSignal(peer: String, signal: CallSignal, stored: Boolean = false): Int {
        val rumor = GiftWrap.rumor(me, Kinds.CALL_SIGNAL, signal.contentJson(), listOf(listOf("p", peer), listOf("amend", "call", signal.type)))
        val wrap = GiftWrap.wrap(rumor, keys, peer, ephemeral = !stored)
        mutex.withLock { seenWraps.add(wrap.id) }
        return pool.publish(wrap, inboxFor(peer)).count { it.accepted }
    }

    /** Adds (or updates) the call entry in a chat. Local only: each side keeps its own call history. */
    suspend fun logCall(peer: String, log: CallLog) {
        val message = ChatMessage(
            id = "call-" + log.callId, peer = peer, fromMe = log.outgoing, text = log.label(),
            createdAt = log.startedAt, type = MessageType.CALL, status = if (log.outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
            extra = log.toJson(), receipt = 2,
        )
        val existing = _conversations.value[peer]?.messages?.any { it.id == message.id } == true
        if (existing) {
            mutex.withLock {
                _conversations.update { all ->
                    val conv = all[peer] ?: return@update all
                    all + (peer to conv.copy(messages = conv.messages.map { if (it.id == message.id) message else it }))
                }
                _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
            }
        } else {
            val missed = !log.outgoing && log.outcome == CallOutcome.MISSED
            if (upsertMessage(message, countUnread = missed) && missed) _events.tryEmit(AmendEvent.MessageArrived(peer, message))
        }
    }

    // ---------------------------------------------------------------- receipts

    private val pendingDelivered = mutableMapOf<String, MutableSet<String>>() // peer -> message ids
    private var deliveredJob: kotlinx.coroutines.Job? = null

    /** Receipts go only to accepted contacts (a stranger doesn't learn you're online) and only for recent messages. */
    private fun wantsReceipt(m: ChatMessage) =
        !m.fromMe && !isGroup(m.peer) && m.peer in settings.accepted && m.createdAt > Event.nowSeconds() - RECEIPT_MAX_AGE

    private fun queueDelivered(m: ChatMessage) {
        if (!wantsReceipt(m) || m.receipt >= 1) return
        synchronized(pendingDelivered) { pendingDelivered.getOrPut(m.peer) { mutableSetOf() }.add(m.id) }
        if (deliveredJob?.isActive != true) {
            // batch: one receipt for a burst of messages
            deliveredJob = scope.launch { delay(1_500); runCatching { flushDelivered() } }
        }
    }

    private suspend fun flushDelivered() {
        val batch = synchronized(pendingDelivered) { pendingDelivered.toMap().also { pendingDelivered.clear() } }
        for ((peer, ids) in batch) {
            if (ids.isEmpty()) continue
            if (sendReceipt(peer, "delivered", ids.toList(), null) > 0) markReceipt(peer, ids, 1)
        }
    }

    /** Call while the chat is on screen: tells the friend their messages were seen. */
    fun markSeen(peer: String) {
        if (peer !in settings.accepted) return // requests: nothing is sent or marked until accepted
        val conv = _conversations.value[peer] ?: return
        val unseen = conv.messages.filter { !it.fromMe && it.receipt < 2 && it.type != MessageType.CALL }
        if (unseen.isEmpty()) return
        val toSend = unseen.filter { wantsReceipt(it) }
        scope.launch {
            if (_eco.value.readReceipts && toSend.isNotEmpty()) {
                val sent = runCatching {
                    sendReceipt(peer, "seen", toSend.takeLast(100).map { it.id }, toSend.maxOf { it.createdAt })
                }.getOrDefault(0)
                if (sent == 0) return@launch // try again next time the chat is opened
            }
            // older ones (or with read receipts off) are just marked locally
            markReceipt(peer, unseen.map { it.id }.toSet(), 2)
        }
    }

    private suspend fun sendReceipt(peer: String, type: String, ids: List<String>, until: Long?): Int {
        val tags = mutableListOf(listOf("p", peer))
        ids.take(100).forEach { tags.add(listOf("e", it)) }
        tags.add(listOf("amend", "receipt", type))
        until?.let { tags.add(listOf("amend-until", it.toString())) }
        val rumor = GiftWrap.rumor(me, Kinds.RECEIPT, "", tags)
        val wrap = GiftWrap.wrap(rumor, keys, peer)
        mutex.withLock { seenWraps.add(wrap.id) }
        meshOutbox?.invoke(wrap)
        val n = pool.publish(wrap, inboxFor(peer)).count { it.accepted }
        return if (n == 0 && meshOutbox != null) 1 else n
    }

    private suspend fun markReceipt(peer: String, ids: Set<String>, level: Int) {
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map { if (it.id in ids && it.receipt < level) it.copy(receipt = level) else it }))
            }
            _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
    }

    /** A friend's phone says it got (or showed) our messages: move their status forward. */
    private suspend fun applyReceipt(peer: String, rumor: Event) {
        val type = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "receipt" }?.get(2) ?: return
        val status = when (type) { "delivered" -> MessageStatus.DELIVERED; "seen" -> MessageStatus.SEEN; else -> return }
        val ids = rumor.tagValues("e").toSet()
        val until = rumor.firstTag("amend-until")?.toLongOrNull()
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map {
                    val hit = it.fromMe &&
                        (it.id in ids || (status == MessageStatus.SEEN && until != null && it.createdAt <= until && it.status.rank >= 1))
                    if (hit) it.copy(status = it.status.advance(status)) else it
                }))
            }
            _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
    }

    fun setReadReceipts(on: Boolean) {
        _eco.update { it.copy(readReceipts = on) }
        store.saveEco(_eco.value)
    }

    // ---------------------------------------------------------------- media

    private val blossom = app.amend.messenger.net.Blossom()
    private val mediaDir = File(dir, "media").apply { mkdirs() }

    /** Blossom servers to upload to, best first. */
    var mediaServers: List<String> = app.amend.messenger.net.Blossom.DEFAULT_SERVERS

    /** Where the plain (decrypted) copy of a message's media lives on this phone. */
    fun localMediaFile(media: MediaInfo): File {
        val ext = when {
            media.mime.contains("mp4") && media.kind == MediaKind.VOICE -> "m4a"
            media.mime.contains("mp4") -> "mp4"
            media.mime.contains("ogg") -> "ogg"
            media.mime.contains("webm") -> "webm"
            else -> "bin"
        }
        return File(mediaDir, "${media.sha256.ifBlank { media.keyHex.take(16) }}.$ext")
    }

    /**
     * Encrypts [plain], uploads it (two servers when possible) and sends a NIP-17
     * file message. The message appears immediately with an upload status.
     */
    suspend fun sendMedia(
        peer: String, plain: ByteArray, kind: MediaKind, mime: String,
        durationMs: Long = 0, waveform: ByteArray = ByteArray(0), width: Int = 0, height: Int = 0,
        replyTo: String? = null,
        caption: String = "",
    ): ChatMessage {
        val cap = caption.trim().take(MAX_CAPTION)
        val enc = app.amend.messenger.core.crypto.FileCrypto.encrypt(plain)
        val draft = MediaInfo(
            kind, emptyList(), mime, enc.key.toHex(), enc.nonce.toHex(), enc.sha256Hex, enc.data.size.toLong(),
            durationMs, waveform, width, height,
        )
        // keep our own plain copy so it plays instantly without downloading
        localMediaFile(draft).writeBytes(plain)
        val type = kind.messageType
        val label = cap.ifEmpty { kind.label }
        val tempId = "local-" + enc.sha256Hex
        upsertMessage(ChatMessage(tempId, peer, true, label, Event.nowSeconds(), type, replyTo = replyTo, status = MessageStatus.SENDING, extra = draft.toJson()), countUnread = false)
        accept(peer)

        val blobs = try {
            blossom.uploadAny(mediaServers, enc.data, keys, copies = 2, mime = mime)
        } catch (e: Exception) {
            setMessageStatus(peer, tempId, MessageStatus.FAILED, 0)
            throw e
        }
        pendingBytesSent.addAndGet(enc.data.size.toLong() * blobs.size)
        val media = draft.copy(urls = blobs.map { it.url })
        val tags = (pTags(peer) + media.tags(peer).filter { it.firstOrNull() != "p" }).toMutableList()
        replyTo?.let { tags.add(listOf("e", it)) }
        if (cap.isNotEmpty()) tags.add(listOf("amend-caption", cap))
        val rumor = GiftWrap.rumor(me, Kinds.FILE_MESSAGE, media.urls.first(), tags)
        // swap the temporary bubble for the real message id
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map {
                    if (it.id == tempId) it.copy(id = rumor.id, createdAt = rumor.createdAt, extra = media.toJson()) else it
                }))
            }
        }
        recordMessageStats(received = false)
        val accepted = runCatching { publishRumor(peer, rumor) }.getOrDefault(0)
        setMessageStatus(peer, rumor.id, if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED, accepted)
        _events.tryEmit(AmendEvent.MessageSent(peer, accepted))
        return _conversations.value[peer]?.messages?.firstOrNull { it.id == rumor.id }
            ?: ChatMessage(rumor.id, peer, true, label, rumor.createdAt, type, extra = media.toJson())
    }

    private suspend fun setMessageStatus(peer: String, id: String, status: MessageStatus, relays: Int) {
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map { if (it.id == id) it.copy(status = it.status.advance(status), relaysAccepted = maxOf(it.relaysAccepted, relays)) else it }))
            }
            _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
    }

    /** Upload never finished → upload again from our plain copy; otherwise just resend the file message. */
    private suspend fun retryMedia(peer: String, msg: ChatMessage) {
        val media = MediaInfo.fromJson(msg.extra) ?: return
        if (media.urls.isEmpty()) {
            val local = localMediaFile(media)
            if (!local.exists()) { setMessageStatus(peer, msg.id, MessageStatus.FAILED, 0); return }
            val plain = local.readBytes()
            // drop the failed bubble; sendMedia makes a fresh one
            mutex.withLock {
                _conversations.update { all ->
                    val conv = all[peer] ?: return@update all
                    all + (peer to conv.copy(messages = conv.messages.filter { it.id != msg.id }))
                }
            }
            runCatching {
                sendMedia(peer, plain, media.kind, media.mime, media.durationMs, media.waveform, media.width, media.height, msg.replyTo,
                    caption = msg.text.takeIf { it != media.kind.label }.orEmpty())
            }
            return
        }
        setMessageStatus(peer, msg.id, MessageStatus.SENDING, 0)
        val tags = (pTags(peer) + media.tags(peer).filter { it.firstOrNull() != "p" }).toMutableList()
        msg.replyTo?.let { tags.add(listOf("e", it)) }
        msg.text.takeIf { it != media.kind.label && it.isNotBlank() }?.let { tags.add(listOf("amend-caption", it)) }
        val rumor = GiftWrap.rumor(me, Kinds.FILE_MESSAGE, media.urls.first(), tags, msg.createdAt)
        val accepted = runCatching { publishRumor(peer, rumor) }.getOrDefault(0)
        setMessageStatus(peer, msg.id, if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED, accepted)
    }

    /** Returns a playable local file for a media message, downloading and decrypting it if needed. */
    suspend fun fetchMedia(media: MediaInfo): File {
        val file = localMediaFile(media)
        if (file.exists() && file.length() > 0) return file
        val encrypted = blossom.download(media.urls, media.sha256.ifBlank { null })
        pendingBytesReceived.addAndGet(encrypted.size.toLong())
        // decrypting a video takes a moment: keep it off the UI thread
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val plain = app.amend.messenger.core.crypto.FileCrypto.decrypt(encrypted, media.keyHex.hexToBytes(), media.nonceHex.hexToBytes())
            val tmp = File(file.path + ".part")
            tmp.writeBytes(plain)
            tmp.renameTo(file)
            file
        }
    }

    suspend fun retry(peer: String, messageId: String) {
        val msg = _conversations.value[peer]?.messages?.firstOrNull { it.id == messageId } ?: return
        if (msg.type == MessageType.CALL || msg.type == MessageType.UNSENT || msg.status == MessageStatus.SCHEDULED) return
        if (msg.type == MessageType.VOICE || msg.type == MessageType.VIDEO || msg.type == MessageType.FILE) {
            retryMedia(peer, msg)
            return
        }
        val tags = pTags(peer).toMutableList()
        msg.replyTo?.let { tags.add(listOf("e", it)) }
        msg.cardId?.let { tags.add(if (it.startsWith("ach:")) listOf("amend", "achievement", it.removePrefix("ach:")) else listOf("amend", "card", it)) }
        val rumor = Event.unsigned(me, Kinds.CHAT_MESSAGE, msg.text, tags, msg.createdAt)
        val accepted = runCatching { publishRumor(peer, rumor) }.getOrDefault(0)
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map {
                    if (it.id == messageId) it.copy(status = it.status.advance(if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED), relaysAccepted = accepted) else it
                }))
            }
            store.saveChat(peer, _conversations.value[peer]!!.messages)
        }
    }

    suspend fun react(peer: String, messageId: String, emoji: String) {
        applyReaction(peer, messageId, me, emoji)
        val rumor = GiftWrap.rumor(me, Kinds.REACTION, emoji, listOf(listOf("e", messageId)) + pTags(peer) + listOf(listOf("k", "14")))
        runCatching { publishRumor(peer, rumor) }
    }

    // ---------------------------------------------------------------- unsend

    /** A sent message can be taken back for this long. */
    fun canUnsend(m: ChatMessage): Boolean =
        m.fromMe && m.type != MessageType.UNSENT && m.type != MessageType.CALL && m.type != MessageType.NOTICE &&
            m.status != MessageStatus.SCHEDULED &&
            (Event.nowSeconds() - m.createdAt <= UNSEND_WINDOW || (isInstant(m) && m.cardId != INSTANT_OPENED_MARK && Event.nowSeconds() - m.createdAt <= INSTANT_TTL))

    /**
     * Takes back one of my messages (within [UNSEND_WINDOW]): it becomes "message unsent" here
     * at once, and a gift-wrapped NIP-09 deletion tells the other phones to do the same.
     */
    suspend fun unsend(peer: String, messageId: String): Boolean {
        val m = _conversations.value[peer]?.messages?.firstOrNull { it.id == messageId } ?: return false
        if (!canUnsend(m)) return false
        applyUnsend(peer, messageId, me, Event.nowSeconds())
        persistSettings()
        val rumor = GiftWrap.rumor(me, Kinds.DELETION, "", pTags(peer) + listOf(listOf("e", messageId), listOf("k", "14")))
        return runCatching { publishRumor(peer, rumor) }.getOrDefault(0) > 0 || meshOutbox != null
    }

    // ---------------------------------------------------------------- edit

    /** My own text messages can be corrected inside the same window as unsend. */
    fun canEdit(m: ChatMessage): Boolean =
        m.fromMe && m.type == MessageType.TEXT && m.status != MessageStatus.SCHEDULED &&
            Event.nowSeconds() - m.createdAt <= UNSEND_WINDOW

    /** Replaces the text of one of my messages here at once and on the other phones. */
    suspend fun editMessage(peer: String, messageId: String, newText: String): Boolean {
        val text = newText.trim().take(MAX_EDIT_CHARS)
        val m = _conversations.value[peer]?.messages?.firstOrNull { it.id == messageId } ?: return false
        if (!canEdit(m) || text.isEmpty() || text == m.text) return false
        val now = Event.nowSeconds()
        applyEdit(peer, messageId, me, now, text)
        val rumor = GiftWrap.rumor(me, Kinds.EDIT, text, pTags(peer) + listOf(listOf("e", messageId), listOf("amend", "edit")), now)
        val expiration = m.expiresAt.takeIf { it > 0 }
        return runCatching { publishRumor(peer, rumor, expiration) }.getOrDefault(0) > 0 || meshOutbox != null
    }

    /** Applies an edit from [by] — only the message's own author, only inside the window, newest wins. */
    private suspend fun applyEdit(peer: String, id: String, by: String, at: Long, text: String) {
        var found = false
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map { m ->
                    if (m.id != id) return@map m
                    found = true
                    val author = m.author ?: if (m.fromMe) me else m.peer
                    if (author == by && m.type == MessageType.TEXT && at - m.createdAt <= UNSEND_WINDOW + 300 && at >= m.editedAt) {
                        m.copy(text = text, editedAt = at)
                    } else m
                }))
            }
            if (found) _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
        if (!found) pendingEdits[id] = Triple(by, at, text)
    }

    private fun tombstone(m: ChatMessage) =
        m.copy(type = MessageType.UNSENT, text = "", extra = null, cardId = null, reactions = emptyMap(), replyTo = null)

    /** Applies an unsend from [by] — only the message's own author, and only inside the window. */
    private suspend fun applyUnsend(peer: String, id: String, by: String, at: Long) {
        settings = settings.copy(unsent = settings.unsent + (id to by))
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map { m ->
                    val author = m.author ?: if (m.fromMe) me else m.peer
                    if (m.id == id && author == by && at - m.createdAt <= UNSEND_WINDOW + 300 && m.type != MessageType.UNSENT) {
                        if (m.type == MessageType.VOICE || m.type == MessageType.VIDEO || m.type == MessageType.FILE) {
                            MediaInfo.fromJson(m.extra)?.let { runCatching { localMediaFile(it).delete() } }
                        }
                        tombstone(m)
                    } else m
                }))
            }
            _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        }
    }

    // ---------------------------------------------------------------- disappearing messages

    /** Turns disappearing messages on/off for a chat and tells the other side(s). */
    suspend fun setDisappearing(peer: String, secs: Long) {
        setDisappearLocal(peer, secs, Event.nowSeconds())
        val label = if (secs <= 0) "off" else Disappear.label(secs)
        sendRumor(
            peer, "⏳ ${myName()} turned disappearing messages ${if (secs <= 0) "off" else "on · $label"}",
            pTags(peer) + listOf(listOf("amend", "disappear", secs.coerceAtLeast(0).toString())),
            MessageType.NOTICE, null, null, noExpiry = true,
        )
    }

    private fun setDisappearLocal(peer: String, secs: Long, at: Long) {
        // history can arrive out of order: only a newer change wins
        if ((settings.disappearAt[peer] ?: 0L) > at) return
        settings = settings.copy(
            disappear = if (secs > 0) settings.disappear + (peer to secs) else settings.disappear - peer,
            disappearAt = settings.disappearAt + (peer to at),
        )
        _conversations.update { all -> all[peer]?.let { all + (peer to it.copy(disappearSecs = secs.coerceAtLeast(0))) } ?: all }
        persistSettingsSoon()
    }

    /** Deletes messages whose disappearing time has come (and their media). */
    private suspend fun purgeExpired() {
        val now = Event.nowSeconds()
        val hit = _conversations.value.values.filter { c -> c.messages.any { it.expiresAt in 1..now } }
        if (hit.isEmpty()) return
        mutex.withLock {
            hit.forEach { c ->
                c.messages.filter { it.expiresAt in 1..now }.forEach { m ->
                    if (m.type == MessageType.VOICE || m.type == MessageType.VIDEO || m.type == MessageType.FILE) {
                        MediaInfo.fromJson(m.extra)?.let { runCatching { localMediaFile(it).delete() } }
                    }
                }
            }
            _conversations.update { all ->
                all.mapValues { (_, c) -> if (c.messages.any { it.expiresAt in 1..now }) c.copy(messages = c.messages.filter { it.expiresAt !in 1..now }) else c }
            }
            hit.forEach { c -> _conversations.value[c.peer]?.let { store.saveChat(c.peer, it.messages) } }
        }
    }

    // ---------------------------------------------------------------- scheduled messages

    /** Keeps a message on this phone and sends it at [at] (unix seconds). */
    suspend fun scheduleText(peer: String, text: String, at: Long, replyTo: String? = null): ChatMessage {
        val id = "sched-" + Hashes.sha256("$peer:$at:${System.nanoTime()}:$text".toByteArray()).toHex().take(24)
        val m = ChatMessage(
            id, peer, true, text.trim(), at, MessageType.TEXT, replyTo = replyTo, status = MessageStatus.SCHEDULED,
            scheduledAt = at, receipt = 2, author = if (isGroup(peer)) me else null,
        )
        upsertMessage(m, countUnread = false)
        accept(peer)
        return m
    }

    suspend fun cancelScheduled(peer: String, id: String) {
        claimScheduled(peer, id)
    }

    /**
     * Takes a scheduled message off the queue. Only one caller gets true for a message, so the
     * in-app timer and the background worker can never both send it.
     */
    private suspend fun claimScheduled(peer: String, id: String): Boolean = mutex.withLock {
        var claimed = false
        _conversations.update { all ->
            val conv = all[peer] ?: return@update all
            claimed = conv.messages.any { it.id == id && it.status == MessageStatus.SCHEDULED }
            if (!claimed) all
            else all + (peer to conv.copy(messages = conv.messages.filter { !(it.id == id && it.status == MessageStatus.SCHEDULED) }))
        }
        if (claimed) _conversations.value[peer]?.let { store.saveChat(peer, it.messages) }
        claimed
    }

    /** Sends every scheduled message whose time has come (or [force] one now). Returns how many. */
    suspend fun sendDueScheduled(forceId: String? = null): Int {
        if (!isOnline() && meshOutbox == null) return 0
        val now = Event.nowSeconds()
        val due = _conversations.value.values.flatMap { c ->
            c.messages.filter { it.status == MessageStatus.SCHEDULED && (it.scheduledAt <= now || it.id == forceId) }
        }
        var sent = 0
        due.forEach { m ->
            if (!claimScheduled(m.peer, m.id)) return@forEach // someone else is already sending it
            runCatching { sendText(m.peer, m.text, m.replyTo) }
            sent++
        }
        return sent
    }

    /** The earliest pending scheduled send, if any (unix seconds). */
    fun nextScheduledAt(): Long? = _conversations.value.values
        .flatMap { c -> c.messages.filter { it.status == MessageStatus.SCHEDULED }.map { it.scheduledAt } }
        .minOrNull()

    // ---------------------------------------------------------------- shared gardens (groups)

    private fun myName() = myProfile.name.ifBlank { "Someone" }

    private fun registerGroup(key: String, participants: List<String>, rumor: Event) {
        val subject = rumor.firstTag("subject")?.trim()?.take(60)
        val old = _groups.value[key]
        if (old != null && (subject == null || subject == old.name)) return
        val g = (old ?: GroupInfo(key, participants.sorted(), createdBy = rumor.pubkey)).let { if (subject != null) it.copy(name = subject) else it }
        _groups.update { it + (key to g) }
        settings = settings.copy(groups = _groups.value)
        // a garden planted by someone you already talk to opens straight away; from a stranger it's a request
        if (old == null && (rumor.pubkey == me || rumor.pubkey in settings.accepted)) accept(key) else persistSettingsSoon()
        if (old == null) resubscribeMetadata()
    }

    /** Plants a new shared garden with [members] (public keys) and announces it. Returns its key. */
    suspend fun createGroup(name: String, members: List<String>): String {
        val all = (members + me).filter { it.length == 64 && it !in settings.blocked }.distinct()
        require(all.size >= 3) { "A shared garden needs at least two other people" }
        val key = GroupInfo.keyFor(all)
        val g = GroupInfo(key, all.sorted(), name.trim().take(60), me)
        _groups.update { it + (key to g) }
        settings = settings.copy(groups = _groups.value)
        accept(key)
        _conversations.update { a -> if (a.containsKey(key)) a else a + (key to Conversation(key)) }
        persistSettings()
        resubscribeMetadata()
        val tags = pTags(key) + listOf(listOf("amend", "group", "create")) +
            (if (g.name.isNotBlank()) listOf(listOf("subject", g.name)) else emptyList())
        sendRumor(key, "🌱 ${myName()} planted this shared garden" + (if (g.name.isNotBlank()) " · “${g.name}”" else ""), tags, MessageType.NOTICE, null, null, noExpiry = true)
        return key
    }

    suspend fun renameGroup(key: String, name: String) {
        val g = groupInfo(key) ?: return
        val clean = name.trim().take(60)
        _groups.update { it + (key to g.copy(name = clean)) }
        settings = settings.copy(groups = _groups.value)
        persistSettings()
        sendRumor(
            key, "✏️ ${myName()} named the garden “$clean”",
            pTags(key) + listOf(listOf("amend", "group", "rename"), listOf("subject", clean)), MessageType.NOTICE, null, null, noExpiry = true,
        )
    }

    // ---------------------------------------------------------------- achievements

    /** Numbers only the app knows (eco map posts, km walked this month), filled in by AmendApp. */
    @Volatile var externalStats: () -> Pair<Long, Long> = { 0L to 0L }

    private val _celebrate = MutableStateFlow<List<String>>(emptyList())
    /** Newly unlocked achievements waiting to be celebrated on screen. */
    val celebrate: StateFlow<List<String>> = _celebrate.asStateFlow()

    fun celebrated(key: String) = _celebrate.update { it - key }

    fun achievementStats(): app.amend.messenger.eco.AchievementStats {
        val e = _eco.value
        val caused = app.amend.messenger.eco.Footprint.gramsCo2(e.totalBytes)
        val (map, km) = runCatching { externalStats() }.getOrDefault(0L to 0L)
        return app.amend.messenger.eco.AchievementStats(
            trees = e.trees.size.toLong(), points = e.totalPoints.toLong(), streak = e.streak.toLong(),
            challenges = e.completedChallenges.size.toLong(), co2AvoidedGrams = e.co2AvoidedGrams,
            offsetRatio = if (caused > 0) (e.co2AvoidedGrams / caused).toLong() else 0L,
            gardens = _groups.value.size.toLong(), forestFriends = _friendStats.value.size.toLong(),
            mapPosts = map, walkedKm = km, messages = (e.messagesSent + e.messagesReceived).toLong(),
        )
    }

    /** Records newly reached tiers; celebrates the highest new tier of each achievement. */
    fun checkAchievements() {
        val earned = app.amend.messenger.eco.Achievements.earned(achievementStats())
        val e = _eco.value
        val fresh = earned - e.achievements.keys
        if (fresh.isEmpty() && e.achievementsInit) return
        val now = Event.nowSeconds()
        _eco.update { it.copy(achievements = it.achievements + fresh.associateWith { now }, achievementsInit = true) }
        store.saveEco(_eco.value)
        if (!e.achievementsInit) return // first run: count what was already done, quietly
        val top = fresh.mapNotNull { app.amend.messenger.eco.Achievements.parse(it) }
            .groupBy { it.first.id }.values.map { l -> l.maxBy { it.second.ordinal } }
            .map { (a, t) -> app.amend.messenger.eco.Achievements.key(a, t) }
        if (top.isEmpty()) return
        _celebrate.update { it + top }
        top.forEach { _events.tryEmit(AmendEvent.AchievementUnlocked(it)) }
    }

    /** Sends an achievement card into a chat. */
    suspend fun shareAchievement(peer: String, key: String): ChatMessage? {
        val (a, t) = app.amend.messenger.eco.Achievements.parse(key) ?: return null
        if (key !in _eco.value.achievements) return null
        return sendRumor(
            peer, "🏅 I unlocked ${a.title} · ${t.label} on amend — ${a.describe(t)}",
            pTags(peer) + listOf(listOf("amend", "achievement", key)), MessageType.ECO_CARD, "ach:$key", null,
        )
    }

    // ---------------------------------------------------------------- stories

    private val _stories = MutableStateFlow(store.loadStories().filter { it.expiresAt > Event.nowSeconds() })
    /** Live stories (mine and my contacts'), oldest first. */
    val stories: StateFlow<List<Story>> = _stories.asStateFlow()

    private fun saveStories() = store.saveStories(_stories.value)

    private fun purgeStories() {
        val now = Event.nowSeconds()
        val gone = _stories.value.filter { it.expiresAt <= now }
        if (gone.isEmpty()) return
        gone.forEach { s -> s.media?.let { MediaInfo.fromJson(it) }?.let { runCatching { localMediaFile(it).delete() } } }
        _stories.update { list -> list.filter { it.expiresAt > now } }
        saveStories()
    }

    /**
     * Posts a story for 24 hours to all your contacts, or only your close friends. A photo or
     * video is encrypted and uploaded once; the story itself is gift-wrapped to each person.
     */
    suspend fun postStory(
        media: ByteArray?, kind: MediaKind = MediaKind.PHOTO, mime: String = "image/jpeg",
        width: Int = 0, height: Int = 0, durationMs: Long = 0,
        text: String = "", bg: Int = 0, closeFriendsOnly: Boolean = false,
    ): Int {
        val audience = (if (closeFriendsOnly) contactKeys().filter { it in settings.closeFriends } else contactKeys()).take(300)
        val now = Event.nowSeconds()
        val expires = now + STORY_TTL
        var mediaJson: String? = null
        if (media != null) {
            val enc = app.amend.messenger.core.crypto.FileCrypto.encrypt(media)
            val draft = MediaInfo(kind, emptyList(), mime, enc.key.toHex(), enc.nonce.toHex(), enc.sha256Hex, enc.data.size.toLong(),
                durationMs = durationMs, width = width, height = height)
            localMediaFile(draft).writeBytes(media)
            val blobs = blossom.uploadAny(mediaServers, enc.data, keys, copies = 2, mime = mime)
            pendingBytesSent.addAndGet(enc.data.size.toLong() * blobs.size)
            mediaJson = draft.copy(urls = blobs.map { it.url }).toJson()
        }
        val body = linkedMapOf<String, Any?>("text" to text.trim().take(Story.MAX_TEXT), "bg" to bg, "close" to closeFriendsOnly)
        if (mediaJson != null) body["media"] = mediaJson
        val rumor = GiftWrap.rumor(
            me, Kinds.STORY, app.amend.messenger.core.json.Json.write(body),
            listOf(listOf("expiration", expires.toString()), listOf("amend", "story")), now,
        )
        val mine = Story(rumor.id, me, now, expires, mediaJson, text.trim().take(Story.MAX_TEXT), bg, closeFriendsOnly, audience)
        _stories.update { it + mine }
        saveStories()
        var sent = 0
        (audience + me).forEach { peer ->
            val wrap = GiftWrap.wrap(rumor, keys, peer, expiration = expires)
            mutex.withLock { seenWraps.add(wrap.id) }
            meshOutbox?.invoke(wrap)
            val ok = runCatching { pool.publish(wrap, inboxFor(peer)).any { it.accepted } }.getOrDefault(false)
            if (ok && peer != me) sent++
        }
        return sent
    }

    private fun handleStory(rumor: Event, fromMe: Boolean) {
        val expires = rumor.firstTag("expiration")?.toLongOrNull() ?: (rumor.createdAt + STORY_TTL)
        val now = Event.nowSeconds()
        if (expires <= now || expires > rumor.createdAt + STORY_TTL + 600) return
        if (_stories.value.any { it.id == rumor.id }) return
        val m = runCatching { app.amend.messenger.core.json.Json.parseObject(rumor.content) }.getOrNull() ?: return
        val media = (m["media"] as? String)?.takeIf { MediaInfo.fromJson(it) != null }
        val text = (m["text"] as? String).orEmpty().take(Story.MAX_TEXT)
        if (media == null && text.isBlank()) return
        val story = Story(
            rumor.id, rumor.pubkey, rumor.createdAt, expires, media, text, ((m["bg"] as? Number)?.toInt() ?: 0).coerceIn(0, 15),
            m["close"] == true, seen = fromMe,
        )
        _stories.update { (it + story).sortedBy { s -> s.createdAt } }
        saveStories()
    }

    /** I watched [storyId]: remember it, and tell its author if read receipts are on. */
    suspend fun markStorySeen(storyId: String) {
        val s = _stories.value.firstOrNull { it.id == storyId } ?: return
        if (s.author == me || s.seen) return
        _stories.update { list -> list.map { if (it.id == storyId) it.copy(seen = true) else it } }
        saveStories()
        if (!_eco.value.readReceipts) return
        val rumor = GiftWrap.rumor(me, Kinds.STORY_VIEW, "", listOf(listOf("p", s.author), listOf("e", storyId)))
        runCatching { publishRumor(s.author, rumor, s.expiresAt) }
    }

    private fun applyStoryView(storyId: String, by: String, at: Long) {
        val s = _stories.value.firstOrNull { it.id == storyId && it.author == me } ?: return
        if (by !in s.audience || by in s.viewers) return
        _stories.update { list -> list.map { if (it.id == storyId) it.copy(viewers = it.viewers + (by to at)) else it } }
        saveStories()
    }

    /** Deletes one of my stories here and for everyone it went to. */
    suspend fun deleteStory(storyId: String) {
        val s = _stories.value.firstOrNull { it.id == storyId && it.author == me } ?: return
        removeStory(storyId, me)
        val rumor = GiftWrap.rumor(me, Kinds.DELETION, "", listOf(listOf("e", storyId), listOf("k", Kinds.STORY.toString())))
        (s.audience + me).forEach { peer ->
            val wrap = GiftWrap.wrap(rumor, keys, peer, expiration = s.expiresAt)
            mutex.withLock { seenWraps.add(wrap.id) }
            runCatching { pool.publish(wrap, inboxFor(peer)) }
        }
    }

    private fun removeStory(storyId: String, by: String) {
        val s = _stories.value.firstOrNull { it.id == storyId && it.author == by } ?: return
        s.media?.let { MediaInfo.fromJson(it) }?.let { runCatching { localMediaFile(it).delete() } }
        _stories.update { list -> list.filterNot { it.id == storyId } }
        saveStories()
    }

    /** A reply to someone's story arrives in your chat with them. */
    suspend fun replyToStory(storyId: String, text: String) {
        val s = _stories.value.firstOrNull { it.id == storyId } ?: return
        if (s.author == me || text.isBlank()) return
        sendText(s.author, "↪ Replying to your story: ${text.trim()}")
    }

    // ---------------------------------------------------------------- instants

    fun isInstant(m: ChatMessage): Boolean = m.type == MessageType.FILE && MediaInfo.fromJson(m.extra)?.kind == MediaKind.INSTANT

    val closeFriends: Set<String> get() = settings.closeFriends

    /** Instants go to everyone in my chat list unless I chose "only selected people" in Settings. */
    val instantsToAll: Boolean get() = settings.instantsToAll

    fun setInstantsToAll(all: Boolean) {
        settings = settings.copy(instantsToAll = all)
        persistSettings()
    }

    /** Instants sent to me that I haven't opened yet, newest first. */
    fun unopenedInstants(): List<ChatMessage> = _conversations.value.values
        .flatMap { c -> c.messages.filter { !it.fromMe && isInstant(it) } }
        .sortedByDescending { it.createdAt }

    fun setCloseFriends(keys: Set<String>) {
        settings = settings.copy(closeFriends = keys.filter { it.length == 64 && it != me }.toSet())
        persistSettings()
    }

    /** People you can send Instants and stories to: your accepted 1:1 contacts. */
    fun contactKeys(): List<String> = _conversations.value.values
        .filter { !it.isGroup && it.peer != me && it.peer.length == 64 && it.peer in settings.accepted && !isBlocked(it.peer) }
        .sortedByDescending { it.lastActivity }.map { it.peer }

    private val _instantArchive = MutableStateFlow(store.loadInstantArchive())
    /** Instants you sent, kept privately on this phone for a year. */
    val instantArchive: StateFlow<List<ArchivedInstant>> = _instantArchive.asStateFlow()

    fun archivedInstantFile(a: ArchivedInstant): File = store.instantFile(a.id)

    /**
     * Sends a camera photo as an Instant to each of [peers] (people or shared gardens): it is
     * encrypted and uploaded once, disappears for each person once they open it, and expires
     * after 24 hours if nobody does. A private copy goes into your Instants archive.
     */
    suspend fun sendInstant(peers: List<String>, jpeg: ByteArray, width: Int, height: Int, caption: String): Int {
        val targets = peers.distinct().filter { it != me && !isBlocked(it) }
        if (targets.isEmpty()) return 0
        val cap = caption.trim().take(MAX_CAPTION)
        val enc = app.amend.messenger.core.crypto.FileCrypto.encrypt(jpeg)
        val archived = ArchivedInstant(enc.sha256Hex.take(32), Event.nowSeconds(), cap, targets.size)
        store.saveInstant(archived, jpeg)
        _instantArchive.update { (listOf(archived) + it).filter { a -> a.createdAt > Event.nowSeconds() - 365L * 86400 } }
        store.saveInstantArchive(_instantArchive.value)
        val blobs = blossom.uploadAny(mediaServers, enc.data, keys, copies = 2, mime = "image/jpeg")
        pendingBytesSent.addAndGet(enc.data.size.toLong() * blobs.size)
        val media = MediaInfo(
            MediaKind.INSTANT, blobs.map { it.url }, "image/jpeg", enc.key.toHex(), enc.nonce.toHex(), enc.sha256Hex,
            enc.data.size.toLong(), width = width, height = height,
        )
        val expires = Event.nowSeconds() + INSTANT_TTL
        var delivered = 0
        targets.forEach { peer ->
            val tags = (pTags(peer) + media.tags(peer).filter { it.firstOrNull() != "p" }).toMutableList()
            tags.add(listOf("expiration", expires.toString()))
            if (cap.isNotEmpty()) tags.add(listOf("amend-caption", cap))
            val rumor = GiftWrap.rumor(me, Kinds.FILE_MESSAGE, media.urls.first(), tags)
            upsertMessage(
                ChatMessage(rumor.id, peer, true, cap.ifEmpty { MediaKind.INSTANT.label }, rumor.createdAt, MessageType.FILE,
                    status = MessageStatus.SENDING, extra = media.toJson(), expiresAt = expires),
                countUnread = false,
            )
            if (!isGroup(peer)) accept(peer)
            val n = runCatching { publishRumor(peer, rumor, expires) }.getOrDefault(0)
            setMessageStatus(peer, rumor.id, if (n > 0) MessageStatus.SENT else MessageStatus.FAILED, n)
            if (n > 0 || meshOutbox != null) delivered++
        }
        recordMessageStats(received = false)
        return delivered
    }

    /** After viewing an Instant: it's gone from this phone, and the sender is told it was opened. */
    suspend fun consumeInstant(peer: String, id: String) {
        val m = _conversations.value[peer]?.messages?.firstOrNull { it.id == id } ?: return
        if (m.fromMe || !isInstant(m)) return
        val author = m.author ?: m.peer
        consumeInstantLocally(id)
        val rumor = GiftWrap.rumor(me, Kinds.INSTANT_OPENED, "", listOf(listOf("p", author), listOf("e", id)))
        runCatching { publishRumor(author, rumor, Event.nowSeconds() + INSTANT_TTL) }
    }

    private suspend fun consumeInstantLocally(id: String) {
        mutex.withLock {
            var changed: String? = null
            _conversations.update { all ->
                all.mapValues { (peer, c) ->
                    if (c.messages.none { it.id == id && !it.fromMe }) c
                    else {
                        changed = peer
                        c.copy(messages = c.messages.map { m ->
                            if (m.id == id && !m.fromMe) {
                                MediaInfo.fromJson(m.extra)?.let { runCatching { localMediaFile(it).delete() } }
                                m.copy(type = MessageType.NOTICE, text = "📸 You opened an Instant", extra = null, cardId = null)
                            } else m
                        })
                    }
                }
            }
            changed?.let { p -> _conversations.value[p]?.let { store.saveChat(p, it.messages) } }
        }
    }

    private suspend fun markInstantOpened(id: String, by: String) {
        mutex.withLock {
            var changed: String? = null
            _conversations.update { all ->
                all.mapValues { (peer, c) ->
                    val hit = c.messages.any { it.id == id && it.fromMe && (peer == by || (isGroup(peer) && by in (groupInfo(peer)?.members ?: emptyList()))) }
                    if (!hit) c else {
                        changed = peer
                        c.copy(messages = c.messages.map { if (it.id == id) it.copy(cardId = INSTANT_OPENED_MARK) else it })
                    }
                }
            }
            changed?.let { p -> _conversations.value[p]?.let { store.saveChat(p, it.messages) } }
        }
    }

    /** Runs work that should finish even if the screen that started it closes (uploads etc.). */
    fun launchInBackground(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Leave without waiting: keeps going after the chat screen closes. */
    fun leaveGroupInBackground(key: String) {
        scope.launch { leaveGroup(key) }
    }

    /** Says goodbye in the garden, then stops receiving it on this phone. */
    suspend fun leaveGroup(key: String) {
        runCatching {
            sendRumor(key, "👋 ${myName()} left the garden", pTags(key) + listOf(listOf("amend", "group", "leave")), MessageType.NOTICE, null, null, noExpiry = true)
        }
        block(key)
    }

    // ---------------------------------------------------------------- contacts

    fun accept(peer: String) {
        if (peer in settings.accepted) return
        settings = settings.copy(accepted = settings.accepted + peer)
        persistSettings()
        resubscribeMetadata()
    }

    fun isAccepted(peer: String) = peer in settings.accepted

    fun isBlocked(peer: String) = peer in settings.blocked || peer in settings.modBlocked

    fun addContact(peer: String, nickname: String = "") {
        accept(peer)
        if (nickname.isNotBlank()) setNickname(peer, nickname)
        _conversations.update { all -> if (all.containsKey(peer)) all else all + (peer to Conversation(peer, nickname = nickname)) }
    }

    fun setNickname(peer: String, nickname: String) {
        settings = settings.copy(nicknames = settings.nicknames + (peer to nickname.trim()))
        _conversations.update { all -> all[peer]?.let { all + (peer to it.copy(nickname = nickname.trim())) } ?: all }
        persistSettings()
    }

    fun setPinned(peer: String, pinned: Boolean) {
        settings = settings.copy(pinned = if (pinned) settings.pinned + peer else settings.pinned - peer)
        _conversations.update { all -> all[peer]?.let { all + (peer to it.copy(pinned = pinned)) } ?: all }
        persistSettings()
    }

    fun block(peer: String) {
        settings = settings.copy(blocked = settings.blocked + peer, accepted = settings.accepted - peer, modAdded = settings.modAdded - peer)
        deleteConversation(peer)
        persistSettings()
    }

    /** Report a person for abuse. With no central server to appeal to, the strongest
     *  action we can take for the user is to block them and remove the conversation. */
    fun report(peer: String) = block(peer)

    // ---------------------------------------------------------------- safety

    fun hideNote(note: Event, author: Boolean) {
        hiddenNotes += if (author) note.pubkey else note.id
        _feed.update { list -> list.filterNot { if (author) it.pubkey == note.pubkey else it.id == note.id } }
    }

    /** Whose blocklist this phone follows (tests swap in their own key). */
    internal var moderatorPubkey: String = Moderation.MODERATOR

    /** The moderator's shared blocklist: block every listed account here, unblock any removed. */
    internal suspend fun applyModerationList(e: Event) {
        val keys = Moderation.parse(e, moderatorPubkey) ?: return
        if (e.createdAt <= settings.modListAt) return
        // a list dated in the future would lock out every real update after it
        if (e.createdAt > Event.nowSeconds() + Moderation.MAX_CLOCK_SKEW) return
        val added = keys - settings.modBlocked
        val removed = settings.modBlocked - keys
        // lifting a moderator block only undoes the block the list made, never one you made yourself
        val madeByList = (settings.modAdded + (added - settings.blocked)) - removed
        val unblock = removed.filter { it in settings.modAdded }
        settings = settings.copy(
            modBlocked = keys, modListAt = e.createdAt, modAdded = madeByList,
            blocked = settings.blocked + added - unblock.toSet(),
            accepted = settings.accepted - added,
        )
        // The list only hides: a remote list never deletes anything from this phone, so a
        // mistaken (or forged-key) entry can be undone without losing the history.
        _conversations.update { all -> all - added }
        val restore = unblock.filter { it != me }.mapNotNull { p -> store.loadChat(p)?.let { p to it } }
        if (restore.isNotEmpty()) _conversations.update { all ->
            all + restore.filter { (p, _) -> p !in all }.map { (p, msgs) ->
                p to Conversation(
                    peer = p, messages = msgs.sortedBy { it.createdAt }, pinned = p in settings.pinned,
                    nickname = settings.nicknames[p] ?: "", disappearSecs = settings.disappear[p] ?: 0,
                )
            }
        }
        _modBlocked.value = keys
        _feed.update { list -> list.filterNot { it.pubkey in keys } }
        persistSettings()
    }

    /** Moderator only: publishes a new shared blocklist signed with the moderator key. */
    suspend fun publishModerationList(moderator: KeyPair, keys: Collection<String>): Int {
        val ev = Moderation.build(moderator, keys, moderatorPubkey)
        applyModerationList(ev)
        return runCatching { pool.publish(ev).count { it.accepted } }.getOrDefault(0)
    }

    /** The last N messages of a chat, as plain text, for a report the user chooses to send. */
    fun reportEvidence(peer: String, count: Int = 10): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC)
        val msgs = _conversations.value[peer]?.messages.orEmpty().filter { it.status != MessageStatus.SCHEDULED }.takeLast(count)
        return msgs.joinToString("\n") { m ->
            val who = if (m.fromMe) "me" else Nip19.npub(m.author ?: m.peer)
            val body = when (m.type) {
                MessageType.TEXT -> m.text
                MessageType.UNSENT -> "[unsent]"
                else -> "[${m.type.name.lowercase()}] ${m.text}".trim()
            }
            "${fmt.format(java.time.Instant.ofEpochSecond(m.createdAt))} UTC · $who: $body"
        }
    }

    fun deleteConversation(peer: String) {
        _conversations.update { it - peer }
        store.deleteChat(peer)
    }

    fun markRead(peer: String) {
        markSeen(peer)
        _conversations.update { all -> all[peer]?.takeIf { it.unread > 0 }?.let { all + (peer to it.copy(unread = 0)) } ?: all }
    }

    fun displayName(peer: String): String {
        if (isGroup(peer)) {
            val g = groupInfo(peer)
            if (!g?.name.isNullOrBlank()) return g!!.name
            val others = g?.members.orEmpty().filter { it != me }.map { displayName(it) }
            return when {
                others.isEmpty() -> "Shared garden"
                others.size <= 2 -> others.joinToString(" & ")
                else -> others.take(2).joinToString(", ") + " & ${others.size - 2} more"
            }
        }
        val conv = _conversations.value[peer]
        if (!conv?.nickname.isNullOrBlank()) return conv!!.nickname
        val p = _profiles.value[peer]
        if (!p?.name.isNullOrBlank()) return p!!.name
        return app.amend.messenger.core.crypto.Nip19.short(peer)
    }

    // ---------------------------------------------------------------- profile & relays

    val myProfile: Profile get() = _profiles.value[me] ?: Profile(me)

    /**
     * Saves and publishes the profile. [picture] null keeps the current photo,
     * "" removes it, otherwise a small data URI or https URL.
     */
    suspend fun updateProfile(name: String, about: String, avatar: String, hue: Float, picture: String? = null, nip05: String? = null) {
        val pic = when {
            picture == null -> myProfile.picture
            picture.isEmpty() || Profile.isValidPicture(picture) -> picture
            else -> myProfile.picture
        }
        val p = Profile(me, name.trim().take(60), about.trim().take(300), avatar, hue, Event.nowSeconds(), pic, nip05?.trim()?.take(100) ?: myProfile.nip05)
        _profiles.update { it + (me to p) }
        store.saveProfiles(_profiles.value)
        val ev = Event.sign(keys.secretKey, Kinds.METADATA, p.toMetadataJson())
        runCatching { pool.publish(ev) }
    }

    /** Tells other NIP-17 apps where to deliver our private messages (kind 10050). */
    suspend fun publishInboxRelays() {
        val ev = Event.sign(keys.secretKey, Kinds.DM_RELAYS, "", settings.relays.map { listOf("relay", it) })
        runCatching { pool.publish(ev) }
    }

    fun setRelays(urls: List<String>) {
        // TLS only: Android blocks clear-text connections anyway, and ws:// would expose relay traffic
        val clean = urls.map { RelayPool.normalize(it) }.filter { it.startsWith("wss://") }.distinct()
        if (clean.isEmpty()) return
        settings = settings.copy(relays = clean)
        _relays.value = clean
        pool.setRelays(clean)
        persistSettings()
        scope.launch { publishInboxRelays() }
    }

    // ---------------------------------------------------------------- eco

    private fun today() = LocalDate.now().toString()

    /** New trees (amend 4.2+) choose from every species, including the flowering ones. */
    private fun seedFor(index: Int) = app.amend.messenger.eco.Species.markSeed(Hashes.sha256("amend-tree:$me:$index".toByteArray())).toHex()

    /** The species of the tree growing in my forest right now. */
    fun growingSpecies(): app.amend.messenger.eco.TreeSpecies =
        app.amend.messenger.eco.Species.fromSeed(seedFor(_eco.value.trees.size).hexToBytes())

    private fun award(points: Int, reason: String, isMessage: Boolean) {
        var event: GrowthEvent? = null
        _eco.update { s ->
            val (ns, ev) = EcoRules.addPoints(s, today(), points, isMessage, ::seedFor, System.currentTimeMillis())
            event = ev
            ns
        }
        store.saveEco(_eco.value)
        event?.let { ev ->
            if (ev.pointsAdded > 0 && !isMessage) _events.tryEmit(AmendEvent.PointsEarned(ev.pointsAdded, reason))
            ev.newTree?.let { _events.tryEmit(AmendEvent.TreeGrown(it)) }
        }
    }

    private fun recordMessageStats(received: Boolean) {
        _eco.update { if (received) it.copy(messagesReceived = it.messagesReceived + 1) else it.copy(messagesSent = it.messagesSent + 1) }
        award(EcoPoints.MESSAGE, "Message", isMessage = true)
    }

    fun completeChallenge(challengeId: String): Boolean {
        val c = Challenges.byId(challengeId) ?: return false
        val day = today()
        if (_eco.value.completedChallenges[day] == challengeId) return false
        _eco.update {
            it.copy(
                completedChallenges = it.completedChallenges + (day to challengeId),
                co2AvoidedGrams = it.co2AvoidedGrams + c.co2Grams,
                dailyAvoided = (it.dailyAvoided + (day to (it.dailyAvoided[day] ?: 0L) + c.co2Grams)).lastDays(),
            )
        }
        award(EcoPoints.CHALLENGE, "Challenge: ${c.title}", isMessage = false)
        return true
    }

    // ---------------------------------------------------------------- the Grove (public notes)

    private val _feed = MutableStateFlow<List<Event>>(emptyList())
    /** Public Nostr notes (kind 1) from me and my contacts, newest first. Fetched only while the Grove is open. */
    val feed: StateFlow<List<Event>> = _feed.asStateFlow()

    private fun feedAuthors(): List<String> =
        (listOf(me) + _conversations.value.values
            .filter { !it.isGroup && it.peer in settings.accepted && it.peer !in settings.blocked && it.peer.length == 64 }
            .sortedByDescending { it.lastActivity }.map { it.peer }).distinct().take(250)

    fun openFeed() {
        pool.subscribe(SUB_FEED, listOf(mapOf("kinds" to listOf(Kinds.TEXT_NOTE), "authors" to feedAuthors(), "limit" to 150)))
    }

    fun closeFeed() = pool.unsubscribe(SUB_FEED)

    private fun addNote(e: Event) {
        if (e.pubkey in settings.blocked || e.pubkey in settings.modBlocked || e.pubkey in hiddenNotes || e.id in hiddenNotes || e.content.isBlank()) return
        if (e.pubkey != me && e.pubkey !in settings.accepted) return
        // replies and reposts make little sense without their thread; keep top-level notes
        if (e.tags.any { it.firstOrNull() == "e" }) return
        _feed.update { list -> if (list.any { it.id == e.id }) list else (list + e).sortedByDescending { it.createdAt }.take(300) }
    }

    /** Publishes a PUBLIC note (anyone on Nostr can read it). Returns how many relays took it. */
    suspend fun postNote(text: String): Int {
        val body = text.trim().take(2000)
        if (body.isEmpty()) return 0
        val ev = Event.sign(keys.secretKey, Kinds.TEXT_NOTE, body, listOf(listOf("client", "amend")))
        _feed.update { (listOf(ev) + it).take(300) }
        return runCatching { pool.publish(ev).count { it.accepted } }.getOrDefault(0)
    }

    /** NIP-09: asks relays to delete one of my notes. */
    suspend fun deleteNote(id: String) {
        _feed.update { list -> list.filterNot { it.id == id && it.pubkey == me } }
        val ev = Event.sign(keys.secretKey, Kinds.DELETION, "", listOf(listOf("e", id), listOf("k", "${Kinds.TEXT_NOTE}")))
        runCatching { pool.publish(ev) }
    }

    // ---------------------------------------------------------------- friends' leaderboard

    /** Opt in/out of sharing my forest with my contacts. Turning it off tells them to forget it. */
    fun setShareStats(on: Boolean) {
        _eco.update { it.copy(shareStats = on, statsSentDay = "") }
        store.saveEco(_eco.value)
        if (!on) scope.launch { runCatching { broadcastEcoStats(off = true) } }
    }

    /** Once a day (while online): my trees/points/streak to each accepted contact, gift-wrapped. */
    private suspend fun sendEcoStatsIfDue() {
        val e = _eco.value
        // once a day, and again whenever a new tree has grown
        val mark = "${today()}:${e.trees.size}"
        if (!e.shareStats || e.statsSentDay == mark || !isOnline()) return
        _eco.update { it.copy(statsSentDay = mark) }
        store.saveEco(_eco.value)
        broadcastEcoStats(off = false)
    }

    private suspend fun broadcastEcoStats(off: Boolean) {
        val e = _eco.value
        val content = app.amend.messenger.core.json.Json.write(
            if (off) linkedMapOf("off" to true) else linkedMapOf(
                "trees" to e.trees.size, "points" to e.totalPoints, "streak" to e.streak,
                "forest" to e.trees.takeLast(FriendStats.MAX_FOREST).map { it.seedHex.take(6).padEnd(6, '0') },
                "growing" to e.forest.pointsInCurrent.coerceIn(0, 99),
            )
        )
        val contacts = _conversations.value.values
            .filter { !it.isGroup && it.peer != me && it.peer in settings.accepted && it.peer !in settings.blocked }
            .sortedByDescending { it.lastActivity }.take(50).map { it.peer }
        contacts.forEach { peer ->
            val rumor = GiftWrap.rumor(me, Kinds.ECO_STATS, content, listOf(listOf("p", peer), listOf("amend", "eco-stats")))
            val wrap = GiftWrap.wrap(rumor, keys, peer)
            mutex.withLock { seenWraps.add(wrap.id) }
            runCatching { pool.publish(wrap, inboxFor(peer)) }
        }
    }

    private fun applyFriendStats(peer: String, rumor: Event) {
        val m = runCatching { app.amend.messenger.core.json.Json.parseObject(rumor.content) }.getOrNull() ?: return
        val old = _friendStats.value[peer]
        if (old != null && old.updatedAt > rumor.createdAt) return
        if (m["off"] == true) {
            _friendStats.update { it - peer }
        } else {
            fun n(k: String) = ((m[k] as? Number)?.toInt() ?: 0).coerceIn(0, 10_000_000)
            _friendStats.update {
                it + (peer to FriendStats(
                    n("trees"), n("points"), n("streak"), rumor.createdAt,
                    FriendStats.cleanForest(m["forest"]), n("growing").coerceIn(0, 99),
                ))
            }
        }
        store.saveFriendStats(_friendStats.value)
    }

    fun setEfficiencyMode(on: Boolean) {
        _eco.update { it.copy(efficiencyMode = on) }
        store.saveEco(_eco.value)
    }

    fun setInstantNotifications(on: Boolean) {
        _eco.update { it.copy(instantNotifications = on) }
        store.saveEco(_eco.value)
    }

    /** True while the relay pool has at least one live connection. */
    fun isOnline(): Boolean = pool.onlineCount() > 0

    fun setCarbonAwareSync(on: Boolean) {
        _eco.update { it.copy(carbonAwareSync = on) }
        store.saveEco(_eco.value)
    }

    private fun markActiveToday() {
        val day = today()
        val s = _eco.value
        if (s.lastActiveDay == day) return
        val yesterday = LocalDate.now().minusDays(1).toString()
        _eco.update { it.copy(lastActiveDay = day, streak = if (it.lastActiveDay == yesterday) it.streak + 1 else 1) }
        if (s.efficiencyMode) award(EcoPoints.EFFICIENT_DAY, "Efficiency mode day", isMessage = false)
        // carbon-aware sync only applies when amend isn't keeping a live connection
        if (s.carbonAwareSync && !s.instantNotifications) award(EcoPoints.CARBON_AWARE_DAY, "Carbon-aware sync day", isMessage = false)
        store.saveEco(_eco.value)
    }

    private fun flushBytes() {
        val s = pendingBytesSent.getAndSet(0)
        val r = pendingBytesReceived.getAndSet(0)
        if (s == 0L && r == 0L) return
        val day = today()
        _eco.update {
            it.copy(
                bytesSent = it.bytesSent + s, bytesReceived = it.bytesReceived + r,
                dailyBytes = (it.dailyBytes + (day to (it.dailyBytes[day] ?: 0L) + s + r)).lastDays(),
            )
        }
        store.saveEco(_eco.value)
    }

    // ---------------------------------------------------------------- persistence

    private fun persistSettings() {
        settings = settings.copy(seenWraps = seenWraps.toList().takeLast(5000))
        store.saveSettings(settings)
    }

    private fun persistSettingsSoon() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(1_000)
            persistSettings()
        }
    }

    /** Waits until at least one relay is online (or times out). */
    suspend fun awaitOnline(timeoutMs: Long = 15_000): Boolean =
        withTimeoutOrNull(timeoutMs) { pool.statuses.first { list -> list.any { it.state == app.amend.messenger.net.RelayState.ONLINE } } } != null

    companion object {
        const val SUB_DM = "amend-dm"
        const val SUB_META = "amend-meta"
        const val SUB_ECO_PREFIX = "eco-"
        const val SUB_FEED = "amend-feed"
        /** Receipts are only sent for messages from the last week. */
        const val RECEIPT_MAX_AGE = 7L * 24 * 3600
        /** How long after sending a message can still be unsent (15 minutes). */
        const val UNSEND_WINDOW = 15L * 60
        const val MAX_EDIT_CHARS = 8000
        /** One person can sound this many SOS alarms here per [SOS_ALARM_WINDOW] seconds. */
        const val SOS_ALARMS_PER_WINDOW = 5
        const val SOS_ALARM_WINDOW = 10L * 60
        /** SOS signals dated further ahead than this are ignored. */
        const val SOS_MAX_CLOCK_SKEW = 10L * 60
        const val MAX_CAPTION = 1000
        /** Instants and stories last a day. */
        const val INSTANT_TTL = 24L * 3600
        const val STORY_TTL = 24L * 3600
        const val INSTANT_OPENED_MARK = "opened"
    }
}
