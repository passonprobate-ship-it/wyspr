package com.keystone.feature.messaging.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.TorBackend
import com.keystone.core.ui.settings.MailboxSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "Be a mailbox" screen.
 *
 * Surfaces:
 *  - The host-enabled toggle (drives [MailboxHost] start/stop)
 *  - Live storage stats (count, bytes held, unique recipients)
 *  - A QR payload encoding (mailboxPub, optional onion) for peers to
 *    scan
 *  - "Purge held messages" action (for the Stop / Purge button)
 *
 * The user's own [com.keystone.feature.messaging.mailbox.MailboxBinding]
 * is owned by the OTHER role's viewmodel ([MailboxClientViewModel]).
 * Hosting a mailbox and using one are independent — a device can do
 * both, neither, or either.
 */
@HiltViewModel
class MailboxHostViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: KeystoneDatabase,
    private val settings: MailboxSettings,
    private val mailboxHost: MailboxHost,
    private val torBackend: TorBackend,
) : ViewModel() {

    val hostEnabled: StateFlow<Boolean> = settings.hostEnabled
    val storageCapBytes: StateFlow<Long> = settings.storageCapBytes

    /** Bytes currently held across every recipient. */
    val bytesHeld: StateFlow<Long> =
        database.mailboxStoredDao.totalSizeBytesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Envelope-row count across every recipient. */
    val countHeld: StateFlow<Int> =
        database.mailboxStoredDao.totalCountFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Distinct recipients we're holding for. */
    val recipientCount: StateFlow<Int> =
        database.mailboxStoredDao.uniqueRecipientsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _mailboxQr = MutableStateFlow<String?>(null)
    /** Base32 QR payload, refreshed whenever onion availability flips. */
    val mailboxQr: StateFlow<String?> = _mailboxQr.asStateFlow()

    init {
        viewModelScope.launch {
            // QR carries the host's own pub + its current .onion (if
            // bootstrapped). We rebuild when either side moves.
            val ownPub = withContext(Dispatchers.IO) {
                PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
            }
            torBackend.onionAddress.combine(torBackend.state) { onion, _ -> onion }
                .collect { onion ->
                    _mailboxQr.value = MailboxQrCodec.toBase32(
                        MailboxQr(
                            version = MailboxQr.VERSION,
                            mailboxPub = ownPub,
                            onionAddress = onion,
                        ),
                    )
                }
        }
    }

    fun setHostEnabled(enabled: Boolean) {
        settings.setHostEnabled(enabled)
    }

    /**
     * Drop everything we're holding. Called from the destructive-action
     * panel on the host screen. Does NOT disable hosting; that's a
     * separate toggle.
     */
    fun purgeHeld() {
        viewModelScope.launch { mailboxHost.purgeAll() }
    }
}
