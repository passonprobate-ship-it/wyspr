package com.wyspr.core.database.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted form of a single message between this device and one
 * paired peer.
 *
 * Threading is implicit: a "thread" is identified by [threadPub] —
 * the peer's pubkey regardless of message direction. The local
 * device's pubkey is never used as a thread id.
 *
 * Statuses (string-encoded to avoid an enum schema migration when
 * a new state lands):
 *   - "pending"   — outbound, not yet pushed over a transport link
 *   - "sent"      — pushed; receiver hasn't ACKed
 *   - "delivered" — receiver ACKed
 *   - "received"  — inbound, accepted into the DB
 *
 * Body is plaintext at this layer; SQLCipher provides at-rest
 * encryption and Noise provides in-flight encryption when Sprint 2
 * lands. Forward secrecy via per-session ephemerals comes from the
 * Noise transport, not from the message envelope.
 *
 * `signature` is the sender's Ed25519 signature over the canonical
 * CBOR of (id, fromPub, toPub, createdAt, body). Lets the receiver
 * verify a message is from the claimed sender even after the
 * Noise session has been torn down.
 */
@Entity(
    tableName = "message",
    indices = [
        Index(value = ["thread_pub", "created_at"]),
        Index(value = ["status"]),
    ],
)
@Immutable
data class MessageEntity(
    @PrimaryKey @ColumnInfo("id") val id: ByteArray,
    @ColumnInfo("thread_pub") val threadPub: ByteArray,
    @ColumnInfo("from_pub") val fromPub: ByteArray,
    @ColumnInfo("to_pub") val toPub: ByteArray,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("received_at") val receivedAt: Long?,
    @ColumnInfo("body") val body: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("signature") val signature: ByteArray,
    @ColumnInfo("expires_at") val expiresAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id.contentEquals(other.id) &&
            threadPub.contentEquals(other.threadPub) &&
            fromPub.contentEquals(other.fromPub) &&
            toPub.contentEquals(other.toPub) &&
            createdAt == other.createdAt &&
            receivedAt == other.receivedAt &&
            body == other.body &&
            status == other.status &&
            signature.contentEquals(other.signature) &&
            expiresAt == other.expiresAt
    }
    override fun hashCode(): Int {
        var r = id.contentHashCode()
        r = 31 * r + threadPub.contentHashCode()
        r = 31 * r + fromPub.contentHashCode()
        r = 31 * r + toPub.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + (receivedAt?.hashCode() ?: 0)
        r = 31 * r + body.hashCode()
        r = 31 * r + status.hashCode()
        r = 31 * r + signature.contentHashCode()
        r = 31 * r + (expiresAt?.hashCode() ?: 0)
        return r
    }
}
