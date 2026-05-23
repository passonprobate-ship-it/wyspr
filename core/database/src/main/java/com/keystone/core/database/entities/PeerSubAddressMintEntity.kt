package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One row per (peerPub, chain) recording the subaddress this device
 * minted for that peer. Sprint W4 — instead of advertising the
 * primary Monero address to everyone, we mint a fresh subaddress
 * per paired peer so on-chain observers can't link payments
 * received from peer A and peer B to the same wallet via shared
 * address reuse.
 *
 * Only the chain-side index pair is stored: for Monero that's
 * `(accountIndex, subAddressIndex)`. The chain-specific address
 * string can be re-derived from the wallet whenever the wallet is
 * open. We persist the string too as a cache so the
 * [OwnPaymentAddressProvider] doesn't have to round-trip the
 * wallet on every sync round.
 *
 * Local-only: never synced to peers (advertised addresses leave
 * the device via the Push frame, but the mint registry stays
 * here).
 */
@Entity(
    tableName = "peer_subaddress_mint",
    primaryKeys = ["peer_pub", "chain"],
    indices = [
        Index(value = ["chain", "account_index", "sub_address_index"]),
    ],
)
data class PeerSubAddressMintEntity(
    @ColumnInfo("peer_pub") val peerPub: ByteArray,
    /** "monero", future: "arrr", etc. */
    @ColumnInfo("chain") val chain: String,
    /** Monero account index (0 for the default account). */
    @ColumnInfo("account_index") val accountIndex: Int,
    /** Monero subaddress index within the account. */
    @ColumnInfo("sub_address_index") val subAddressIndex: Int,
    /** Cached base58 address string — re-derivable from the wallet. */
    @ColumnInfo("address") val address: String,
    @ColumnInfo("created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerSubAddressMintEntity) return false
        return peerPub.contentEquals(other.peerPub) &&
            chain == other.chain &&
            accountIndex == other.accountIndex &&
            subAddressIndex == other.subAddressIndex &&
            address == other.address &&
            createdAt == other.createdAt
    }
    override fun hashCode(): Int {
        var r = peerPub.contentHashCode()
        r = 31 * r + chain.hashCode()
        r = 31 * r + accountIndex.hashCode()
        r = 31 * r + subAddressIndex.hashCode()
        r = 31 * r + address.hashCode()
        r = 31 * r + createdAt.hashCode()
        return r
    }
}
