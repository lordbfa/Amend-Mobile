package app.amend.messenger.data

import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.crypto.hexToBytes
import app.amend.messenger.core.crypto.Hashes
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
    private val store = Store(dir)
    private val mutex = Mutex()

    private var settings = store.loadSettings(RelayPool.DEFAULT_RELAYS).let { s ->
        // Upgrade installs still on the original relay list (two of those relays proved unreliable).
        if (RelayPool.PREVIOUS_DEFAULTS.any { it.sorted() == s.relays.sorted() }) s.copy(relays = RelayPool.DEFAULT_RELAYS) else s
    }

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    private val _eco = MutableStateFlow(store.loadEco())
    val eco: StateFlow<EcoState> = _eco.asStateFlow()

    private val _relays = MutableStateFlow(settings.relays)
    val relayUrls: StateFlow<List<String>> = _relays.asStateFlow()

    private val _events = MutableSharedFlow<AmendEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AmendEvent> = _events

    private val _incomingSos = MutableStateFlow<Map<String, IncomingSos>>(emptyMap())
    /** Friends' SOS alerts (active and recently ended), by public key. */
    val incomingSos: StateFlow<Map<String, IncomingSos>> = _incomingSos.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var inboxRelays = store.loadInboxRelays()
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
        val chats = store.loadChats()
        _conversations.value = chats.mapValues { (peer, msgs) ->
            Conversation(
                peer = peer,
                messages = msgs.sortedBy { it.createdAt },
                pinned = peer in settings.pinned,
                nickname = settings.nicknames[peer] ?: "",
            )
        }
        pool.setRelays(settings.relays)
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
        pool.subscribe(SUB_DM, listOf(dmFilter))
        resubscribeMetadata()
    }

    private fun resubscribeMetadata() {
        val authors = (_conversations.value.keys + settings.accepted + me).filter { it.length == 64 }.distinct()
        pool.subscribe(SUB_META, listOf(linkedMapOf("kinds" to listOf(Kinds.METADATA, Kinds.DM_RELAYS), "authors" to authors)))
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun handleIncoming(msg: RelayIncoming) {
        when (msg) {
            is RelayIncoming.EventMsg -> when (msg.event.kind) {
                Kinds.GIFT_WRAP -> handleWrap(msg.event)
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
        mutex.withLock {
            if (!seenWraps.add(wrap.id)) return
        }
        val rumor = try {
            GiftWrap.unwrap(wrap, keys)
        } catch (e: Exception) {
            return
        }
        val fromMe = rumor.pubkey == me
        val peer = if (fromMe) rumor.tagValues("p").firstOrNull { it != me } ?: me else rumor.pubkey
        if (peer in settings.blocked) return
        if (rumor.kind == Kinds.CHAT_MESSAGE) {
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
                    text = when (media.kind) { MediaKind.VOICE -> "🎤 Voice message"; MediaKind.VIDEO -> "🎥 Video message"; MediaKind.FILE -> "📎 File" },
                    createdAt = rumor.createdAt,
                    type = when (media.kind) { MediaKind.VOICE -> MessageType.VOICE; MediaKind.VIDEO -> MessageType.VIDEO; MediaKind.FILE -> MessageType.FILE },
                    replyTo = rumor.firstTag("e"),
                    status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                    extra = media.toJson(),
                )
                val added = upsertMessage(message, countUnread = !fromMe)
                if (added && !fromMe) {
                    recordMessageStats(received = true)
                    _events.tryEmit(AmendEvent.MessageArrived(peer, message))
                    queueDelivered(message)
                }
            }
            Kinds.CHAT_MESSAGE -> {
                val cardId = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "card" }?.get(2)
                val message = ChatMessage(
                    id = rumor.id,
                    peer = peer,
                    fromMe = fromMe,
                    text = rumor.content,
                    createdAt = rumor.createdAt,
                    type = if (cardId != null && Challenges.byId(cardId) != null) MessageType.ECO_CARD else MessageType.TEXT,
                    cardId = cardId,
                    replyTo = rumor.firstTag("e"),
                    status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                )
                val added = upsertMessage(message, countUnread = !fromMe)
                if (added && !fromMe) {
                    recordMessageStats(received = true)
                    _events.tryEmit(AmendEvent.MessageArrived(peer, message))
                    queueDelivered(message)
                }
            }
            Kinds.REACTION -> {
                val target = rumor.firstTag("e") ?: return
                applyReaction(peer, target, rumor.pubkey, rumor.content.take(8))
            }
            Kinds.RECEIPT -> if (!fromMe) applyReceipt(peer, rumor)
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
        val fresh = rumor.createdAt >= Event.nowSeconds() - 6 * 3600
        _incomingSos.update { it + (peer to sos.copy(active = sos.active && fresh)) }
        if (fresh) _events.tryEmit(AmendEvent.SosReceived(peer, sos, signal.phase))
    }

    /** People who receive my SOS: everyone I've chatted with, minus [excluded] and blocked contacts. */
    fun sosRecipients(excluded: Set<String>): List<String> =
        _conversations.value.values
            .filter { it.peer != me && it.peer !in settings.blocked && it.peer !in excluded }
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
                    pool.publish(wrap, inboxRelays[peer].orEmpty()).count { it.accepted }
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

    /** Wraps a rumor for the peer and for ourselves, and publishes both. Returns relays that accepted. */
    private suspend fun publishRumor(peer: String, rumor: Event): Int {
        val toPeer = GiftWrap.wrap(rumor, keys, peer)
        val toSelf = if (peer != me) GiftWrap.wrap(rumor, keys, me) else null
        mutex.withLock {
            seenWraps.add(toPeer.id)
            toSelf?.let { seenWraps.add(it.id) }
        }
        val results = pool.publish(toPeer, inboxRelays[peer].orEmpty())
        toSelf?.let { scope.launch { pool.publish(it) } }
        return results.count { it.accepted }
    }

    suspend fun sendText(peer: String, text: String, replyTo: String? = null): ChatMessage {
        val tags = mutableListOf(listOf("p", peer))
        replyTo?.let { tags.add(listOf("e", it)) }
        return sendRumor(peer, text.trim(), tags, MessageType.TEXT, null, replyTo)
    }

    suspend fun shareChallenge(peer: String, challengeId: String): ChatMessage {
        val c = Challenges.byId(challengeId) ?: throw IllegalArgumentException("unknown challenge")
        val text = "${c.category.emoji} Eco challenge: ${c.title} — ${c.description}"
        val msg = sendRumor(peer, text, listOf(listOf("p", peer), listOf("amend", "card", c.id)), MessageType.ECO_CARD, c.id, null)
        award(EcoPoints.SHARE_CARD, "Shared an eco challenge", isMessage = false)
        return msg
    }

    private suspend fun sendRumor(
        peer: String, content: String, tags: List<List<String>>, type: MessageType, cardId: String?, replyTo: String?,
    ): ChatMessage {
        val rumor = GiftWrap.rumor(me, Kinds.CHAT_MESSAGE, content, tags)
        val local = ChatMessage(rumor.id, peer, true, content, rumor.createdAt, type, cardId, replyTo, status = MessageStatus.SENDING)
        upsertMessage(local, countUnread = false)
        accept(peer)
        recordMessageStats(received = false)
        val accepted = try {
            publishRumor(peer, rumor)
        } catch (e: Exception) {
            0
        }
        val final = local.copy(status = if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED, relaysAccepted = accepted)
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

    // ---------------------------------------------------------------- receipts

    private val pendingDelivered = mutableMapOf<String, MutableSet<String>>() // peer -> message ids
    private var deliveredJob: kotlinx.coroutines.Job? = null

    /** Receipts go only to accepted contacts (a stranger doesn't learn you're online) and only for recent messages. */
    private fun wantsReceipt(m: ChatMessage) =
        !m.fromMe && m.peer in settings.accepted && m.createdAt > Event.nowSeconds() - RECEIPT_MAX_AGE

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
        val unseen = conv.messages.filter { !it.fromMe && it.receipt < 2 }
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
        return pool.publish(wrap, inboxRelays[peer].orEmpty()).count { it.accepted }
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
    ): ChatMessage {
        val enc = app.amend.messenger.core.crypto.FileCrypto.encrypt(plain)
        val draft = MediaInfo(
            kind, emptyList(), mime, enc.key.toHex(), enc.nonce.toHex(), enc.sha256Hex, enc.data.size.toLong(),
            durationMs, waveform, width, height,
        )
        // keep our own plain copy so it plays instantly without downloading
        localMediaFile(draft).writeBytes(plain)
        val type = when (kind) { MediaKind.VOICE -> MessageType.VOICE; MediaKind.VIDEO -> MessageType.VIDEO; MediaKind.FILE -> MessageType.FILE }
        val label = when (kind) { MediaKind.VOICE -> "🎤 Voice message"; MediaKind.VIDEO -> "🎥 Video message"; MediaKind.FILE -> "📎 File" }
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
        val tags = media.tags(peer).toMutableList()
        replyTo?.let { tags.add(listOf("e", it)) }
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
                sendMedia(peer, plain, media.kind, media.mime, media.durationMs, media.waveform, media.width, media.height, msg.replyTo)
            }
            return
        }
        setMessageStatus(peer, msg.id, MessageStatus.SENDING, 0)
        val tags = media.tags(peer).toMutableList()
        msg.replyTo?.let { tags.add(listOf("e", it)) }
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
        val plain = app.amend.messenger.core.crypto.FileCrypto.decrypt(encrypted, media.keyHex.hexToBytes(), media.nonceHex.hexToBytes())
        file.writeBytes(plain)
        return file
    }

    suspend fun retry(peer: String, messageId: String) {
        val msg = _conversations.value[peer]?.messages?.firstOrNull { it.id == messageId } ?: return
        if (msg.type == MessageType.VOICE || msg.type == MessageType.VIDEO || msg.type == MessageType.FILE) {
            retryMedia(peer, msg)
            return
        }
        val tags = mutableListOf(listOf("p", peer))
        msg.replyTo?.let { tags.add(listOf("e", it)) }
        msg.cardId?.let { tags.add(listOf("amend", "card", it)) }
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
        val rumor = GiftWrap.rumor(me, Kinds.REACTION, emoji, listOf(listOf("e", messageId), listOf("p", peer), listOf("k", "14")))
        runCatching { publishRumor(peer, rumor) }
    }

    // ---------------------------------------------------------------- contacts

    fun accept(peer: String) {
        if (peer in settings.accepted) return
        settings = settings.copy(accepted = settings.accepted + peer)
        persistSettings()
        resubscribeMetadata()
    }

    fun isAccepted(peer: String) = peer in settings.accepted

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
        settings = settings.copy(blocked = settings.blocked + peer, accepted = settings.accepted - peer)
        deleteConversation(peer)
        persistSettings()
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
    suspend fun updateProfile(name: String, about: String, avatar: String, hue: Float, picture: String? = null) {
        val pic = when {
            picture == null -> myProfile.picture
            picture.isEmpty() || Profile.isValidPicture(picture) -> picture
            else -> myProfile.picture
        }
        val p = Profile(me, name.trim().take(60), about.trim().take(300), avatar, hue, Event.nowSeconds(), pic)
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
        val clean = urls.map { RelayPool.normalize(it) }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        if (clean.isEmpty()) return
        settings = settings.copy(relays = clean)
        _relays.value = clean
        pool.setRelays(clean)
        persistSettings()
        scope.launch { publishInboxRelays() }
    }

    // ---------------------------------------------------------------- eco

    private fun today() = LocalDate.now().toString()

    private fun seedFor(index: Int) = Hashes.sha256("amend-tree:$me:$index".toByteArray()).toHex()

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
            )
        }
        award(EcoPoints.CHALLENGE, "Challenge: ${c.title}", isMessage = false)
        return true
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
        _eco.update { it.copy(bytesSent = it.bytesSent + s, bytesReceived = it.bytesReceived + r) }
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
        /** Receipts are only sent for messages from the last week. */
        const val RECEIPT_MAX_AGE = 7L * 24 * 3600
    }
}
