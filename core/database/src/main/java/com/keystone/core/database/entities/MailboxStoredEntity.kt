package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single sealed envelope being held by the local device acting as a
 * mailbox. Rows only exist on a host running "Be a mailbox" mode.
 *
 * `ciphertext` is the output of `crypto_box_seal(MessageEnvelope,
 * recipientX25519Pub)`. The host cannot decrypt — they don't have
 * the recipient's secret key. Only the recipient can unseal.
 *
 * `to_pub` is the recipient's Ed25519 identity pub (not their X25519
 * pub). The mailbox indexes on this to fan out `Pull` responses to
 * the right Noise peer. `from_pub` is the sender's identity (the
 * signature on the envelope is by them).
 *
 * `size_bytes` mirrors `ciphertext.length` so the storage-cap query
 * doesn't have to LENGTH() a BLOB per row.
 */
@Entity(
    tableName = "mailbox_stored",
    indices = [
        Index(value = ["to_pub", "created_at"]),
        Index(value = ["expires_at"]),
    ],
)
data class MailboxStoredEntity(
    @PrimaryKey @ColumnInfo("envelope_id") val envelopeId: ByteArray,
    @ColumnInfo("to_pub") val toPub: ByteArray,
    @ColumnInfo("from_pub") val fromPub: ByteArray,
    @ColumnInfo("ciphertext") val ciphertext: ByteArray,
    /**
     * Sender's Ed25519 signature on the outer [MailboxEnvelope] — kept
     * so the host can re-serve the byte-identical envelope to the
     * recipient at pull time. The recipient's primary defence is the
     * INNER `MessageEnvelope` signature (which the host cannot forge
     * since it can't unseal the ciphertext); the outer signature is
     * defence-in-depth that lets the recipient detect a malicious
     * host substituting bogus envelopes.
     */
    @ColumnInfo("signature") val signature: ByteArray,
    @ColumnInfo("size_bytes") val sizeBytes: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("expires_at") val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailboxStoredEntity) return false
        return envelopeId.contentEquals(other.envelopeId) &&
            toPub.contentEquals(other.toPub) &&
            fromPub.contentEquals(other.fromPub) &&
            ciphertext.contentEquals(other.ciphertext) &&
            signature.contentEquals(other.signature) &&
            sizeBytes == other.sizeBytes &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt
    }
    override fun hashCode(): Int {
        var r = envelopeId.contentHashCode()
        r = 31 * r + toPub.contentHashCode()
        r = 31 * r + fromPub.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        r = 31 * r + sizeBytes
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + expiresAt.hashCode()
        return r
    }
}
