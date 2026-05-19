package com.keystone.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.PublicKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var ownPub: ByteArray? = null
    @Volatile private var peerPub: ByteArray? = null

    fun bind(peer: PublicKey) {
        peerPub = peer.bytes
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
}
