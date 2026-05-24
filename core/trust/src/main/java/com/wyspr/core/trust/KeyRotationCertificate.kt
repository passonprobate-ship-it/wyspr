package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey

/**
 * Signed certificate linking an old identity key to a new one.
 * SECURITY-MODEL.md §8.5.
 *
 * Wire form: 7-element canonical CBOR array (6 signed fields + signature).
 * Signed by the OLD key to prove the holder authorized the transition.
 */
data class KeyRotationCertificate(
    val version: Int,
    val oldPub: PublicKey,
    val newPub: PublicKey,
    val communityId: CommunityId,
    val issuedAt: Long,
    val newOnion: ByteArray?,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported key rotation version: $version" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
        if (newOnion != null) {
            require(newOnion.size == ONION_LENGTH) { "onion must be $ONION_LENGTH bytes" }
        }
        require(!oldPub.bytes.contentEquals(newPub.bytes)) { "old and new keys must differ" }
    }

    fun signedBytes(): ByteArray = encodeSignedFields(
        version = version,
        oldPub = oldPub,
        newPub = newPub,
        communityId = communityId,
        issuedAt = issuedAt,
        newOnion = newOnion,
    )

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(7)
        uint(version.toLong())
        bytes(oldPub.bytes)
        bytes(newPub.bytes)
        bytes(communityId.bytes)
        uint(issuedAt)
        if (newOnion != null) bytes(newOnion) else nullValue()
        bytes(signature)
    }

    companion object {
        const val VERSION = 1
        const val SIG_LENGTH = 64
        const val ONION_LENGTH = 56

        fun issue(
            keystore: KeystoreManager,
            oldPub: PublicKey,
            newPub: PublicKey,
            communityId: CommunityId,
            newOnion: ByteArray?,
            now: Long = System.currentTimeMillis() / 1000,
        ): KeyRotationCertificate {
            val signedBytes = encodeSignedFields(
                version = VERSION,
                oldPub = oldPub,
                newPub = newPub,
                communityId = communityId,
                issuedAt = now,
                newOnion = newOnion,
            )
            val signature = keystore.sign(signedBytes)
            return KeyRotationCertificate(
                version = VERSION,
                oldPub = oldPub,
                newPub = newPub,
                communityId = communityId,
                issuedAt = now,
                newOnion = newOnion,
                signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): KeyRotationCertificate = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 7) { "KeyRotationCertificate must have 7 fields, got $n" }
            val version = uint().toIntChecked()
            val oldPub = bytes()
            val newPub = bytes()
            val community = bytes()
            val issuedAt = uint()
            val newOnion = bytesOrNull()
            val signature = bytes()
            KeyRotationCertificate(
                version = version,
                oldPub = PublicKey(oldPub),
                newPub = PublicKey(newPub),
                communityId = CommunityId(community),
                issuedAt = issuedAt,
                newOnion = newOnion,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            version: Int,
            oldPub: PublicKey,
            newPub: PublicKey,
            communityId: CommunityId,
            issuedAt: Long,
            newOnion: ByteArray?,
        ): ByteArray = Cbor.encode {
            arrayHeader(6)
            uint(version.toLong())
            bytes(oldPub.bytes)
            bytes(newPub.bytes)
            bytes(communityId.bytes)
            uint(issuedAt)
            if (newOnion != null) bytes(newOnion) else nullValue()
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

fun KeyRotationCertificate.verify(
    sodium: LazySodiumAndroid,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    maxFutureSkewSeconds: Long = DEFAULT_CLOCK_SKEW_SECONDS,
): Boolean {
    if (signature.size != Sign.BYTES) return false
    if (issuedAt < 0) return false
    if (issuedAt > nowSeconds + maxFutureSkewSeconds) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, oldPub.bytes)
}
