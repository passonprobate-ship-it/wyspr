package com.wyspr.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.ContactEntity
import com.wyspr.core.database.entities.GroupEntity
import com.wyspr.core.database.entities.GroupMessageEntity
import com.wyspr.core.database.entities.MessageEntity
import com.wyspr.core.identity.Fingerprint
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PeerKey
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.identity.asPeerKey
import com.wyspr.core.trust.TrustGraph
import com.wyspr.core.trust.TrustGraphService
import com.wyspr.feature.messaging.sync.MessageSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private val database: WysprDatabase,
    private val trustGraphService: TrustGraphService,
    private val messageStore: MessageStore,
    private val syncService: MessageSyncService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Sync banner: tri-state announcement at the top of the list
     * surfacing the current/last attempt. Null when nothing has
     * happened yet (or after the user dismisses).
     */
    private val _sync = MutableStateFlow<SyncBanner?>(null)
    val sync: StateFlow<SyncBanner?> = _sync.asStateFlow()

    fun syncNow() {
        viewModelScope.launch {
            _sync.value = SyncBanner.Running
            val result = runCatching { syncService.runOnce() }
            _sync.value = result.fold(
                onSuccess = { r ->
                    if (r.errorReason != null) SyncBanner.Failed(r.errorReason)
                    else SyncBanner.Done(
                        pushed = r.pushedMessages,
                        received = r.receivedMessages,
                    )
                },
                onFailure = { t -> SyncBanner.Failed(t.message ?: "Sync failed") },
            )
        }
    }

    fun dismissSyncBanner() { _sync.value = null }

    /** Set or clear the local-only nickname for a group thread. */
    fun renameGroupLocal(groupId: GroupId, nickname: String?) {
        val trimmed = nickname?.trim()?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                database.groupDao.setNickname(groupId.bytes, trimmed)
            }
        }
    }

    /**
     * Soft delete a group locally: drops the group_entity row, which
     * cascades to group_member + group_message. The peer still has
     * the group; if they push another envelope to us later, our
     * `ingestGroup` will reject it (no membership cert locally) so
     * the group stays gone unless we accept a fresh membership cert.
     */
    fun leaveGroupLocal(groupId: GroupId) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                database.groupDao.delete(groupId.bytes)
            }
        }
    }

    sealed interface SyncBanner {
        data object Running : SyncBanner
        data class Done(val pushed: Int, val received: Int) : SyncBanner
        data class Failed(val message: String) : SyncBanner
    }

    fun start() {
        viewModelScope.launch {
            val (own, peers) = withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                val ownPub = PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
                ownPub to loadPeers(ownPub)
            }
            // Compose four reactive feeds:
            //   - latest 1:1 message per peer thread
            //   - latest group message per group
            //   - contact-rename feed (so renames re-render immediately)
            //   - group list (so a freshly-created group appears)
            kotlinx.coroutines.flow.combine(
                messageStore.latestPerThreadFlow(),
                database.groupMessageDao.latestPerGroupFlow(),
                database.contactDao.allFlow(),
                database.groupDao.allFlow(),
            ) { latest1to1, latestGroup, contacts, groups ->
                Quad(latest1to1, latestGroup, contacts, groups)
            }
                // Run `project()` off the main thread — it does Map
                // construction, ByteArray→List<Byte> conversions for
                // hash equality, and sorts across every thread. Four
                // upstream flows emit frequently; doing this on Main
                // showed up as the hottest non-UI path in the
                // ConversationList draw pipeline.
                .flowOn(Dispatchers.Default)
                .collectLatest { (latest1to1, latestGroup, contacts, groups) ->
                    _state.value = project(own, peers, latest1to1, latestGroup, contacts, groups)
                }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private suspend fun loadPeers(ownPub: PublicKey): List<PublicKey> {
        val graph: TrustGraph = trustGraphService.snapshot() ?: return emptyList()
        val edges = graph.snapshot().edges
        val seen = HashSet<PeerKey>()
        return buildList {
            for (edge in edges) {
                val other = when {
                    edge.from.bytes.contentEquals(ownPub.bytes) -> edge.to
                    edge.to.bytes.contentEquals(ownPub.bytes) -> edge.from
                    else -> continue
                }
                if (seen.add(other.bytes.asPeerKey())) add(other)
            }
        }
    }

    private fun project(
        ownPub: PublicKey,
        peers: List<PublicKey>,
        latest: List<MessageEntity>,
        latestGroup: List<GroupMessageEntity>,
        contacts: List<ContactEntity>,
        groups: List<GroupEntity>,
    ): UiState {
        val latestByPeer: Map<PeerKey, MessageEntity> =
            latest.associateBy { it.threadPub.asPeerKey() }
        val nameByPeer: Map<PeerKey, String> = contacts
            .mapNotNull { c -> c.displayName?.takeIf { it.isNotBlank() }?.let { c.peerPub.asPeerKey() to it } }
            .toMap()
        val threadRows = peers.map { peer ->
            val key = peer.bytes.asPeerKey()
            val last = latestByPeer[key]
            ThreadRow(
                peer = peer,
                fingerprint = peer.fingerprint,
                displayName = nameByPeer[key],
                lastBodyPreview = last?.body?.let { formatListPreview(it) },
                lastAt = last?.createdAt,
                lastFromSelf = last?.fromPub?.contentEquals(ownPub.bytes),
            )
        }.sortedByDescending { it.lastAt ?: 0L }

        val latestByGroup: Map<PeerKey, GroupMessageEntity> =
            latestGroup.associateBy { it.groupId.asPeerKey() }
        val groupRows = groups.map { g ->
            val last = latestByGroup[g.groupId.asPeerKey()]
            GroupRow(
                groupId = GroupId(g.groupId),
                name = g.localNickname?.takeIf { it.isNotBlank() } ?: g.name,
                lastBodyPreview = last?.body?.let { formatListPreview(it) },
                lastAt = last?.createdAt,
                lastFromSelf = last?.fromPub?.contentEquals(ownPub.bytes),
            )
        }.sortedByDescending { it.lastAt ?: 0L }

        if (peers.isEmpty() && groupRows.isEmpty()) return UiState.NoPeers
        return UiState.Ready(threadRows, groupRows)
    }

    sealed interface UiState {
        data object Loading : UiState
        data object NoPeers : UiState
        data class Ready(
            val rows: List<ThreadRow>,
            val groupRows: List<GroupRow> = emptyList(),
        ) : UiState
    }

    data class ThreadRow(
        val peer: PublicKey,
        val fingerprint: Fingerprint,
        val displayName: String?,
        val lastBodyPreview: String?,
        val lastAt: Long?,
        val lastFromSelf: Boolean?,
    )

    data class GroupRow(
        val groupId: GroupId,
        val name: String,
        val lastBodyPreview: String?,
        val lastAt: Long?,
        val lastFromSelf: Boolean?,
    )

    private fun formatListPreview(body: String): String {
        if (body.startsWith("wyspr:loc:")) return "📍 Location"
        if (body.startsWith("wyspr:img:")) return "📷 Photo"
        if (body.startsWith("wyspr:audio:")) return "🎙 Voice note"
        if (body.startsWith("wyspr:react:")) {
            val decoded = com.wyspr.feature.messaging.reactions.ReactionPayload.decode(body)
            return if (decoded != null && !decoded.isRetract) "Reacted ${decoded.emoji}" else "Removed a reaction"
        }
        if (body.startsWith("wyspr:reply:")) {
            val inner = com.wyspr.feature.messaging.reply.ReplyPayload.decode(body)?.body
            return inner?.take(BODY_PREVIEW_CHARS) ?: body.take(BODY_PREVIEW_CHARS)
        }
        return body.take(BODY_PREVIEW_CHARS)
    }

    private companion object {
        const val BODY_PREVIEW_CHARS = 80
    }
}
