package com.wyspr.feature.messaging.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.identity.PublicKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "Use a mailbox" screen.
 *
 * Surfaces the local user's current [MailboxBinding] (which mailbox
 * is serving them) and a one-shot action to apply a freshly-scanned
 * mailbox QR.
 *
 * Actual pull-and-ingest happens in [com.wyspr.feature.messaging.sync.MessageSyncEngine.mailboxPullPhase]
 * during normal sync rounds — this viewmodel just configures the
 * binding the engine consults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MailboxClientViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val bindingService: MailboxBindingService,
) : ViewModel() {

    private val _ownPub = MutableStateFlow<PublicKey?>(null)

    /**
     * Every mailbox host the local user has configured. v0.8.x had at
     * most one; multi-host (Sprint 2 of TOR-ACROSS-WEB) allows N. The
     * UI currently surfaces the first (oldest) entry as "your mailbox"
     * and is silent about additional ones — that polish lands when the
     * test cohort actually starts configuring multi-host.
     */
    val myBindings: StateFlow<List<MailboxBinding>> = _ownPub
        .flatMapLatest { pub ->
            if (pub == null) flowOf(emptyList()) else bindingService.myBindingsFlow(pub)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Backwards-compat surface for the existing single-mailbox UI. First/oldest binding or null. */
    val myBinding: StateFlow<MailboxBinding?> = _ownPub
        .flatMapLatest { pub ->
            if (pub == null) flowOf(null)
            else bindingService.myBindingsFlow(pub).map { it.firstOrNull() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _scanFeedback = MutableStateFlow<String?>(null)
    val scanFeedback: StateFlow<String?> = _scanFeedback.asStateFlow()

    init {
        viewModelScope.launch {
            val pub = withContext(Dispatchers.IO) {
                PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
            }
            _ownPub.value = pub
        }
    }

    /**
     * Apply a freshly-scanned mailbox QR: build a [MailboxBinding]
     * signed by the local identity and persist it as the user's
     * current mailbox. Any existing binding is overwritten — v1
     * supports one mailbox per user.
     */
    fun applyScannedQr(qr: MailboxQr) {
        viewModelScope.launch {
            val pub = _ownPub.value
            if (pub == null) {
                _scanFeedback.value = "identity not ready"
                return@launch
            }
            // Refuse a binding that lacks an onion address. If the host's
            // Tor is still bootstrapping at QR mint time, the QR carries
            // a null onion and the binding becomes BLE-only for its full
            // 90-day validity — silently breaking async delivery whenever
            // the recipient and host aren't co-located. Better to fail
            // loud so the user re-scans once Tor is up.
            if (qr.onionAddress.isNullOrBlank()) {
                _scanFeedback.value = "Mailbox host's Tor hasn't started yet. Wait a moment and re-scan."
                return@launch
            }
            val now = System.currentTimeMillis() / 1000
            val binding = withContext(Dispatchers.IO) {
                MailboxBinding.issue(
                    keystore = keystore,
                    ownerPub = pub,
                    mailboxPub = qr.mailboxPub,
                    mailboxOnion = qr.onionAddress,
                    now = now,
                )
            }
            // addOwn: multi-host upserts the (owner, host) row, leaves
            // other hosts alone. Re-scanning the same host's QR refreshes
            // the cert; scanning a different host's QR adds a second
            // mailbox without evicting the first.
            bindingService.addOwn(binding)
            _scanFeedback.value = "Mailbox configured."
        }
    }

    fun clearBinding() {
        viewModelScope.launch {
            val pub = _ownPub.value ?: return@launch
            bindingService.clearOwn(pub)
            _scanFeedback.value = "Mailbox removed."
        }
    }

    fun dismissFeedback() { _scanFeedback.value = null }
}
