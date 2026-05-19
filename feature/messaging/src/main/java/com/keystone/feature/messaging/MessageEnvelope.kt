package com.keystone.feature.messaging

import com.keystone.core.crypto.Cbor
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.identity.PublicKey
import java.security.SecureRandom

/**
 * Signed envelope around one peer-to-peer message.
 *
 * Sprint 1 (this commit) only constructs and stores envelopes
 * locally — the wire side that ships these between paired devices
 * over Noise lands in Sprint 2. The structure is fixed now so
 * Sprint 2 doesn't have to retrofit a signature scheme onto
 * already-persisted rows.
 *
 * Wire layout (CBOR, RFC 8949 §4.2.1 canonical) — the same shape
 * `InvitationCertificate` uses for its signed bytes:
 *
 *     [ id (16 bytes),
 *       fromPub (32 bytes),
 *       toPub (32 bytes),
 *       createdAt (uint, sender clock seconds),
 *       body (UTF-8 text) ]
 *
 * The signature is Ed25519 over this exact byte sequence, produced
 * by the sender's hardware keystore. Verifiers reconstruct the
 * same bytes and check against the claimed [fromPub].
 */
data class MessageEnvelope(
    val id: ByteArray,
    val fromPub: PublicKey,
    val toPub: PublicKey,
    val createdAt: Long,
    val body: String,
    val signature: ByteArray,
) {
    init {
        require(id.size == ID_LENGTH) { "message id must be $ID_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
    }

    fun signedBytes(): ByteArray = encodeSignedFields(id, fromPub, toPub, createdAt, body)

    /**
     * Full wire form including the signature. Mirrors [signedBytes]
     * but with one extra `bytes(signature)` element — 6-element array
     * total. Sprint 2 sync uses this when pushing envelopes to a peer
     * over the Noise transport.
     */
    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(6)
        bytes(id)
        bytes(fromPub.bytes)
        bytes(toPub.bytes)
        uint(createdAt)
        bytes(body.encodeToByteArray())
        bytes(signature)
    }

    // verify() lives in core:trust where lazysodium is already on
    // the classpath. Sprint 2 will add an extension function in the
    // sync layer:
    //     fun MessageEnvelope.verify(sodium: LazySodiumAndroid): Boolean
    // For Sprint 1 the signature is constructed but never checked
    // since no inbound path exists yet.

    companion object {

        /** Inverse of [wireBytes]. Throws on any malformed input. */
        fun fromWire(bytes: ByteArray): MessageEnvelope = Cbor.decode(bytes) {
            val n = arrayHeader()
            require(n == 6) { "MessageEnvelope must have 6 fields, got $n" }
            val id = bytes()
            val fromPub = PublicKey(bytes())
            val toPub = PublicKey(bytes())
            val createdAt = uint()
            val body = bytes().decodeToString()
            val signature = bytes()
            MessageEnvelope(
                id = id,
                fromPub = fromPub,
                toPub = toPub,
                createdAt = createdAt,
                body = body,
                signature = signature,
            )
        }

        const val ID_LENGTH = 16
        const val SIG_LENGTH = 64
        const val MAX_BODY_BYTES = 16_384

        /**
         * Build and sign a fresh outbound envelope. Generates a
         * random 16-byte id; the keystore signs the canonical
         * CBOR encoding. The body is truncated by the UI long
         * before reaching here, but the cap is asserted again as
         * defence-in-depth.
         */
        fun issue(
            keystore: KeystoreManager,
            fromPub: PublicKey,
            toPub: PublicKey,
            body: String,
            now: Long,
            random: SecureRandom = SecureRandom(),
        ): MessageEnvelope {
            require(body.isNotEmpty()) { "message body must not be empty" }
            require(body.encodeToByteArray().size <= MAX_BODY_BYTES) {
                "message body exceeds $MAX_BODY_BYTES bytes"
            }
            val id = ByteArray(ID_LENGTH).also { random.nextBytes(it) }
            val signed = encodeSignedFields(id, fromPub, toPub, now, body)
            val signature = keystore.sign(signed)
            return MessageEnvelope(
                id = id,
                fromPub = fromPub,
                toPub = toPub,
                createdAt = now,
                body = body,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            id: ByteArray,
            fromPub: PublicKey,
            toPub: PublicKey,
            createdAt: Long,
            body: String,
        ): ByteArray = Cbor.encode {
            arrayHeader(5)
            bytes(id)
            bytes(fromPub.bytes)
            bytes(toPub.bytes)
            uint(createdAt)
            // Body encoded as bytes (UTF-8). Cbor.kt deliberately doesn't
            // expose a text-string writer; bytes are sufficient for the
            // signed-form contract since both sides agree on UTF-8.
            bytes(body.encodeToByteArray())
        }
    }
}
