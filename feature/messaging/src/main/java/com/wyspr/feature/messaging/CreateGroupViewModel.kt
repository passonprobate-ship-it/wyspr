package com.wyspr.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.ContactEntity
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.trust.TrustGraphService
import com.wyspr.feature.messaging.groups.GroupCreationService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the create-group screen. Loads paired peers from the trust
 * graph, lets the user pick which to include + a group name, and
 * hands off to [GroupCreationService] on confirm.
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val database: WysprDatabase,
    private val keystore: KeystoreManager,
    private val trustGraphService: TrustGraphService,
    private val creationService: GroupCreationService,
) : ViewModel() {

    data class PeerOption(
        val peer: PublicKey,
        val displayName: String?,
        val fingerprint: String,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val peers: List<PeerOption>) : UiState
        data object NoPeers : UiState
    }

    sealed interface CreateResult {
        data class Ok(val groupId: GroupId) : CreateResult
        data class Failed(val message: String) : CreateResult
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<CreateResult?>(null)
    val result: StateFlow<CreateResult?> = _result.asStateFlow()

    fun start() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                val ownPub = PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
                val graph = trustGraphService.snapshot() ?: return@withContext emptyList()
                val edges = graph.snapshot().edges
                val seen = HashSet<List<Byte>>()
                val peers = ArrayList<PublicKey>()
                for (edge in edges) {
                    val other = when {
                        edge.from.bytes.contentEquals(ownPub.bytes) -> edge.to
                        edge.to.bytes.contentEquals(ownPub.bytes) -> edge.from
                        else -> continue
                    }
                    if (seen.add(other.bytes.toList())) peers.add(other)
                }
                val contacts: List<ContactEntity> = database.contactDao.all()
                val byPub = contacts.associateBy { it.peerPub.toList() }
                peers.map { p ->
                    PeerOption(
                        peer = p,
                        displayName = byPub[p.bytes.toList()]?.displayName?.takeIf { it.isNotBlank() },
                        fingerprint = p.fingerprint.toString(),
                    )
                }
            }
            _state.value = if (rows.isEmpty()) UiState.NoPeers else UiState.Ready(rows)
        }
    }

    fun create(name: String, selected: List<PublicKey>) {
        if (selected.isEmpty() || name.isBlank()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    creationService.createGroup(
                        name = name,
                        otherMembers = selected,
                        nowSeconds = System.currentTimeMillis() / 1000,
                    )
                }
            }
            _result.value = outcome.fold(
                onSuccess = { CreateResult.Ok(it) },
                onFailure = { CreateResult.Failed(it.message ?: "Create failed") },
            )
        }
    }

    fun consumeResult() { _result.value = null }
}
