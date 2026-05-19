package com.keystone.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
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
            }
            messageStore.threadFlow(peer).collectLatest { messages ->
                _state.value = UiState.Ready(
                    own = ownPub?.let { PublicKey(it) },
                    peer = peer,
                    messages = messages,
                )
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
                runCatching { syncService.runOnce(timeoutMs = AUTO_SYNC_TIMEOUT_MS) }
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    messageStore.send(toPub = PublicKey(peer), body = trimmed)
                }
            }
        }
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val own: PublicKey?,
            val peer: PublicKey,
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
