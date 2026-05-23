package com.keystone.feature.monero

import android.content.Context
import android.util.Log
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.ForegroundClaim
import com.keystone.feature.monero.persistence.EncryptedWalletDataStore
import com.keystone.feature.monero.persistence.SeedStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import im.molly.monero.sdk.FeePriority
import im.molly.monero.sdk.MoneroAmount
import im.molly.monero.sdk.MoneroNetwork
import im.molly.monero.sdk.MoneroNodeClient
import im.molly.monero.sdk.MoneroWallet
import im.molly.monero.sdk.PaymentDetail
import im.molly.monero.sdk.PaymentRequest
import im.molly.monero.sdk.PublicAddress
import im.molly.monero.sdk.RemoteNode
import im.molly.monero.sdk.RestorePoint
import im.molly.monero.sdk.SecretKey
import im.molly.monero.sdk.WalletProvider
import im.molly.monero.sdk.randomSecretKey
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
    private val seedStorage: SeedStorage,
    private val httpClient: OkHttpClient,
    private val paymentAddressService: PaymentAddressService,
    private val foregroundClaim: ForegroundClaim,
) {

    /**
     * Tracks whether we've already claimed the foreground slot. The
     * claim is reference-counted in the underlying service so a
     * double-start is harmless, but we still want a single
     * release path that fires regardless of how the wallet came
     * down (shutdown, restore-replace, error during ledger
     * collection).
     */
    @Volatile private var foregroundHeld: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var provider: WalletProvider? = null
    @Volatile internal var wallet: MoneroWallet? = null
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
     * immediately if a wallet is already open.
     *
     * First-call paths:
     *  - Wallet file already on disk → reopen via [WalletProvider.openWallet].
     *  - No wallet file:
     *      - Seed file present (came from a previous fresh-create
     *        or a [restoreFromSeed] that didn't finish saving) →
     *        restoreWallet with that seed.
     *      - Nothing on disk → mint a fresh 32-byte random seed,
     *        persist it via [SeedStorage], call restoreWallet with
     *        it (RestorePoint = now). Going through restoreWallet
     *        for "create" lets us keep the seed bytes for the
     *        reveal flow — `createNewWallet` mints internally and
     *        never gives them back.
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

            val w = when {
                dataStore.exists() -> {
                    Log.i(TAG, "bootstrap: opening existing wallet from disk")
                    prov.openWallet(MoneroNetwork.Mainnet, dataStore, client)
                }
                else -> {
                    val seed = seedStorage.read() ?: mintAndPersistFreshSeed()
                    Log.i(TAG, "bootstrap: restoring wallet from on-disk seed")
                    SecretKey(seed.copyOf()).use { sk ->
                        prov.restoreWallet(
                            MoneroNetwork.Mainnet,
                            dataStore,
                            client,
                            sk,
                            RestorePoint.creationTime(java.time.Instant.now()),
                        )
                    }.also { it.save() }
                }
            }
            wallet = w
            claimForegroundIfNeeded()
            startLedgerCollector(w)
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap failed: ${t::class.simpleName}: ${t.message}", t)
            _walletState.value = WalletState.Failed(t.message ?: "bootstrap failed")
            releaseForegroundIfHeld()
        }
    }

    /**
     * Restore a wallet from a user-supplied 32-byte seed plus
     * optional creation-time / block-height restore point. Replaces
     * any existing wallet file. Caller is responsible for warning
     * the user that this destroys the current wallet.
     *
     * The 32 bytes come from one of two paths in the UI:
     *  - User pasted 64 hex characters (the "spend secret").
     *  - User entered 25 Electrum-style words, decoded via
     *    [im.molly.monero.sdk.mnemonics.MoneroMnemonic.recoverEntropy].
     *
     * Both produce the same byte array; this function doesn't know
     * which path was taken.
     */
    suspend fun restoreFromSeed(
        seed: ByteArray,
        restorePoint: RestorePoint = RestorePoint.Genesis,
    ): RestoreResult = mutex.withLock {
        require(seed.size == SeedStorage.SEED_LENGTH) {
            "expected ${SeedStorage.SEED_LENGTH}-byte seed, got ${seed.size}"
        }
        try {
            // Tear down any open wallet first — the on-disk blob is
            // about to be replaced.
            runCatching { wallet?.close() }
            wallet = null

            // Replace on-disk state. delete() is fine if no file yet.
            dataStore.delete()
            seedStorage.write(seed)

            // Connect (re-using existing provider if alive) and
            // restoreWallet with the user's seed.
            val prov = provider ?: InProcessWalletService.connect(context).also { provider = it }
            val remote = RemoteNode(
                url = "http://${currentNode.host}:${currentNode.port}",
                network = MoneroNetwork.Mainnet,
                username = "",
                password = "",
            )
            val client = nodeClient ?: remote.singleNodeClient(httpClient = httpClient)
                .also { nodeClient = it }

            _walletState.value = WalletState.Binding
            val w = SecretKey(seed.copyOf()).use { sk ->
                prov.restoreWallet(
                    MoneroNetwork.Mainnet,
                    dataStore,
                    client,
                    sk,
                    restorePoint,
                )
            }
            w.save()
            wallet = w
            claimForegroundIfNeeded()
            startLedgerCollector(w)
            RestoreResult.Ok
        } catch (t: Throwable) {
            Log.w(TAG, "restoreFromSeed failed: ${t::class.simpleName}: ${t.message}", t)
            _walletState.value = WalletState.Failed(t.message ?: "restore failed")
            releaseForegroundIfHeld()
            RestoreResult.Error(t.message ?: t::class.simpleName ?: "restore failed")
        }
    }

    private fun claimForegroundIfNeeded() {
        if (foregroundHeld) return
        foregroundClaim.start(ForegroundClaim.Reason.WalletSync)
        foregroundHeld = true
    }

    private fun releaseForegroundIfHeld() {
        if (!foregroundHeld) return
        foregroundClaim.stop(ForegroundClaim.Reason.WalletSync)
        foregroundHeld = false
    }

    /**
     * Read the wallet's 32-byte spend secret. The reveal-seed UI
     * calls this after biometric auth, then renders the bytes as
     * the 25-word Electrum-style mnemonic via [MoneroMnemonic].
     * Returns null when no seed has been written yet (very early
     * cold start, before bootstrap).
     */
    fun readSeedBytes(): ByteArray? = seedStorage.read()

    private fun mintAndPersistFreshSeed(): ByteArray {
        // randomSecretKey() returns a cryptographically random
        // 32-byte SecretKey wrapping a libsodium-style buffer. Copy
        // it out, persist the bytes, destroy the SecretKey so the
        // buffer in the Closeable wrapper gets zeroed when the
        // suspend frame returns.
        val sk = randomSecretKey()
        val bytes = sk.use { it.bytes.copyOf() }
        seedStorage.write(bytes)
        return bytes
    }

    /**
     * Send XMR to a paired peer. Resolves the destination via
     * [PaymentAddressService.currentForPeer] — the user never types
     * an address string. Builds a [PaymentRequest], hands it to
     * mollyim's `createTransfer`, then commits the resulting
     * [im.molly.monero.sdk.PendingTransfer].
     *
     * Returns a [SendResult] sealed type so the UI can render the
     * specific failure (no address bound, wallet not ready,
     * insufficient funds, broadcast failed).
     *
     * No biometric gate here — the UI layer wraps the call with the
     * existing `BiometricGate` from `:core:ui`. This service trusts
     * its caller already authenticated the user.
     */
    suspend fun sendTo(
        peerPub: PublicKey,
        amountAtomicUnits: Long,
        feePriority: FeePriority = FeePriority.Medium,
    ): SendResult {
        val w = wallet ?: return SendResult.WalletNotReady
        val addressStr = paymentAddressService
            .currentForPeer(peerPub, PaymentAddressService.CHAIN_MONERO)
            ?: return SendResult.NoAddressBound
        return try {
            val dest = PublicAddress.parse(addressStr)
            val detail = PaymentDetail(MoneroAmount(amountAtomicUnits), dest)
            val request = PaymentRequest(
                paymentDetails = listOf(detail),
                spendingAccountIndex = 0,
                feePriority = feePriority,
            )
            w.createTransfer(request).use { pending ->
                val ok = pending.commit()
                if (ok) SendResult.Sent(
                    amountAtomicUnits = pending.amount.atomicUnits,
                    feeAtomicUnits = pending.fee.atomicUnits,
                ) else SendResult.BroadcastFailed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sendTo failed: ${t::class.simpleName}: ${t.message}", t)
            SendResult.Error(t.message ?: t::class.simpleName ?: "send failed")
        }
    }

    /**
     * Free up the wallet binding. Called from process shutdown;
     * idempotent.
     */
    fun shutdown() {
        releaseForegroundIfHeld()
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
                    transactions = emptyList(),
                )
                w.ledger().collectLatest { ledger ->
                    val balance = ledger.getBalance()
                    _walletState.value = WalletState.Ready(
                        primaryAddress = primaryAddress,
                        balanceAtomicUnits = balance.totalAmount.atomicUnits,
                        confirmedAtomicUnits = balance.confirmedAmount.atomicUnits,
                        pendingAtomicUnits = balance.pendingAmount.atomicUnits,
                        txCount = ledger.transactions.size,
                        transactions = buildHistory(ledger.transactions),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ledger collector ended: ${t::class.simpleName}: ${t.message}")
                _walletState.value = WalletState.Failed(t.message ?: "ledger collector failed")
                releaseForegroundIfHeld()
            }
        }
    }

    /**
     * Convert mollyim's per-tx records into [TxHistoryEntry]. Sorted
     * newest first by block timestamp (unconfirmed txs float to the
     * top with `Instant.MAX`).
     *
     * Amount sign convention: positive when net inbound (received >
     * sent for this device), negative when net outbound. Outbound
     * "amount" already excludes change in mollyim's accounting; we
     * surface the fee separately so the UI can render fee vs.
     * paid-to-recipient distinctly.
     */
    private fun buildHistory(
        txs: Collection<im.molly.monero.sdk.Transaction>,
    ): List<TxHistoryEntry> {
        return txs
            .map { tx ->
                // mollyim's accounting: `sent` is the set of enotes
                // we burned in this tx, `received` is the set we
                // gained. An outbound spend has sent.isNotEmpty()
                // (even though received may carry change). An
                // inbound deposit has sent.isEmpty().
                val isOutbound = tx.sent.isNotEmpty()
                val absAmount = kotlin.math.abs(tx.amount.atomicUnits)
                TxHistoryEntry(
                    txHash = tx.txId,
                    amountAtomicUnits = if (isOutbound) -absAmount else absAmount,
                    feeAtomicUnits = if (isOutbound) tx.fee.atomicUnits else 0L,
                    blockHeight = tx.blockHeight,
                    blockTimestamp = tx.blockTimestamp,
                )
            }
            .sortedByDescending { it.blockTimestamp ?: java.time.Instant.MAX }
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
        /** Wallet open and ready. balance / address / counts / tx history surfaced. */
        data class Ready(
            val primaryAddress: String,
            val balanceAtomicUnits: Long,
            val confirmedAtomicUnits: Long,
            val pendingAtomicUnits: Long,
            val txCount: Int,
            /**
             * Sprint W4: transaction history. Sorted newest-first.
             * Empty before the first ledger snapshot; populated on
             * every subsequent ledger update. Includes both inbound
             * and outbound; sign of [TxHistoryEntry.amountAtomicUnits]
             * disambiguates.
             */
            val transactions: List<TxHistoryEntry> = emptyList(),
        ) : WalletState
        /** Connect, open, or ledger collect failed. UI shows error + retry. */
        data class Failed(val message: String) : WalletState
    }

    /**
     * One row in the transaction history. Derived from
     * [im.molly.monero.sdk.Transaction] but flattened so the UI
     * never imports the SDK type.
     *
     * Sign convention: [amountAtomicUnits] is positive when this
     * device received the value (inbound), negative when sent.
     * Outbound entries also expose [feeAtomicUnits] separately so
     * the UI can render "sent 0.1 XMR + 0.0002 fee" if desired.
     */
    data class TxHistoryEntry(
        /** Monero transaction hash (the on-chain id). */
        val txHash: String,
        /** Positive = inbound, negative = outbound. Includes change-back. */
        val amountAtomicUnits: Long,
        /** Outbound only — fee paid by us. Zero for inbound. */
        val feeAtomicUnits: Long,
        /** Block height the tx confirmed in; null = still in the mempool. */
        val blockHeight: Int?,
        /** Wall-clock time of the confirming block; null when unconfirmed. */
        val blockTimestamp: java.time.Instant?,
    ) {
        val isInbound: Boolean get() = amountAtomicUnits >= 0L
        val isUnconfirmed: Boolean get() = blockHeight == null
    }

    /**
     * Outcome of [restoreFromSeed]. UI renders the error variant
     * with the bubbled-up message; success simply triggers a
     * re-render driven by the new [walletState] flow value.
     */
    sealed interface RestoreResult {
        data object Ok : RestoreResult
        data class Error(val message: String) : RestoreResult
    }

    /**
     * Outcome of [sendTo]. UI renders each variant differently — a
     * success goes to a confirmation screen, the others to specific
     * error states.
     */
    sealed interface SendResult {
        /** Tx built and broadcast. Returns the amount + fee we paid. */
        data class Sent(val amountAtomicUnits: Long, val feeAtomicUnits: Long) : SendResult
        /** Wallet hasn't finished bootstrapping yet. */
        data object WalletNotReady : SendResult
        /** No address is bound for this peer — UI prompts to set one. */
        data object NoAddressBound : SendResult
        /** Tx built locally but daemon refused to broadcast. */
        data object BroadcastFailed : SendResult
        /** Any other failure — invalid address, insufficient funds, …. */
        data class Error(val message: String) : SendResult
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
