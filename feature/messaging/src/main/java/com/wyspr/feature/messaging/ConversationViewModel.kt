package com.wyspr.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.ContactEntity
import com.wyspr.core.database.entities.MessageEntity
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.transport.MessagingNotifier
import com.wyspr.feature.messaging.mailbox.MailboxBindingService
import com.wyspr.feature.messaging.mailbox.MailboxNotifyClient
import com.wyspr.feature.messaging.sync.MessageSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-thread state holder. Streams messages between this device and
 * the chosen peer, exposes the local pubkey so the screen can
 * decide which side of the bubble layout each message goes on, and
 * a `send` action that hands off to [MessageStore].
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: WysprDatabase,
    private val messageStore: MessageStore,
    private val syncService: MessageSyncService,
    private val notifier: MessagingNotifier,
    private val mailboxNotifyClient: MailboxNotifyClient,
    private val bindingService: MailboxBindingService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Composer draft — lives in the VM so typing survives navigation
     *  away (e.g., tap-to-rename) and configuration changes. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** Message the user picked to reply to, or null. While non-null the
     *  composer shows a "quoting X" card and the next send() prepends the
     *  reply marker so the recipient renders the quote pill. */
    private val _replyingTo = MutableStateFlow<MessageEntity?>(null)
    val replyingTo: StateFlow<MessageEntity?> = _replyingTo.asStateFlow()
    fun pickReply(message: MessageEntity?) { _replyingTo.value = message }
    fun cancelReply() { _replyingTo.value = null }

    /** Modal state — title-bar tap shows the peer-detail sheet, rename
     *  shows the rename dialog. Both moved off Composable-local
     *  `remember` so they survive screen rotation. */
    private val _peerDetailsOpen = MutableStateFlow(false)
    val peerDetailsOpen: StateFlow<Boolean> = _peerDetailsOpen.asStateFlow()

    private val _renameOpen = MutableStateFlow(false)
    val renameOpen: StateFlow<Boolean> = _renameOpen.asStateFlow()

    /** Local-only free-form notes about the peer. Edited via the
     *  peer-details sheet's "Notes" expander. Never synced. */
    private val _notesOpen = MutableStateFlow(false)
    val notesOpen: StateFlow<Boolean> = _notesOpen.asStateFlow()

    fun updateDraft(value: String) { _draft.value = value }
    fun clearDraft() { _draft.value = "" }
    fun openPeerDetails() { _peerDetailsOpen.value = true }
    fun closePeerDetails() { _peerDetailsOpen.value = false }
    fun openRename() {
        _peerDetailsOpen.value = false
        _renameOpen.value = true
    }
    fun closeRename() { _renameOpen.value = false }
    fun openNotes() {
        _peerDetailsOpen.value = false
        _notesOpen.value = true
    }
    fun closeNotes() { _notesOpen.value = false }

    /** Persist the user's free-form notes for the bound peer. */
    fun saveNotes(text: String) {
        val peer = peerPub ?: return
        val trimmed = text.trim().takeIf { it.isNotBlank() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                val existing = database.contactDao.byPub(peer)
                database.contactDao.upsert(
                    ContactEntity(
                        peerPub = peer,
                        displayName = existing?.displayName,
                        notes = trimmed,
                    ),
                )
            }
        }
    }

    @Volatile private var ownPub: ByteArray? = null
    @Volatile private var peerPub: ByteArray? = null
    @Volatile private var autoSyncJob: Job? = null

    /**
     * Sprint 3 mailbox push-notify driver. Started on bind(); torn
     * down on onCleared() or re-bind. Holds N child jobs internally —
     * one [MailboxNotifyClient.subscribe] loop per known own mailbox
     * binding (multi-host means N>=1). Each child re-dials with
     * backoff when the subscription drops.
     */
    @Volatile private var mailboxNotifyJob: Job? = null

    fun bind(peer: PublicKey) {
        peerPub = peer.bytes
        // The user is now actively looking at this peer's thread —
        // clear the system notification for them so the badge
        // doesn't stay stale, and start the periodic auto-sync
        // loop that runs while this VM is bound. The loop ends
        // when the VM is cleared (screen leaves the back stack)
        // or when bind() is called with a different peer.
        notifier.clearForPeer(peer.bytes)
        // Suppress notifications for this peer while the user is in
        // the chat — the message lands in the DB and on screen; an
        // OS notification on top is just noise.
        notifier.setActivePeer(peer.bytes)
        startAutoSync()
        startMailboxNotify()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                ownPub = keystore.loadOrCreateIdentityKey().publicKey
                // Flip every fresh inbound from this peer to
                // "viewed" so the next sync round picks them up
                // as read-receipts to push back.
                messageStore.markInboundViewed(peer)
            }
            messageStore.threadFlow(peer)
                .combine(database.contactDao.allFlow()) { messages, contacts ->
                    val row = contacts.firstOrNull { it.peerPub.contentEquals(peer.bytes) }
                    Triple(
                        messages,
                        row?.displayName?.takeIf { it.isNotBlank() },
                        row?.notes?.takeIf { it.isNotBlank() },
                    )
                }
                .collectLatest { (messages, displayName, notes) ->
                    _state.value = UiState.Ready(
                        own = ownPub?.let { PublicKey(it) },
                        peer = peer,
                        displayName = displayName,
                        notes = notes,
                        messages = messages,
                    )
                    // Flip any unviewed inbound to "viewed" only when
                    // there's actually something to flip. The flow
                    // emits on every status change including
                    // outbound-state transitions; gating here keeps
                    // a busy thread from running an O(thread) UPDATE
                    // loop on every message-bubble repaint.
                    val hasUnviewed = messages.any { m ->
                        m.status == MessageStore.STATUS_RECEIVED &&
                            m.fromPub.contentEquals(peer.bytes)
                    }
                    if (hasUnviewed) {
                        withContext(Dispatchers.IO) {
                            messageStore.markInboundViewed(peer)
                        }
                    }
                }
        }
    }

    /**
     * Set or clear the friendly name shown for [peerPub]. A null or
     * blank value drops the contact row entirely so the UI falls
     * back to the raw fingerprint.
     */
    fun renameContact(name: String) {
        val peer = peerPub ?: return
        val trimmed = name.trim()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                val existing = database.contactDao.byPub(peer)
                if (trimmed.isEmpty() && existing?.notes.isNullOrBlank()) {
                    // No display name and no notes — drop the row entirely
                    // so the UI falls back to the raw fingerprint.
                    database.contactDao.clear(peer)
                } else {
                    database.contactDao.upsert(
                        ContactEntity(
                            peerPub = peer,
                            displayName = trimmed.takeIf { it.isNotEmpty() },
                            notes = existing?.notes,
                        ),
                    )
                }
            }
        }
    }

    private fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            // Brief settle before the first attempt — gives the UI
            // a chance to draw and the user a chance to read the
            // existing thread before BLE radio fires up.
            delay(INITIAL_SYNC_DELAY_MS)
            while (isActive) {
                runCatching {
                    syncService.runOnce(timeoutMs = AUTO_SYNC_TIMEOUT_MS)
                }.onFailure { t ->
                    // Re-throw cancellation so structured concurrency works
                    // — eating it here would let the loop swallow shutdown
                    // signals and keep holding the round mutex while the
                    // viewModelScope is supposed to be tearing down.
                    if (t is kotlinx.coroutines.CancellationException) throw t
                }
                delay(AUTO_SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Sprint 3: subscribe to push-notify on every mailbox host the
     * local user has delegated to. When a host writes a Notify, we
     * fire an immediate [MessageSyncService.runOnce] instead of
     * waiting for the next [AUTO_SYNC_INTERVAL_MS] tick — cross-
     * internet message latency drops from ~8s to ~circuit RTT.
     *
     * One child coroutine per binding; each re-dials with backoff
     * when the subscription drops (peer reboots, Tor circuit dies,
     * etc.). The 8s auto-sync stays running as a safety net so a
     * stuck subscription can't strand messages.
     */
    private fun startMailboxNotify() {
        mailboxNotifyJob?.cancel()
        mailboxNotifyJob = viewModelScope.launch {
            // Brief settle — gives Tor a chance to bootstrap on a
            // cold launch before we start hammering its SOCKS port.
            delay(INITIAL_SYNC_DELAY_MS)
            val ownIdentity = withContext(Dispatchers.IO) {
                runCatching { keystore.loadOrCreateIdentityKey() }.getOrNull()
            } ?: return@launch
            val ownPubKey = PublicKey(ownIdentity.publicKey)
            val bindings = withContext(Dispatchers.IO) {
                runCatching { bindingService.myBindings(ownPubKey) }.getOrElse { emptyList() }
            }
            if (bindings.isEmpty()) return@launch
            // Spawn one subscriber loop per binding. Each loop owns
            // its retry/backoff state. Structured-concurrency child
            // of mailboxNotifyJob — cancelling the parent stops all.
            for (binding in bindings) {
                launch { subscribeLoop(binding) }
            }
        }
    }

    private suspend fun subscribeLoop(binding: com.wyspr.feature.messaging.mailbox.MailboxBinding) {
        var backoffMs = NOTIFY_BACKOFF_INITIAL_MS
        // No isActive check needed — delay() throws CancellationException
        // when the parent job cancels, which exits the loop naturally
        // via structured concurrency.
        while (true) {
            val notifies = try {
                mailboxNotifyClient.subscribe(binding) {
                    // Trigger an immediate sync round against whichever
                    // peer is currently active. runOnce is mutex-
                    // serialized so a notify-driven and auto-sync-
                    // driven round can't collide.
                    runCatching { syncService.runOnce(timeoutMs = AUTO_SYNC_TIMEOUT_MS) }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                0
            }
            // If the connection lived long enough to deliver pokes,
            // reset backoff — the host is healthy. A short-lived
            // connection (dial failed or peer disconnected
            // immediately) gets the full backoff to avoid hammering
            // a downed host.
            if (notifies > 0) backoffMs = NOTIFY_BACKOFF_INITIAL_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(NOTIFY_BACKOFF_MAX_MS)
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSyncJob?.cancel()
        mailboxNotifyJob?.cancel()
        // Re-enable notifications for this peer — the user is no
        // longer looking at the thread.
        notifier.setActivePeer(null)
    }

    fun send(body: String) {
        val peer = peerPub ?: return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        // If the user picked a message to reply to, wrap the body in
        // the reply marker. Receivers detect the prefix and render a
        // quote pill above the bubble (degrades to plain text on old
        // clients — the marker is part of the signed body, so wire
        // integrity is preserved either way).
        val replyTarget = _replyingTo.value
        val outgoing = if (replyTarget != null) {
            com.wyspr.feature.messaging.reply.ReplyPayload.encode(
                replyToId = replyTarget.id,
                body = trimmed,
            )
        } else trimmed
        // Clear the draft + reply-target before the suspending DB
        // write so a slow backend can't show stale state in the
        // composer.
        clearDraft()
        cancelReply()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    messageStore.send(toPub = PublicKey(peer), body = outgoing)
                }
            }
        }
    }

    /**
     * Send the user's current location as a tagged-body message. The
     * body is encoded via [com.wyspr.feature.messaging.location.LocationPayload]
     * — the wire format is unchanged, the receiver detects the prefix
     * and renders as a location card.
     */
    fun sendLocation(lat: Double, lng: Double, accuracyMeters: Float) {
        send(
            com.wyspr.feature.messaging.location.LocationPayload.encode(
                lat, lng, accuracyMeters,
            ),
        )
    }

    /**
     * Send a compressed JPEG as a tagged-body message. The screen is
     * responsible for the URI → bytes pipeline ([com.wyspr.feature.messaging.image.ImageCompressor.compress])
     * so this viewmodel stays Context-free. Encoded body is the
     * standard `wyspr:img:<base64>` form.
     */
    fun sendImage(jpegBytes: ByteArray) {
        send(com.wyspr.feature.messaging.image.ImagePayload.encode(jpegBytes))
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val own: PublicKey?,
            val peer: PublicKey,
            /** User-set friendly name for the peer, or null/blank to use fingerprint. */
            val displayName: String?,
            /** User-set free-form notes about this peer (local only). */
            val notes: String?,
            val messages: List<MessageEntity>,
        ) : UiState
    }

    private companion object {
        /** Wait before the first auto-sync so the UI settles. */
        const val INITIAL_SYNC_DELAY_MS = 1_500L
        /** Per-attempt BLE budget — tight enough to retry quickly. */
        const val AUTO_SYNC_TIMEOUT_MS = 12_000L
        /** Idle time between attempts. */
        const val AUTO_SYNC_INTERVAL_MS = 8_000L

        /** First reconnect delay after a mailbox-notify subscription ends. */
        const val NOTIFY_BACKOFF_INITIAL_MS = 3_000L

        /** Ceiling for the exponential backoff between notify-subscribe retries. */
        const val NOTIFY_BACKOFF_MAX_MS = 60_000L
    }
}
