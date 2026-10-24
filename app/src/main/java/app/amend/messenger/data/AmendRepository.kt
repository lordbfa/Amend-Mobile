package app.amend.messenger.data

import app.amend.messenger.core.crypto.toHex
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
}

/**
 * The heart of amend: identity, conversations, relay networking and eco progress.
 * Pure Kotlin/JVM (no Android classes), so it's also exercised by the CI relay test.
 */
class AmendRepository(
    dir: File,
    val keys: KeyPair,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val me: String = keys.publicKeyHex
    private val store = Store(dir)
    private val mutex = Mutex()

    private var settings = store.loadSettings(RelayPool.DEFAULT_RELAYS).let { s ->
        // Upgrade installs still on the original relay list (two of those relays proved unreliable).
        if (s.relays.sorted() == RelayPool.LEGACY_DEFAULTS.sorted()) s.copy(relays = RelayPool.DEFAULT_RELAYS) else s
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
        when (rumor.kind) {
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
                }
            }
            Kinds.REACTION -> {
                val target = rumor.firstTag("e") ?: return
                applyReaction(peer, target, rumor.pubkey, rumor.content.take(8))
            }
        }
        persistSettingsSoon()
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
                            status = if (old.status == MessageStatus.SENDING || old.status == MessageStatus.FAILED) message.status else old.status,
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
                all + (peer to conv.copy(messages = conv.messages.map { if (it.id == final.id) final else it }))
            }
            store.saveChat(peer, _conversations.value[peer]!!.messages)
        }
        _events.tryEmit(AmendEvent.MessageSent(peer, accepted))
        return final
    }

    suspend fun retry(peer: String, messageId: String) {
        val msg = _conversations.value[peer]?.messages?.firstOrNull { it.id == messageId } ?: return
        val tags = mutableListOf(listOf("p", peer))
        msg.replyTo?.let { tags.add(listOf("e", it)) }
        msg.cardId?.let { tags.add(listOf("amend", "card", it)) }
        val rumor = Event.unsigned(me, Kinds.CHAT_MESSAGE, msg.text, tags, msg.createdAt)
        val accepted = runCatching { publishRumor(peer, rumor) }.getOrDefault(0)
        mutex.withLock {
            _conversations.update { all ->
                val conv = all[peer] ?: return@update all
                all + (peer to conv.copy(messages = conv.messages.map {
                    if (it.id == messageId) it.copy(status = if (accepted > 0) MessageStatus.SENT else MessageStatus.FAILED, relaysAccepted = accepted) else it
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

    suspend fun updateProfile(name: String, about: String, avatar: String, hue: Float) {
        val p = Profile(me, name.trim().take(60), about.trim().take(300), avatar, hue, Event.nowSeconds())
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
        if (s.carbonAwareSync) award(EcoPoints.CARBON_AWARE_DAY, "Carbon-aware sync day", isMessage = false)
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
    }
}
