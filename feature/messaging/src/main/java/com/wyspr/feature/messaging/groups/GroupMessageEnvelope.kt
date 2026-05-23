package com.wyspr.feature.messaging.groups

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PublicKey
import java.security.SecureRandom

/**
 * Signed envelope around one message posted to a private group.
 * Same wire-discipline as `MessageEnvelope` — CBOR canonical, Ed25519
 * over a fixed field order — but with `groupId` instead of `toPub`.
 *
 * Wire layout for the signed bytes (PROTOCOLS.md):
 *
 *     [ id (16 bytes),
 *       fromPub (32 bytes),
 *       groupId (32 bytes),
 *       createdAt (uint, sender clock seconds),
 *       body (UTF-8 bytes) ]
 *
 * Receiver re-encodes the same fields and checks the signature
 * against [fromPub] — confirmation that the message actually came
 * from the claimed sender (channel binding alone proves the
 * transport peer, not the original author when group messages are
 * forwarded via gossip in v2+).
 *
 * The same envelope is fanned out by the sender to every member of
 * the group as they currently understand membership. Receivers
 * dedupe by [id] — if two members hand C the same envelope, C
 * stores it once.
 */
data class GroupMessageEnvelope(
    val id: ByteArray,
    val fromPub: PublicKey,
    val groupId: GroupId,
    val createdAt: Long,
    val body: String,
    val signature: ByteArray,
) {
    init {
        require(id.size == ID_LENGTH) { "group message id must be $ID_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
    }

    fun signedBytes(): ByteArray = encodeSignedFields(id, fromPub, groupId, createdAt, body)

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(6)
        bytes(id)
        bytes(fromPub.bytes)
        bytes(groupId.bytes)
        uint(createdAt)
        bytes(body.encodeToByteArray())
        bytes(signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessageEnvelope) return false
        return id.contentEquals(other.id) &&
            fromPub.bytes.contentEquals(other.fromPub.bytes) &&
            groupId.bytes.contentEquals(other.groupId.bytes) &&
            createdAt == other.createdAt &&
            body == other.body &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = id.contentHashCode()
        r = 31 * r + fromPub.bytes.contentHashCode()
        r = 31 * r + groupId.bytes.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + body.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        const val ID_LENGTH = 16
        const val SIG_LENGTH = 64
        const val MAX_BODY_BYTES = 204_800

        fun fromWire(bytes: ByteArray): GroupMessageEnvelope = Cbor.decode(bytes) {
            val n = arrayHeader()
            require(n == 6) { "GroupMessageEnvelope must have 6 fields, got $n" }
            val id = bytes()
            val fromPub = PublicKey(bytes())
            val groupId = GroupId(bytes())
            val createdAt = uint()
            val bodyBytes = bytes()
            require(bodyBytes.size <= MAX_BODY_BYTES) {
                "group message body exceeds $MAX_BODY_BYTES bytes"
            }
            require(bodyBytes.isNotEmpty()) { "group message body must not be empty" }
            val body = bodyBytes.decodeToString()
            val signature = bytes()
            GroupMessageEnvelope(
                id = id,
                fromPub = fromPub,
                groupId = groupId,
                createdAt = createdAt,
                body = body,
                signature = signature,
            )
        }

        /**
         * Build and sign a fresh outbound group envelope. The id is a
         * random 16 bytes; the keystore signs the canonical CBOR
         * encoding of the signed-field order above.
         */
        fun issue(
            keystore: KeystoreManager,
            fromPub: PublicKey,
            groupId: GroupId,
            body: String,
            now: Long,
            random: SecureRandom = SecureRandom(),
        ): GroupMessageEnvelope {
            require(body.isNotEmpty()) { "group message body must not be empty" }
            require(body.encodeToByteArray().size <= MAX_BODY_BYTES) {
                "group message body exceeds $MAX_BODY_BYTES bytes"
            }
            val id = ByteArray(ID_LENGTH).also { random.nextBytes(it) }
            val signed = encodeSignedFields(id, fromPub, groupId, now, body)
            val signature = keystore.sign(signed)
            return GroupMessageEnvelope(
                id = id,
                fromPub = fromPub,
                groupId = groupId,
                createdAt = now,
                body = body,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            id: ByteArray,
            fromPub: PublicKey,
            groupId: GroupId,
            createdAt: Long,
            body: String,
        ): ByteArray = Cbor.encode {
            arrayHeader(5)
            bytes(id)
            bytes(fromPub.bytes)
            bytes(groupId.bytes)
            uint(createdAt)
            bytes(body.encodeToByteArray())
        }
    }
}

/**
 * Verify the envelope's Ed25519 signature against the claimed
 * [GroupMessageEnvelope.fromPub]. Returns true iff the signature
 * checks out. Group membership is NOT verified here — the caller
 * must additionally confirm [fromPub] holds an active
 * [GroupMembership] cert for [GroupMessageEnvelope.groupId]
 * via the local group_member table.
 */
fun GroupMessageEnvelope.verify(sodium: LazySodiumAndroid): Boolean {
    if (signature.size != Sign.BYTES) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, fromPub.bytes)
}
