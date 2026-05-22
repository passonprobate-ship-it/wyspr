package com.keystone.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.ContactEntity
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.MessagingNotifier
import com.keystone.feature.messaging.sync.MessageSyncService
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
    private val database: KeystoneDatabase,
    private val messageStore: MessageStore,
    private val syncService: MessageSyncService,
    private val notifier: MessagingNotifier,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Composer draft — lives in the VM so typing survives navigation
     *  away (e.g., tap-to-rename) and configuration changes. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** Modal state — title-bar tap shows the peer-detail sheet, rename
     *  shows the rename dialog. Both moved off Composable-local
     *  `remember` so they survive screen rotation. */
    private val _peerDetailsOpen = MutableStateFlow(false)
    val peerDetailsOpen: StateFlow<Boolean> = _peerDetailsOpen.asStateFlow()

    private val _renameOpen = MutableStateFlow(false)
    val renameOpen: StateFlow<Boolean> = _renameOpen.asStateFlow()

    fun updateDraft(value: String) { _draft.value = value }
    fun clearDraft() { _draft.value = "" }
    fun openPeerDetails() { _peerDetailsOpen.value = true }
    fun closePeerDetails() { _peerDetailsOpen.value = false }
    fun openRename() {
        _peerDetailsOpen.value = false
        _renameOpen.value = true
    }
    fun closeRename() { _renameOpen.value = false }

    @Volatile private var ownPub: ByteArray? = null
    @Volatile private var peerPub: ByteArray? = null
    @Volatile private var autoSyncJob: Job? = null

    fun bind(peer: PublicKey) {
        peerPub = peer.bytes
        // The user is now actively looking at this peer's thread —
        // clear the system notification for them so the badge
        // doesn't stay stale, and start the periodic auto-sync
        // loop that runs while this VM is bound. The loop ends
        // when the VM is cleared (screen leaves the back stack)
        // or when bind() is called with a different peer.
        notifier.clearForPeer(peer.bytes)
        startAutoSync()

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
                    val name = contacts
                        .firstOrNull { it.peerPub.contentEquals(peer.bytes) }
                        ?.displayName
                        ?.takeIf { it.isNotBlank() }
                    Triple(messages, name, Unit)
                }
                .collectLatest { (messages, displayName, _) ->
                    _state.value = UiState.Ready(
                        own = ownPub?.let { PublicKey(it) },
                        peer = peer,
                        displayName = displayName,
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
                if (trimmed.isEmpty()) {
                    database.contactDao.clear(peer)
                } else {
                    database.contactDao.upsert(
                        ContactEntity(peerPub = peer, displayName = trimmed),
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

    override fun onCleared() {
        super.onCleared()
        autoSyncJob?.cancel()
    }

    fun send(body: String) {
        val peer = peerPub ?: return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        // Clear the draft before the suspending DB write so a slow
        // backend can't show stale text in the composer.
        clearDraft()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    messageStore.send(toPub = PublicKey(peer), body = trimmed)
                }
            }
        }
    }

    /**
     * Send the user's current location as a tagged-body message. The
     * body is encoded via [com.keystone.feature.messaging.location.LocationPayload]
     * — the wire format is unchanged, the receiver detects the prefix
     * and renders as a location card.
     */
    fun sendLocation(lat: Double, lng: Double, accuracyMeters: Float) {
        send(
            com.keystone.feature.messaging.location.LocationPayload.encode(
                lat, lng, accuracyMeters,
            ),
        )
    }

    /**
     * Send a compressed JPEG as a tagged-body message. The screen is
     * responsible for the URI → bytes pipeline ([com.keystone.feature.messaging.image.ImageCompressor.compress])
     * so this viewmodel stays Context-free. Encoded body is the
     * standard `keystone:img:<base64>` form.
     */
    fun sendImage(jpegBytes: ByteArray) {
        send(com.keystone.feature.messaging.image.ImagePayload.encode(jpegBytes))
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val own: PublicKey?,
            val peer: PublicKey,
            /** User-set friendly name for the peer, or null/blank to use fingerprint. */
            val displayName: String?,
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
    }
}
