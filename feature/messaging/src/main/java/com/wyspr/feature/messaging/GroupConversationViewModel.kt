package com.wyspr.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.GroupMessageEntity
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PublicKey
import com.wyspr.feature.messaging.groups.GroupMessageEnvelope
import com.wyspr.feature.messaging.groups.GroupStore
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for one group thread. Streams [GroupMessageEntity]
 * rows for the group, exposes the local pubkey so the screen can
 * decide which side of the bubble each message belongs on, and a
 * [send] action that builds + signs a [GroupMessageEnvelope] and
 * stores it for the next sync round.
 */
@HiltViewModel
class GroupConversationViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: WysprDatabase,
    private val groupStore: GroupStore,
    private val syncService: MessageSyncService,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val groupId: GroupId,
            val groupName: String,
            val ownPub: PublicKey?,
            val memberCount: Int,
            val messages: List<GroupMessageEntity>,
            /** Map memberPub → displayName for inbound bubble labelling. */
            val displayNames: Map<com.wyspr.core.identity.PeerKey, String>,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Composer draft and modal state — VM-owned so navigation away
     *  (e.g., to view member list) doesn't drop user input. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** Group message the user picked to reply to, or null. */
    private val _replyingTo = MutableStateFlow<GroupMessageEntity?>(null)
    val replyingTo: StateFlow<GroupMessageEntity?> = _replyingTo.asStateFlow()
    fun pickReply(message: GroupMessageEntity?) { _replyingTo.value = message }
    fun cancelReply() { _replyingTo.value = null }

    private val _memberListOpen = MutableStateFlow(false)
    val memberListOpen: StateFlow<Boolean> = _memberListOpen.asStateFlow()

    private val _renameOpen = MutableStateFlow(false)
    val renameOpen: StateFlow<Boolean> = _renameOpen.asStateFlow()

    fun updateDraft(value: String) { _draft.value = value }
    fun clearDraft() { _draft.value = "" }
    fun openMemberList() { _memberListOpen.value = true }
    fun closeMemberList() { _memberListOpen.value = false }
    fun openRename() { _renameOpen.value = true }
    fun closeRename() { _renameOpen.value = false }

    @Volatile private var ownPub: ByteArray? = null
    @Volatile private var groupId: GroupId? = null
    @Volatile private var autoSyncJob: Job? = null

    fun bind(id: GroupId) {
        groupId = id
        startAutoSync()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                ownPub = keystore.loadOrCreateIdentityKey().publicKey
            }
            val group = withContext(Dispatchers.IO) { groupStore.groupById(id) }
                ?: run {
                    _state.value = UiState.Loading
                    return@launch
                }
            val name = group.localNickname?.takeIf { it.isNotBlank() } ?: group.name
            database.groupMessageDao.threadFlow(id.bytes)
                .collectLatest { messages ->
                    val members = withContext(Dispatchers.IO) {
                        groupStore.activeMembers(id)
                    }
                    val contacts = withContext(Dispatchers.IO) { database.contactDao.all() }
                    val nameByPub: Map<com.wyspr.core.identity.PeerKey, String> = contacts
                        .mapNotNull { c ->
                            c.displayName?.takeIf { it.isNotBlank() }
                                ?.let { com.wyspr.core.identity.PeerKey(c.peerPub) to it }
                        }
                        .toMap()
                    _state.value = UiState.Ready(
                        groupId = id,
                        groupName = name,
                        ownPub = ownPub?.let { PublicKey(it) },
                        memberCount = members.size,
                        messages = messages,
                        displayNames = nameByPub,
                    )
                }
        }
    }

    /**
     * Snapshot of active members for the currently-bound group, paired
     * with each member's friendly contact name (or null). Used by the
     * member-list dialog.
     */
    data class MemberView(
        val pub: PublicKey,
        val displayName: String?,
        val isSelf: Boolean,
        val isCreator: Boolean,
    )

    suspend fun loadMembers(): List<MemberView> {
        val gid = groupId ?: return emptyList()
        val pub = ownPub
        return withContext(Dispatchers.IO) {
            val group = groupStore.groupById(gid) ?: return@withContext emptyList()
            val members = groupStore.activeMembers(gid)
            val contacts = database.contactDao.all()
            val nameByPub: Map<com.wyspr.core.identity.PeerKey, String> = contacts
                .mapNotNull { c ->
                    c.displayName?.takeIf { it.isNotBlank() }
                        ?.let { com.wyspr.core.identity.PeerKey(c.peerPub) to it }
                }
                .toMap()
            members.map { m ->
                MemberView(
                    pub = PublicKey(m.memberPub),
                    displayName = nameByPub[com.wyspr.core.identity.PeerKey(m.memberPub)],
                    isSelf = pub != null && m.memberPub.contentEquals(pub),
                    isCreator = m.memberPub.contentEquals(group.creatorPub),
                )
            }
        }
    }

    /** Local nickname for the group; null/blank to clear. */
    fun renameLocal(nickname: String?) {
        val gid = groupId ?: return
        val trimmed = nickname?.trim()?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                database.groupDao.setNickname(gid.bytes, trimmed)
            }
        }
    }

    /**
     * Send the user's current location as a tagged-body group message.
     * Same encoding scheme as 1:1 — see [com.wyspr.feature.messaging.location.LocationPayload].
     */
    fun sendLocation(lat: Double, lng: Double, accuracyMeters: Float) {
        send(
            com.wyspr.feature.messaging.location.LocationPayload.encode(
                lat, lng, accuracyMeters,
            ),
        )
    }

    /**
     * Send a compressed JPEG as a tagged-body group message. The
     * screen runs the URI → bytes compression so this viewmodel
     * stays Context-free.
     */
    fun sendImage(jpegBytes: ByteArray) {
        send(com.wyspr.feature.messaging.image.ImagePayload.encode(jpegBytes))
    }

    fun send(body: String) {
        val gid = groupId ?: return
        val pub = ownPub ?: return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        val replyTarget = _replyingTo.value
        val outgoing = if (replyTarget != null) {
            com.wyspr.feature.messaging.reply.ReplyPayload.encode(
                replyToId = replyTarget.id,
                body = trimmed,
            )
        } else trimmed
        clearDraft()
        cancelReply()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val env = GroupMessageEnvelope.issue(
                        keystore = keystore,
                        fromPub = PublicKey(pub),
                        groupId = gid,
                        body = outgoing,
                        now = System.currentTimeMillis() / 1000,
                    )
                    database.groupMessageDao.upsert(
                        GroupMessageEntity(
                            id = env.id,
                            groupId = env.groupId.bytes,
                            fromPub = env.fromPub.bytes,
                            body = env.body,
                            createdAt = env.createdAt,
                            receivedAt = null,
                            status = GroupStore.STATUS_PENDING,
                            signature = env.signature,
                        ),
                    )
                }
            }
        }
    }

    private fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
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

    private companion object {
        const val INITIAL_SYNC_DELAY_MS = 1_500L
        const val AUTO_SYNC_TIMEOUT_MS = 12_000L
        const val AUTO_SYNC_INTERVAL_MS = 8_000L
    }
}
