package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-use nonce store for invitation certificates. Per
 * SECURITY-MODEL.md §3.2 the nonce on each invitation cert is
 * one-shot; replay of the same cert (even within validity) is
 * refused. Previously there was no enforcement — a stolen cert
 * could be replayed against the same Invitee identity until the
 * validity window expired, swapping the persisted TrustEdge's
 * `peerOnion` field on each replay.
 */
@Entity(tableName = "seen_cert_nonce")
data class SeenCertNonceEntity(
    @PrimaryKey @ColumnInfo("nonce") val nonce: ByteArray,
    @ColumnInfo("observed_at") val observedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeenCertNonceEntity) return false
        return nonce.contentEquals(other.nonce) && observedAt == other.observedAt
    }
    override fun hashCode(): Int =
        31 * nonce.contentHashCode() + observedAt.hashCode()
}
