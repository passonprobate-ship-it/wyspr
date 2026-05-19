package com.keystone.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.Fingerprint
import com.keystone.core.identity.PublicKey
import com.keystone.core.trust.TrustGraph
import com.keystone.core.trust.TrustGraphService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the conversation-list screen. Combines:
 *   - The trust graph (which peers do I have an edge with?)
 *   - The message store's latest-per-thread feed
 *
 * Surfaces one row per trust edge. Peers I've handshook with but
 * never messaged still appear, with an empty preview that doubles
 * as a "tap to start chatting" affordance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: KeystoneDatabase,
    private val trustGraphService: TrustGraphService,
    private val messageStore: MessageStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun start() {
        viewModelScope.launch {
            val (own, peers) = withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                val ownPub = PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
                ownPub to loadPeers(ownPub)
            }
            messageStore.latestPerThreadFlow().collectLatest { latest ->
                _state.value = project(own, peers, latest)
            }
        }
    }

    private suspend fun loadPeers(ownPub: PublicKey): List<PublicKey> {
        val graph: TrustGraph = trustGraphService.snapshot() ?: return emptyList()
        val edges = graph.snapshot().edges
        val seen = HashSet<List<Byte>>()
        return buildList {
            for (edge in edges) {
                val other = when {
                    edge.from.bytes.contentEquals(ownPub.bytes) -> edge.to
                    edge.to.bytes.contentEquals(ownPub.bytes) -> edge.from
                    else -> continue
                }
                if (seen.add(other.bytes.toList())) add(other)
            }
        }
    }

    private fun project(
        ownPub: PublicKey,
        peers: List<PublicKey>,
        latest: List<MessageEntity>,
    ): UiState {
        val latestByPeer: Map<List<Byte>, MessageEntity> =
            latest.associateBy { it.threadPub.toList() }
        if (peers.isEmpty()) return UiState.NoPeers
        val rows = peers.map { peer ->
            val last = latestByPeer[peer.bytes.toList()]
            ThreadRow(
                peer = peer,
                fingerprint = peer.fingerprint,
                lastBodyPreview = last?.body?.take(BODY_PREVIEW_CHARS),
                lastAt = last?.createdAt,
                lastFromSelf = last?.fromPub?.contentEquals(ownPub.bytes),
            )
        }.sortedByDescending { it.lastAt ?: 0L }
        return UiState.Ready(rows)
    }

    sealed interface UiState {
        data object Loading : UiState
        data object NoPeers : UiState
        data class Ready(val rows: List<ThreadRow>) : UiState
    }

    data class ThreadRow(
        val peer: PublicKey,
        val fingerprint: Fingerprint,
        val lastBodyPreview: String?,
        val lastAt: Long?,
        /** True when the last message was from us, false from them, null when there are no messages. */
        val lastFromSelf: Boolean?,
    )

    private companion object {
        const val BODY_PREVIEW_CHARS = 80
    }
}
