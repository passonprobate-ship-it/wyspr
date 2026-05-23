package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Chain-agnostic payment-address binding for a paired peer.
 *
 * One row per `(peer_pub, chain, address)` tuple. A "current"
 * address for a peer is the newest row with `revoked_at IS NULL`
 * for that chain; older rows stay on disk for audit / rotation
 * history. Soft-delete via `revoked_at` rather than hard delete
 * lets the user verify "yes, this is the address you gave me on
 * date X" after rotating.
 *
 * Chain values in v1:
 *   - `"monero"` — Monero subaddress (or primary address) the
 *     peer published. v2 may add `"arrr"`, `"btc-lightning"`, etc.
 *
 * Sprint W2 wiring (docs/MONERO-WALLET.md): set via UI on the
 * conversation peer-detail screen (the user pastes the peer's
 * shared XMR address) or via the messaging sync round (auto-
 * exchange — future polish). Looked up by `MoneroWalletService.sendTo`
 * before constructing the Monero transfer.
 */
@Entity(
    tableName = "peer_payment_address",
    primaryKeys = ["peer_pub", "chain", "address"],
    indices = [
        // Lookups are "give me the current address for (peerPub, chain)" —
        // the index drives that filter / sort. created_at desc-by-default
        // is implicit via the sort at query time.
        Index(value = ["peer_pub", "chain", "revoked_at"]),
    ],
)
data class PeerPaymentAddressEntity(
    @ColumnInfo("peer_pub") val peerPub: ByteArray,
    /** "monero", future: "arrr", "btc-lightning", … */
    @ColumnInfo("chain") val chain: String,
    /** Chain-specific encoded address (Monero base58, etc.). */
    @ColumnInfo("address") val address: String,
    @ColumnInfo("created_at") val createdAt: Long,
    /** Soft-delete marker. Null = active. Unix seconds. */
    @ColumnInfo("revoked_at") val revokedAt: Long?,
    /** Optional local-only free-form note ("address Alice gave me on…"). Never synced. */
    @ColumnInfo("notes") val notes: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerPaymentAddressEntity) return false
        return peerPub.contentEquals(other.peerPub) &&
            chain == other.chain &&
            address == other.address &&
            createdAt == other.createdAt &&
            revokedAt == other.revokedAt &&
            notes == other.notes
    }
    override fun hashCode(): Int {
        var r = peerPub.contentHashCode()
        r = 31 * r + chain.hashCode()
        r = 31 * r + address.hashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + (revokedAt?.hashCode() ?: 0)
        r = 31 * r + (notes?.hashCode() ?: 0)
        return r
    }
}
