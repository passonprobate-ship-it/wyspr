package com.keystone.feature.monero

import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.PeerPaymentAddressEntity
import com.keystone.core.identity.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chain-agnostic binding of "this paired peer publishes this
 * payment address on this chain." Backed by the `peer_payment_address`
 * table (schema v15). Read by [MoneroWalletService.sendTo] to
 * resolve a peer's friendly name into the actual Monero address
 * before constructing a transfer; written by the conversation UI
 * when the user pastes / scans / receives a peer's address.
 *
 * v1 chains: `"monero"`. The service is deliberately chain-string-
 * scoped rather than Monero-specific so a future "send ARRR" path
 * uses the same plumbing.
 */
@Singleton
class PaymentAddressService @Inject constructor(
    private val database: KeystoneDatabase,
) {

    /** Active address for [peerPub] on [chain], or null if no binding exists. */
    suspend fun currentForPeer(peerPub: PublicKey, chain: String): String? =
        withContext(Dispatchers.IO) {
            ensureOpen()
            database.peerPaymentAddressDao.currentForPeer(peerPub.bytes, chain)?.address
        }

    /** Reactive variant — Compose subscribes to gate "Send XMR" buttons. */
    fun currentForPeerFlow(peerPub: PublicKey, chain: String): Flow<String?> =
        database.peerPaymentAddressDao
            .currentForPeerFlow(peerPub.bytes, chain)
            .map { it?.address }

    /**
     * Bind [address] to [peerPub] on [chain]. If the same tuple is
     * already in the table the upsert is a no-op (composite-PK
     * REPLACE rewrites the same row).
     *
     * Re-binding a different address creates a NEW row alongside
     * the old one — old row stays active until you [revoke] it.
     * That's intentional: gives the user audit history and lets a
     * peer's old address keep working briefly during a rotation.
     */
    suspend fun addForPeer(
        peerPub: PublicKey,
        chain: String,
        address: String,
        notes: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.peerPaymentAddressDao.upsert(
            PeerPaymentAddressEntity(
                peerPub = peerPub.bytes,
                chain = chain,
                address = address,
                createdAt = nowSeconds,
                revokedAt = null,
                notes = notes,
            ),
        )
    }

    /** Soft-delete a specific binding (peer rotated, user wants to stop using). */
    suspend fun revoke(
        peerPub: PublicKey,
        chain: String,
        address: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.peerPaymentAddressDao.revoke(peerPub.bytes, chain, address, nowSeconds)
    }

    /** All bindings (active + revoked) for [peerPub]. Used by the audit/details UI. */
    suspend fun allForPeer(peerPub: PublicKey): List<PeerPaymentAddressEntity> =
        withContext(Dispatchers.IO) {
            ensureOpen()
            database.peerPaymentAddressDao.allForPeer(peerPub.bytes)
        }

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }

    companion object {
        /** Chain string used for Monero (XMR) bindings. */
        const val CHAIN_MONERO = "monero"
    }
}
