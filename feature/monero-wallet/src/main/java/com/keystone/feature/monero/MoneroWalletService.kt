package com.keystone.feature.monero

import android.util.Log
import com.keystone.core.transport.TorBackend
import com.keystone.feature.monero.rpc.MoneroRpcClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level façade for the Monero wallet feature. UI talks here; here
 * talks to the RPC client, the node round-robin, the crypto engine,
 * and the keystore-bound wallet file.
 *
 * v0.7.0a slice only covers the RPC-discoverable parts of the wallet
 * (node connectivity, chain tip, sync status). Balance, history,
 * send, and receive routes land in v0.7.0b once the crypto engine is
 * bundled. See [MoneroCryptoEngine] kdoc and NEXT-STEPS.md §10.
 */
@Singleton
class MoneroWalletService @Inject constructor(
    private val torBackend: TorBackend,
    private val rpc: MoneroRpcClient,
    @Suppress("unused") private val keyStore: MoneroKeyStore,
    @Suppress("unused") private val crypto: MoneroCryptoEngine,
) {

    /** Stable node pool for the v0.7.0a scaffold. */
    private val nodes: List<MoneroNode> = MoneroNodeRegistry.defaults

    /** Round-robin starting index, randomised once per process. */
    @Volatile
    private var nextNodeIndex: Int = nodes.indices.random()

    private val _connection = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    /**
     * Live connection state for the UI. Updated by [refreshNodeInfo]
     * — that's the only mutator. The status carries the chain tip
     * and the node identity so the UI never needs to read RPC state
     * directly.
     */
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /**
     * Refresh the chain-tip view by walking the node list until one
     * responds. Caller is expected to be on a coroutine; this method
     * suspends through [MoneroRpcClient.getInfo].
     *
     * The Tor SOCKS port is sampled at call time — if the embedded
     * Tor daemon hasn't finished bootstrapping yet, sets the status
     * to [ConnectionStatus.WaitingForTor] and returns without
     * touching the network.
     */
    suspend fun refreshNodeInfo() {
        val torSocksPort = torBackend.socksPort.value
        if (torSocksPort == null || torBackend.state.value !is TorBackend.State.Ready) {
            _connection.value = ConnectionStatus.WaitingForTor
            return
        }

        val attemptOrder = nodes.indices.map { (nextNodeIndex + it) % nodes.size }
        nextNodeIndex = (nextNodeIndex + 1) % nodes.size

        for (idx in attemptOrder) {
            val node = nodes[idx]
            try {
                val info = rpc.getInfo(node, torSocksPort)
                _connection.value = ConnectionStatus.Connected(
                    node = node,
                    chainHeight = info.height,
                    targetHeight = info.targetHeight,
                    daemonSynchronized = info.synchronized,
                    outgoingConnections = info.outgoingConnections,
                    nettype = info.nettype,
                    version = info.version,
                )
                return
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Node $node unreachable: ${t.javaClass.simpleName}")
                // Try the next one. We deliberately don't surface
                // per-node errors to the UI — repeated round-robin
                // failures get summarised as AllNodesUnreachable.
            }
        }
        _connection.value = ConnectionStatus.AllNodesUnreachable
    }

    /**
     * Wait for Tor to reach [TorBackend.State.Ready] then do an
     * initial node refresh. Intended to be called once from the
     * Compose entry point.
     */
    suspend fun awaitTorThenRefresh() {
        torBackend.state.first { it is TorBackend.State.Ready }
        refreshNodeInfo()
    }

    /**
     * UI-facing view of the wallet's RPC layer. Real balance /
     * history come from the crypto engine once it's bound.
     */
    sealed interface ConnectionStatus {
        data object Idle : ConnectionStatus
        data object WaitingForTor : ConnectionStatus
        data class Connected(
            val node: MoneroNode,
            val chainHeight: Long,
            val targetHeight: Long,
            val daemonSynchronized: Boolean,
            val outgoingConnections: Int,
            val nettype: String,
            val version: String,
        ) : ConnectionStatus
        data object AllNodesUnreachable : ConnectionStatus
    }

    private companion object {
        const val LOG_TAG = "MoneroWalletService"
    }
}
