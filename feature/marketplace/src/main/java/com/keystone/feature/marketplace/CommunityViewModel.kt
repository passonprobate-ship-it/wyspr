package com.keystone.feature.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.Fingerprint
import com.keystone.core.identity.PublicKey
import com.keystone.core.trust.TrustGraph
import com.keystone.core.trust.TrustGraphService
import com.keystone.core.trust.TrustLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads + projects the local view of the community for the
 * "My community" screen: who am I, what community I belong to,
 * who have I paired with, and what trust level the local graph
 * assigns each of them.
 *
 * Everything here is read-only; mutation is the handshake flow's
 * job. The screen surfaces the data the handshake produced.
 */
@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: KeystoneDatabase,
    private val trustGraphService: TrustGraphService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Idempotent — called from a LaunchedEffect when the screen mounts. */
    fun load() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
            _state.value = snapshot
        }
    }

    private suspend fun buildSnapshot(): UiState {
        if (!database.isOpen) database.open()
        val membership = database.communityMembershipDao.firstOrNull()
            ?: return UiState.NoCommunity
        val identity = runCatching { keystore.loadOrCreateIdentityKey() }
            .getOrNull() ?: return UiState.NoCommunity
        val ownPub = PublicKey(identity.publicKey)
        val graph = trustGraphService.snapshot()

        val edges = graph?.snapshot()?.edges.orEmpty()
        val peers = buildList {
            val seen = HashSet<List<Byte>>()
            for (edge in edges) {
                // For each edge we project the *other* endpoint as a
                // peer entry. An edge can name us as `from` or `to`.
                val other = when {
                    edge.from.bytes.contentEquals(ownPub.bytes) -> edge.to
                    edge.to.bytes.contentEquals(ownPub.bytes) -> edge.from
                    else -> continue
                }
                val key = other.bytes.toList()
                if (!seen.add(key)) continue
                val level = graph?.trustLevel(other) ?: TrustLevel.Unknown
                add(
                    PeerEntry(
                        publicKey = other,
                        fingerprint = other.fingerprint,
                        trustLevel = level,
                        pairedAt = edge.establishedAt,
                    ),
                )
            }
        }.sortedByDescending { it.pairedAt }

        return UiState.Ready(
            ownFingerprint = ownPub.fingerprint,
            ownTrustLevel = if (membership.isFounder) TrustLevel.Root else (graph?.trustLevel(ownPub) ?: TrustLevel.Unknown),
            communityIdHex = membership.communityId.toHex(),
            isFounder = membership.isFounder,
            foundedAt = membership.foundedAt,
            peers = peers,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    data class PeerEntry(
        val publicKey: PublicKey,
        val fingerprint: Fingerprint,
        val trustLevel: TrustLevel,
        val pairedAt: Long,
    )

    sealed interface UiState {
        data object Loading : UiState
        data object NoCommunity : UiState
        data class Ready(
            val ownFingerprint: Fingerprint,
            val ownTrustLevel: TrustLevel,
            val communityIdHex: String,
            val isFounder: Boolean,
            val foundedAt: Long,
            val peers: List<PeerEntry>,
        ) : UiState
    }
}
