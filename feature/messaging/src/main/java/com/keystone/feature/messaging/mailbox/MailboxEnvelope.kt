package com.keystone.feature.messaging.mailbox

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.keystone.core.crypto.Cbor
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.MessageEnvelope
import java.security.SecureRandom

/**
 * Sealed envelope for asynchronous delivery via a mailbox host.
 *
 * Distinct from [MessageEnvelope] because the mailbox host must be
 * able to ROUTE without being able to READ. The recipient's
 * Ed25519 identity is in clear (so the host can index storage by
 * recipient); the actual [MessageEnvelope] is wrapped with
 * `crypto_box_seal` to the recipient's X25519 key (derived from
 * their Ed25519 identity). Only the recipient can unseal.
 *
 * Wire form (CBOR, RFC 8949 §4.2.1 canonical), 6 elements:
 *
 *     [ id (16 bytes),
 *       toPub (32 bytes — recipient Ed25519),
 *       fromPub (32 bytes — sender Ed25519),
 *       createdAt (uint, sender clock seconds),
 *       ciphertext (sealed_box(MessageEnvelope.wireBytes, recipientX25519)),
 *       signature (64 bytes) ]
 *
 * Signature is Ed25519 by [fromPub] over CBOR(id, toPub, fromPub,
 * createdAt, ciphertext). A mailbox host verifies this before
 * storing — that's how it rejects pushes from non-community
 * members without ever decrypting.
 */
data class MailboxEnvelope(
    val id: ByteArray,
    val toPub: PublicKey,
    val fromPub: PublicKey,
    val createdAt: Long,
    val ciphertext: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(id.size == ID_LENGTH) { "envelope id must be $ID_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
        require(ciphertext.size >= Box.SEALBYTES) {
            "ciphertext shorter than sealed-box overhead ($SEAL_OVERHEAD bytes)"
        }
    }

    // ByteArray fields in a Kotlin data class compare by reference under
    // the auto-generated equals; override so test assertions and de-dup
    // logic work as readers expect.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailboxEnvelope) return false
        return id.contentEquals(other.id) &&
            toPub.bytes.contentEquals(other.toPub.bytes) &&
            fromPub.bytes.contentEquals(other.fromPub.bytes) &&
            createdAt == other.createdAt &&
            ciphertext.contentEquals(other.ciphertext) &&
            signature.contentEquals(other.signature)
    }
    override fun hashCode(): Int {
        var r = id.contentHashCode()
        r = 31 * r + toPub.bytes.contentHashCode()
        r = 31 * r + fromPub.bytes.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    /** Bytes the signature covers. */
    fun signedBytes(): ByteArray = encodeSignedFields(
        id = id,
        toPub = toPub,
        fromPub = fromPub,
        createdAt = createdAt,
        ciphertext = ciphertext,
    )

    /** Full on-the-wire CBOR encoding. */
    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(6)
        bytes(id)
        bytes(toPub.bytes)
        bytes(fromPub.bytes)
        uint(createdAt)
        bytes(ciphertext)
        bytes(signature)
    }

    companion object {
        const val ID_LENGTH = 16
        const val SIG_LENGTH = 64

        /** `crypto_box_seal` overhead: 32 (ephemeral pub) + 16 (MAC). */
        const val SEAL_OVERHEAD = 48

        /** 14 days. Matches docs/MAILBOX.md baked-in default. */
        const val DEFAULT_RETENTION_SECONDS = 14L * 24 * 60 * 60

        /**
         * Seal a [MessageEnvelope] for asynchronous delivery via a
         * mailbox. The inner envelope is wrapped with
         * `crypto_box_seal` to the recipient's X25519 key, derived
         * from their Ed25519 identity. The outer signature is
         * produced by the sender's keystore; the mailbox host uses
         * it to verify the push without unsealing.
         *
         * `inner.toPub` MUST equal [recipientPub] — we re-check
         * here to prevent a caller from sealing a message for one
         * recipient and addressing the mailbox row to another.
         */
        fun seal(
            keystore: KeystoreManager,
            sodium: LazySodiumAndroid,
            inner: MessageEnvelope,
            recipientPub: PublicKey,
            now: Long,
            random: SecureRandom = SecureRandom(),
        ): MailboxEnvelope {
            require(inner.toPub.bytes.contentEquals(recipientPub.bytes)) {
                "inner.toPub does not match recipientPub"
            }
            val xPub = ByteArray(Box.PUBLICKEYBYTES)
            require(sodium.convertPublicKeyEd25519ToCurve25519(xPub, recipientPub.bytes)) {
                "Ed25519 → X25519 public-key conversion failed"
            }
            val plaintext = inner.wireBytes()
            val ciphertext = ByteArray(plaintext.size + Box.SEALBYTES)
            require(
                sodium.cryptoBoxSeal(ciphertext, plaintext, plaintext.size.toLong(), xPub),
            ) { "crypto_box_seal failed" }

            val id = ByteArray(ID_LENGTH).also { random.nextBytes(it) }
            val signedBytes = encodeSignedFields(
                id = id,
                toPub = recipientPub,
                fromPub = inner.fromPub,
                createdAt = now,
                ciphertext = ciphertext,
            )
            val signature = keystore.sign(signedBytes)
            return MailboxEnvelope(
                id = id,
                toPub = recipientPub,
                fromPub = inner.fromPub,
                createdAt = now,
                ciphertext = ciphertext,
                signature = signature,
            )
        }

        /**
         * Unseal a [MailboxEnvelope] back into its inner
         * [MessageEnvelope] using the recipient's X25519 keypair
         * (which only the recipient has, derived from their
         * hardware-protected identity seed via
         * [com.keystone.core.crypto.KeystoreManager.deriveStaticX25519]).
         *
         * Throws if the ciphertext fails to decrypt OR if the inner
         * envelope's claimed [MessageEnvelope.toPub] doesn't match
         * the outer recipient — that's the cross-binding the seal
         * step asserts; the unseal step asserts the symmetric form.
         */
        fun open(
            sodium: LazySodiumAndroid,
            envelope: MailboxEnvelope,
            recipientX25519Pub: ByteArray,
            recipientX25519Sec: ByteArray,
        ): MessageEnvelope {
            require(recipientX25519Pub.size == Box.PUBLICKEYBYTES)
            require(recipientX25519Sec.size == Box.SECRETKEYBYTES)
            val plaintext = ByteArray(envelope.ciphertext.size - Box.SEALBYTES)
            require(
                sodium.cryptoBoxSealOpen(
                    plaintext,
                    envelope.ciphertext,
                    envelope.ciphertext.size.toLong(),
                    recipientX25519Pub,
                    recipientX25519Sec,
                ),
            ) { "crypto_box_seal_open failed (corrupt or wrong recipient)" }
            val inner = MessageEnvelope.fromWire(plaintext)
            require(inner.toPub.bytes.contentEquals(envelope.toPub.bytes)) {
                "inner.toPub does not match outer envelope.toPub"
            }
            return inner
        }

        fun fromWire(blob: ByteArray): MailboxEnvelope = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 6) { "MailboxEnvelope must have 6 fields, got $n" }
            val id = bytes()
            val toPub = PublicKey(bytes())
            val fromPub = PublicKey(bytes())
            val createdAt = uint()
            val ciphertext = bytes()
            val signature = bytes()
            MailboxEnvelope(
                id = id,
                toPub = toPub,
                fromPub = fromPub,
                createdAt = createdAt,
                ciphertext = ciphertext,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            id: ByteArray,
            toPub: PublicKey,
            fromPub: PublicKey,
            createdAt: Long,
            ciphertext: ByteArray,
        ): ByteArray = Cbor.encode {
            arrayHeader(5)
            bytes(id)
            bytes(toPub.bytes)
            bytes(fromPub.bytes)
            uint(createdAt)
            bytes(ciphertext)
        }
    }
}

/**
 * Verify the outer Ed25519 signature on a [MailboxEnvelope]. Returns
 * true iff the signature is valid by [MailboxEnvelope.fromPub].
 *
 * Verifying this does NOT prove the sender is in the host's
 * community — that's a trust-graph check the host does separately
 * before storing.
 */
fun MailboxEnvelope.verify(sodium: LazySodiumAndroid): Boolean {
    if (signature.size != Sign.BYTES) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, fromPub.bytes)
}
