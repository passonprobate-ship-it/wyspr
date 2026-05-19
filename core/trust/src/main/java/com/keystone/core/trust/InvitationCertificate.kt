package com.keystone.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.keystone.core.crypto.Cbor
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import java.security.SecureRandom

/**
 * Signed certificate produced by an Inviter for an Invitee.
 * SECURITY-MODEL.md §3.2.
 *
 * Wire form is canonical CBOR (PROTOCOLS.md §1) — both sides re-encode
 * the same fields in the same order with the same codec, so the bytes
 * the signer signed and the bytes the verifier verifies are byte-equal.
 */
data class InvitationCertificate(
    val version: Int,
    val inviterPub: PublicKey,
    val inviteePub: PublicKey,
    val communityId: CommunityId,
    val issuedAt: Long,
    val expiresAt: Long,
    val vouchLevel: VouchLevel,
    val nonce: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported invitation version: $version" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
        require(expiresAt > issuedAt) { "cert expires before it issued" }
    }

    /**
     * Bytes the signature covers. PROTOCOLS.md §3.4 — the order is part
     * of the spec, do not reorder.
     *
     *     [ version, inviterPub, inviteePub, communityId,
     *       issuedAt, expiresAt, vouchLevel, nonce ]
     *
     * `vouchLevel` is encoded as a uint enum tag so we never have to
     * agree on string casing across implementations.
     */
    fun signedBytes(): ByteArray = encodeSignedFields(
        version = version,
        inviterPub = inviterPub,
        inviteePub = inviteePub,
        communityId = communityId,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        vouchLevel = vouchLevel,
        nonce = nonce,
    )

    /**
     * Full on-the-wire encoding: signed fields followed by the signature.
     * Encoded as a 9-element CBOR array — 8 signed fields + signature.
     */
    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(9)
        uint(version.toLong())
        bytes(inviterPub.bytes)
        bytes(inviteePub.bytes)
        bytes(communityId.bytes)
        uint(issuedAt)
        uint(expiresAt)
        uint(vouchLevel.tag.toLong())
        bytes(nonce)
        bytes(signature)
    }

    enum class VouchLevel(val tag: Int) {
        PROVISIONAL(1),
        FULL(2);

        companion object {
            fun fromTag(tag: Int): VouchLevel = entries.first { it.tag == tag }
        }
    }

    companion object {
        const val VERSION = 1
        const val NONCE_LENGTH = 16
        const val SIG_LENGTH = 64

        /** Default 24-hour validity. Tighter is better; never wider. */
        const val DEFAULT_VALIDITY_SECONDS = 24L * 60 * 60

        /**
         * Build, encode, and sign an invitation in one step. The keystore
         * does the signing so the private key never leaves hardware.
         */
        fun issue(
            keystore: KeystoreManager,
            inviterPub: PublicKey,
            inviteePub: PublicKey,
            communityId: CommunityId,
            vouchLevel: VouchLevel,
            now: Long,
            validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
            random: SecureRandom = SecureRandom(),
        ): InvitationCertificate {
            val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
            val issuedAt = now
            val expiresAt = now + validitySeconds
            val signedBytes = encodeSignedFields(
                version = VERSION,
                inviterPub = inviterPub,
                inviteePub = inviteePub,
                communityId = communityId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                vouchLevel = vouchLevel,
                nonce = nonce,
            )
            val signature = keystore.sign(signedBytes)
            return InvitationCertificate(
                version = VERSION,
                inviterPub = inviterPub,
                inviteePub = inviteePub,
                communityId = communityId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                vouchLevel = vouchLevel,
                nonce = nonce,
                signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): InvitationCertificate = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 9) { "InvitationCertificate must have 9 fields, got $n" }
            val version = uint().toIntChecked()
            val inviter = bytes()
            val invitee = bytes()
            val community = bytes()
            val issuedAt = uint()
            val expiresAt = uint()
            val vouchTag = uint().toIntChecked()
            val nonce = bytes()
            val signature = bytes()
            InvitationCertificate(
                version = version,
                inviterPub = PublicKey(inviter),
                inviteePub = PublicKey(invitee),
                communityId = CommunityId(community),
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                vouchLevel = VouchLevel.fromTag(vouchTag),
                nonce = nonce,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            version: Int,
            inviterPub: PublicKey,
            inviteePub: PublicKey,
            communityId: CommunityId,
            issuedAt: Long,
            expiresAt: Long,
            vouchLevel: VouchLevel,
            nonce: ByteArray,
        ): ByteArray = Cbor.encode {
            arrayHeader(8)
            uint(version.toLong())
            bytes(inviterPub.bytes)
            bytes(inviteePub.bytes)
            bytes(communityId.bytes)
            uint(issuedAt)
            uint(expiresAt)
            uint(vouchLevel.tag.toLong())
            bytes(nonce)
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

/**
 * Verifies an [InvitationCertificate]'s Ed25519 signature against the
 * declared inviter public key. Returns true iff the signature is valid
 * AND the cert has not expired AND the inviter is who claims to sign.
 *
 * Verification does NOT prove the inviter is *trusted*; that check is
 * the TrustGraph's job. This only proves the cert is authentic and
 * unaltered.
 */
fun InvitationCertificate.verify(
    sodium: LazySodiumAndroid,
    nowSeconds: Long,
): Boolean {
    if (nowSeconds < issuedAt || nowSeconds >= expiresAt) return false
    if (signature.size != Sign.BYTES) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, inviterPub.bytes)
}

/**
 * Signed revocation. SECURITY-MODEL.md §3.6. There is no un-revoke;
 * a quarantined identity must re-handshake from a clean keypair.
 */
data class RevocationCertificate(
    val version: Int,
    val issuerPub: PublicKey,
    val targetPub: PublicKey,
    val communityId: CommunityId,
    val issuedAt: Long,
    val reasonCode: ReasonCode,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported revocation version: $version" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
    }

    fun signedBytes(): ByteArray = Cbor.encode {
        arrayHeader(6)
        uint(version.toLong())
        bytes(issuerPub.bytes)
        bytes(targetPub.bytes)
        bytes(communityId.bytes)
        uint(issuedAt)
        uint(reasonCode.tag.toLong())
    }

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(7)
        uint(version.toLong())
        bytes(issuerPub.bytes)
        bytes(targetPub.bytes)
        bytes(communityId.bytes)
        uint(issuedAt)
        uint(reasonCode.tag.toLong())
        bytes(signature)
    }

    /** Reasons are an enumeration. Free-text would leak metadata. */
    enum class ReasonCode(val tag: Int) {
        COMPROMISED(1),
        INFILTRATOR(2),
        INACTIVE(3),
        VOLUNTARY_EXIT(4),
        OTHER(99);

        companion object {
            fun fromTag(tag: Int): ReasonCode = entries.first { it.tag == tag }
        }
    }

    companion object {
        const val VERSION = 1
        const val SIG_LENGTH = 64

        fun fromWire(blob: ByteArray): RevocationCertificate = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 7) { "RevocationCertificate must have 7 fields, got $n" }
            val version = uint().toIntChecked()
            val issuer = bytes()
            val target = bytes()
            val community = bytes()
            val issuedAt = uint()
            val reasonTag = uint().toIntChecked()
            val signature = bytes()
            RevocationCertificate(
                version = version,
                issuerPub = PublicKey(issuer),
                targetPub = PublicKey(target),
                communityId = CommunityId(community),
                issuedAt = issuedAt,
                reasonCode = ReasonCode.fromTag(reasonTag),
                signature = signature,
            )
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

fun RevocationCertificate.verify(sodium: LazySodiumAndroid): Boolean {
    if (signature.size != Sign.BYTES) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, issuerPub.bytes)
}
