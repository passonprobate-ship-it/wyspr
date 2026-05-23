package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * User-supplied display name for a paired peer. Indexed by the peer's
 * 32-byte Ed25519 public key so renaming works across both directions
 * of a trust edge (Inviter-side and Invitee-side) and survives a
 * re-pairing as long as the key material itself is preserved.
 *
 * A `null` or missing row means "no friendly name set" — the UI falls
 * back to displaying the fingerprint.
 *
 * Kept in its own table rather than as a column on `trust_edge` so:
 *   1. It survives revocation/re-issuance of the underlying cert.
 *   2. It doesn't multiply across multiple edges to the same peer.
 *   3. Sensitive contact metadata stays cleanly separable from the
 *      cryptographic trust state if we ever need to export/wipe one
 *      without the other.
 */
@Entity(tableName = "contact")
data class ContactEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "peerPub") val peerPub: ByteArray,
    @ColumnInfo(name = "displayName") val displayName: String?,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "disappear_after") val disappearAfter: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return peerPub.contentEquals(other.peerPub) &&
            displayName == other.displayName &&
            notes == other.notes &&
            disappearAfter == other.disappearAfter
    }

    override fun hashCode(): Int {
        var result = peerPub.contentHashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (notes?.hashCode() ?: 0)
        result = 31 * result + (disappearAfter?.hashCode() ?: 0)
        return result
    }
}
