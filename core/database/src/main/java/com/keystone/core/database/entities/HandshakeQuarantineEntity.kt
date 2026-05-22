package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks peers that aborted a handshake recently. SECURITY-MODEL.md §3.3
 * specifies a 24h cooldown after a failed channel-binding so an
 * attacker can't grind QR mints. Without this, abort() was a pure
 * state-flow flip with no DB persistence.
 */
@Entity(tableName = "handshake_quarantine")
data class HandshakeQuarantineEntity(
    @PrimaryKey @ColumnInfo("peer_pub") val peerPub: ByteArray,
    @ColumnInfo("quarantined_until") val quarantinedUntil: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeQuarantineEntity) return false
        return peerPub.contentEquals(other.peerPub) && quarantinedUntil == other.quarantinedUntil
    }
    override fun hashCode(): Int =
        31 * peerPub.contentHashCode() + quarantinedUntil.hashCode()
}
