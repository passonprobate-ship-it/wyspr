package com.wyspr.feature.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.currency.AccountId
import com.wyspr.core.currency.GenesisIssuance
import com.wyspr.core.currency.Transfer
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.currency.WalletService
import com.wyspr.core.database.CommunityService
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.Fingerprint
import com.wyspr.core.ui.settings.BiometricSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine for the Gem wallet UI. Wraps [WalletService] in a
 * Compose-friendly StateFlow surface.
 *
 * v0 hardcodes the community ID to all-zero — the community-creation
 * flow lands in a later sprint. Until then, every device runs on the
 * same notional community and the wallet is testable end-to-end on a
 * single device via the [mintDebugGenesis] debug entry.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val wallet: WalletService,
    private val keystore: KeystoreManager,
    private val biometricSettings: BiometricSettings,
    private val communityService: CommunityService,
) : ViewModel() {

    /**
     * The active community for every wallet operation. Loaded lazily
     * on first use and cached for the VM's lifetime; a community
     * switch (or reset) goes through [reloadCommunity] to refresh.
     * Until the first onboarding pass succeeds this falls back to
     * all-zero so the UI doesn't NPE on a fresh install.
     */
    @Volatile
    private var community: ByteArray = ByteArray(32)

    private suspend fun loadCommunity() {
        community = communityService.activeCommunityIdOrNull()?.bytes ?: ByteArray(32)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _sendResult = MutableStateFlow<SendUi?>(null)
    val sendResult: StateFlow<SendUi?> = _sendResult.asStateFlow()

    val biometricGateEnabled: StateFlow<Boolean> = biometricSettings.gateEnabled
    fun setBiometricGate(enabled: Boolean) = biometricSettings.setGateEnabled(enabled)

    val bindIdentityToBiometric: StateFlow<Boolean> = biometricSettings.bindToBiometric
    fun setBindIdentityToBiometric(enabled: Boolean) = biometricSettings.setBindToBiometric(enabled)

    /**
     * Snapshot of the current identity's binding state for the
     * Settings screen. The toggle only affects *future* identities,
     * so we surface what's actually in effect right now.
     */
    val identityIsBound: Boolean get() = keystore.isWrappingKeyBound

    /** Hex of the active community, for display + copy-to-clipboard. */
    val activeCommunityHex: String
        get() = community.joinToString("") { "%02x".format(it) }

    /**
     * Manually switch to a community by pasting its hex. v0 test
     * surface — the real "accept invitation" path uses an
     * InvitationCertificate. Returns true on success, false on bad hex.
     */
    suspend fun switchCommunity(hex: String): Boolean {
        val clean = hex.trim().lowercase().filter { !it.isWhitespace() }
        if (clean.length != CommunityId.LENGTH * 2) return false
        val bytes = runCatching { hexToBytes(clean) }.getOrNull() ?: return false
        communityService.switchCommunity(CommunityId(bytes))
        refresh()
        return true
    }

    init {
        refresh()
    }

    /** Reload the entire UI state from persistence. */
    fun refresh() {
        viewModelScope.launch {
            loadCommunity()
            val me = wallet.me()
            val genesis = wallet.genesisOrNull(community)
            val transfers = wallet.recentTransfers(community, limit = 50)
            _state.value = UiState(
                meAccount = me.account,
                meFingerprint = derivedFingerprint(me.account),
                balance = me.balance,
                nextSeq = me.nextSeq,
                slashed = me.slashed,
                genesisExists = genesis != null,
                genesisAmount = genesis?.totalSupply ?: 0L,
                transfers = transfers,
                loaded = true,
            )
        }
    }

    /**
     * DEBUG: mint a genesis to bootstrap a solo test community. UI must
     * hide this entry once we have a community-founding flow.
     */
    fun mintDebugGenesis(amount: Long, prompt: suspend () -> Boolean = { true }) {
        viewModelScope.launch {
            if (keystore.needsAuth() && !prompt()) {
                _sendResult.value = SendUi.Error("Biometric authentication required.")
                return@launch
            }
            val outcome = wallet.mintDebugGenesis(community, amount)
            _sendResult.value = SendUi.fromOutcome(outcome)
            refresh()
        }
    }

    /**
     * Parse a 64-character hex pubkey and send. Returns user-friendly
     * results via [sendResult]. [prompt] runs when the keystore's auth
     * window has expired — the UI provides it via BiometricUnlocker.
     */
    fun send(
        recipientHex: String,
        amountText: String,
        memoText: String,
        prompt: suspend () -> Boolean = { true },
    ) {
        viewModelScope.launch {
            val cleanHex = recipientHex.trim().lowercase().filter { !it.isWhitespace() }
            val recipientBytes = runCatching { hexToBytes(cleanHex) }.getOrNull()
            if (recipientBytes == null || recipientBytes.size != AccountId.LENGTH) {
                _sendResult.value = SendUi.Error("Recipient must be a 64-character hex public key.")
                return@launch
            }
            val amount = amountText.trim().toLongOrNull()
            if (amount == null || amount < 0) {
                _sendResult.value = SendUi.Error("Amount must be a non-negative integer.")
                return@launch
            }
            if (keystore.needsAuth() && !prompt()) {
                _sendResult.value = SendUi.Error("Biometric authentication required to sign.")
                return@launch
            }
            val outcome = wallet.send(
                community = community,
                recipient = AccountId(recipientBytes),
                amount = amount,
                memoHash = if (memoText.isBlank()) null else hashMemo(memoText),
            )
            _sendResult.value = SendUi.fromOutcome(outcome)
            refresh()
        }
    }

    fun acknowledgeSendResult() { _sendResult.value = null }

    /**
     * Wipe the local identity, wallet, and ledger. Caller MUST confirm
     * destructiveness before invoking this. After it returns, the
     * caller should navigate back to onboarding with the back stack
     * cleared, otherwise screens will hold stale state.
     *
     * Returns true on success, false on any underlying failure (which
     * leaves the wallet in an unspecified state; the user should be
     * told to reinstall).
     */
    suspend fun resetIdentity(): Boolean = try {
        wallet.resetIdentity()
        // Re-snapshot the UI so the post-reset "no genesis, no balance"
        // state is reflected immediately if the user returns.
        _state.value = UiState(loaded = true)
        _sendResult.value = null
        community = ByteArray(32)
        true
    } catch (_: Throwable) {
        false
    }

    /** Recompute balances from the envelope log; surface drift via [AuditUi]. */
    fun runAudit(onResult: (AuditUi) -> Unit) {
        viewModelScope.launch {
            val cached = wallet.me().balance
            val state = wallet.recomputeBalances(community)
            val me = wallet.me()
            val recomputed = state.balanceOf(me.account)
            onResult(AuditUi(cachedBalance = cached, recomputedBalance = recomputed))
            refresh()
        }
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string has odd length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun derivedFingerprint(account: AccountId): String =
        runCatching {
            com.wyspr.core.identity.PublicKey(account.bytes).fingerprint.toString()
        }.getOrDefault("(unknown)")

    private fun hashMemo(text: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(text.encodeToByteArray())
    }

    data class UiState(
        val meAccount: AccountId? = null,
        val meFingerprint: String = "",
        val balance: Long = 0L,
        val nextSeq: Long = 1L,
        val slashed: Boolean = false,
        val genesisExists: Boolean = false,
        val genesisAmount: Long = 0L,
        val transfers: List<Transfer> = emptyList(),
        val loaded: Boolean = false,
    )

    sealed interface SendUi {
        data class Success(val message: String) : SendUi
        data class Error(val message: String) : SendUi

        companion object {
            fun fromOutcome(outcome: WalletService.SendOutcome): SendUi = when (outcome) {
                is WalletService.Sent ->
                    Success("Signed and queued. seq=${outcome.transfer.seq}, amount=${outcome.transfer.amount}.")
                is WalletService.InsufficientFunds ->
                    Error("Insufficient funds: balance ${outcome.balance}, requested ${outcome.requested}.")
                WalletService.SenderSlashed ->
                    Error("Your account is slashed and cannot send.")
                WalletService.SelfTransfer ->
                    Error("Cannot send to yourself.")
                is WalletService.SendRejected ->
                    Error(outcome.reason)
            }
        }
    }

    data class AuditUi(
        val cachedBalance: Long,
        val recomputedBalance: Long,
    ) {
        val drift: Long get() = cachedBalance - recomputedBalance
        val ok: Boolean get() = drift == 0L
    }
}
