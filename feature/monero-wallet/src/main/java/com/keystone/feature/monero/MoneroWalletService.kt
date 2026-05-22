package com.keystone.feature.monero

import android.content.Context
import android.util.Log
import com.keystone.feature.monero.persistence.EncryptedWalletDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import im.molly.monero.sdk.MoneroAmount
import im.molly.monero.sdk.MoneroNetwork
import im.molly.monero.sdk.MoneroNodeClient
import im.molly.monero.sdk.MoneroWallet
import im.molly.monero.sdk.RemoteNode
import im.molly.monero.sdk.WalletProvider
import im.molly.monero.sdk.service.InProcessWalletService
import im.molly.monero.sdk.singleNodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level Monero wallet facade. UI talks here; here talks to
 * mollyim's [WalletProvider] (obtained from
 * [InProcessWalletService.Companion.connect]) and surfaces the
 * wallet's state via [walletState] as a single StateFlow for
 * Compose consumption.
 *
 * Lifecycle:
 *
 *  - First [bootstrap] call connects to mollyim's
 *    InProcessWalletService and either opens an existing wallet
 *    (if the keystore-wrapped file already exists on disk) or
 *    creates a brand-new one.
 *  - The opened [MoneroWallet]'s `ledger()` Flow drives
 *    [walletState] updates (balance, address, sync height).
 *
 * Threat model: every network call mollyim makes goes through
 * [com.keystone.feature.monero.network.TorSocksOkHttp]'s OkHttp
 * client. The wallet engine itself runs in-process for v1.
 * Switching to [im.molly.monero.sdk.service.SandboxedWalletService]
 * is a future hardening sprint (requires manifest isolated-process
 * plumbing).
 *
 * Pre-W1 v0.7.0a scaffolding: this used a custom Tor RPC client +
 * round-robin over a hand-curated node list. mollyim makes that
 * scaffolding redundant — `MoneroNodeClient` now handles node
 * communication. The existing [MoneroNodeRegistry] still provides
 * the default node URL the user dials by default.
 */
@Singleton
class MoneroWalletService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: EncryptedWalletDataStore,
    private val httpClient: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var provider: WalletProvider? = null
    @Volatile private var wallet: MoneroWallet? = null
    @Volatile private var nodeClient: MoneroNodeClient? = null

    /**
     * Currently selected Monero node. Defaults to the first entry in
     * [MoneroNodeRegistry.defaults]. Future polish sprint will let
     * the user pick or supply a custom node URL.
     */
    private val currentNode: MoneroNode = MoneroNodeRegistry.defaults.first()

    private val _walletState = MutableStateFlow<WalletState>(WalletState.Idle)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    /**
     * Bootstrap the wallet. Idempotent — repeated calls return
     * immediately if a wallet is already open. The first call
     * connects to mollyim's wallet service, then either restores
     * from disk (if a wallet file exists) or creates a brand-new
     * one with a random seed.
     */
    suspend fun bootstrap() = mutex.withLock {
        if (wallet != null) return@withLock
        try {
            _walletState.value = WalletState.Binding
            val prov = InProcessWalletService.connect(context)
            provider = prov

            val remote = RemoteNode(
                url = "http://${currentNode.host}:${currentNode.port}",
                network = MoneroNetwork.Mainnet,
                username = "",
                password = "",
            )
            val client = remote.singleNodeClient(httpClient = httpClient)
            nodeClient = client

            val w = if (dataStore.exists()) {
                Log.i(TAG, "bootstrap: opening existing wallet from disk")
                prov.openWallet(MoneroNetwork.Mainnet, dataStore, client)
            } else {
                Log.i(TAG, "bootstrap: creating brand-new wallet (no file on disk yet)")
                val w = prov.createNewWallet(MoneroNetwork.Mainnet, dataStore, client)
                w.save()  // persist seed immediately
                w
            }
            wallet = w
            startLedgerCollector(w)
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap failed: ${t::class.simpleName}: ${t.message}", t)
            _walletState.value = WalletState.Failed(t.message ?: "bootstrap failed")
        }
    }

    /**
     * Free up the wallet binding. Called from process shutdown;
     * idempotent.
     */
    fun shutdown() {
        runCatching { wallet?.close() }
        wallet = null
        runCatching { nodeClient?.close() }
        nodeClient = null
        runCatching { provider?.close() }
        provider = null
        scope.cancel()
    }

    private fun startLedgerCollector(w: MoneroWallet) {
        scope.launch {
            try {
                val primaryAddress = w.publicAddress.address
                _walletState.value = WalletState.Ready(
                    primaryAddress = primaryAddress,
                    balanceAtomicUnits = 0L,
                    confirmedAtomicUnits = 0L,
                    pendingAtomicUnits = 0L,
                    txCount = 0,
                )
                w.ledger().collectLatest { ledger ->
                    val balance = ledger.getBalance()
                    _walletState.value = WalletState.Ready(
                        primaryAddress = primaryAddress,
                        balanceAtomicUnits = balance.totalAmount.atomicUnits,
                        confirmedAtomicUnits = balance.confirmedAmount.atomicUnits,
                        pendingAtomicUnits = balance.pendingAmount.atomicUnits,
                        txCount = ledger.transactions.size,
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ledger collector ended: ${t::class.simpleName}: ${t.message}")
                _walletState.value = WalletState.Failed(t.message ?: "ledger collector failed")
            }
        }
    }

    /**
     * Single-source-of-truth view of the wallet. UI subscribes to
     * one StateFlow and renders the appropriate state.
     */
    sealed interface WalletState {
        /** Service not connected yet — nothing has happened. */
        data object Idle : WalletState
        /** Currently connecting to mollyim's wallet service. */
        data object Binding : WalletState
        /** Wallet open and ready. balance / address / counts surfaced. */
        data class Ready(
            val primaryAddress: String,
            val balanceAtomicUnits: Long,
            val confirmedAtomicUnits: Long,
            val pendingAtomicUnits: Long,
            val txCount: Int,
        ) : WalletState
        /** Connect, open, or ledger collect failed. UI shows error + retry. */
        data class Failed(val message: String) : WalletState
    }

    private companion object {
        const val TAG = "MoneroWalletService"
    }
}

/** Format atomic units (piconero) as a human-readable XMR decimal string. */
fun Long.atomicUnitsAsXmr(): String =
    java.math.BigDecimal(this)
        .divide(java.math.BigDecimal(1_000_000_000_000L))
        .toPlainString()
