package com.wyspr.feature.messaging.mailbox

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.identity.PublicKey

/**
 * Signed cert authorising [mailboxPub] to hold inbound for [ownerPub].
 *
 * Issued by the owner (the user who wants to use a mailbox) and
 * signed with their identity key. When Alice (the owner) scans a
 * mailbox host's QR, Alice's device builds and signs this cert; the
 * cert then propagates to Alice's trust-graph peers via sync so they
 * know where to push messages destined for Alice (Phase 3+).
 *
 * Wire form is canonical CBOR. Signature covers the fields in the
 * order below — verifiers re-encode the same bytes with the same
 * codec.
 */
data class MailboxBinding(
    val version: Int,
    val ownerPub: PublicKey,
    val mailboxPub: PublicKey,
    /** Mailbox host's HSv3 .onion (56 base32 chars). Null for BLE-only. */
    val mailboxOnion: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported mailbox binding version: $version" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
        mailboxOnion?.let {
            require(it.length == ONION_LENGTH) { "onion address must be $ONION_LENGTH chars" }
        }
        require(expiresAt > createdAt) { "binding expires before it issued" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailboxBinding) return false
        return version == other.version &&
            ownerPub.bytes.contentEquals(other.ownerPub.bytes) &&
            mailboxPub.bytes.contentEquals(other.mailboxPub.bytes) &&
            mailboxOnion == other.mailboxOnion &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            signature.contentEquals(other.signature)
    }
    override fun hashCode(): Int {
        var r = version
        r = 31 * r + ownerPub.bytes.contentHashCode()
        r = 31 * r + mailboxPub.bytes.contentHashCode()
        r = 31 * r + (mailboxOnion?.hashCode() ?: 0)
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + expiresAt.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    /**
     * Bytes the signature covers. Order is part of the spec; do not
     * reorder. `mailboxOnion` is encoded as bytes (UTF-8) — empty
     * byte string means "no onion" so the codec doesn't need a CBOR
     * null primitive.
     */
    fun signedBytes(): ByteArray = encodeSignedFields(
        version = version,
        ownerPub = ownerPub,
        mailboxPub = mailboxPub,
        mailboxOnion = mailboxOnion,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(7)
        uint(version.toLong())
        bytes(ownerPub.bytes)
        bytes(mailboxPub.bytes)
        bytes((mailboxOnion ?: "").encodeToByteArray())
        uint(createdAt)
        uint(expiresAt)
        bytes(signature)
    }

    companion object {
        const val VERSION = 1
        const val SIG_LENGTH = 64
        const val ONION_LENGTH = 56

        /** 90 days. Long enough that re-issuance isn't frequent; short enough that abandoned mailboxes age out. */
        const val DEFAULT_VALIDITY_SECONDS = 90L * 24 * 60 * 60

        fun issue(
            keystore: KeystoreManager,
            ownerPub: PublicKey,
            mailboxPub: PublicKey,
            mailboxOnion: String?,
            now: Long,
            validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
        ): MailboxBinding {
            val createdAt = now
            val expiresAt = now + validitySeconds
            val signed = encodeSignedFields(
                version = VERSION,
                ownerPub = ownerPub,
                mailboxPub = mailboxPub,
                mailboxOnion = mailboxOnion,
                createdAt = createdAt,
                expiresAt = expiresAt,
            )
            val signature = keystore.sign(signed)
            return MailboxBinding(
                version = VERSION,
                ownerPub = ownerPub,
                mailboxPub = mailboxPub,
                mailboxOnion = mailboxOnion,
                createdAt = createdAt,
                expiresAt = expiresAt,
                signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): MailboxBinding = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 7) { "MailboxBinding must have 7 fields, got $n" }
            val version = uint().toIntChecked()
            val owner = bytes()
            val mailbox = bytes()
            val onionBytes = bytes()
            val createdAt = uint()
            val expiresAt = uint()
            val signature = bytes()
            MailboxBinding(
                version = version,
                ownerPub = PublicKey(owner),
                mailboxPub = PublicKey(mailbox),
                mailboxOnion = onionBytes.decodeToString().takeIf { it.isNotEmpty() },
                createdAt = createdAt,
                expiresAt = expiresAt,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            version: Int,
            ownerPub: PublicKey,
            mailboxPub: PublicKey,
            mailboxOnion: String?,
            createdAt: Long,
            expiresAt: Long,
        ): ByteArray = Cbor.encode {
            arrayHeader(6)
            uint(version.toLong())
            bytes(ownerPub.bytes)
            bytes(mailboxPub.bytes)
            bytes((mailboxOnion ?: "").encodeToByteArray())
            uint(createdAt)
            uint(expiresAt)
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

/**
 * Verify [MailboxBinding]'s signature against the declared owner pub.
 * Returns true iff the signature is authentic AND the cert is within
 * its validity window. `clockSkewSeconds` matches the tolerance used
 * by [com.wyspr.core.trust.verify] on InvitationCertificate.
 */
fun MailboxBinding.verify(
    sodium: LazySodiumAndroid,
    nowSeconds: Long,
    clockSkewSeconds: Long = 0L,
): Boolean {
    if (nowSeconds + clockSkewSeconds < createdAt) return false
    if (nowSeconds - clockSkewSeconds >= expiresAt) return false
    if (signature.size != Sign.BYTES) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, ownerPub.bytes)
}
