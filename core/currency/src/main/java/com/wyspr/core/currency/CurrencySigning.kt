package com.wyspr.core.currency

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.KeystoreManager

/**
 * Thin wrapper around the two halves of envelope signing:
 *
 *  - Outbound: signing always routes through [KeystoreManager.sign] so
 *    the private key never leaves the hardware keystore.
 *  - Inbound: verification uses libsodium directly with the sender's
 *    public key. No keystore round-trip is needed (nor possible — the
 *    sender's private bits are on their device).
 *
 * The byte sequence passed in is whatever `signedBytes()` produced on
 * the corresponding envelope.
 */
object CurrencySigning {

    /** Sign with the local identity key. */
    fun sign(keystore: KeystoreManager, signedBytes: ByteArray): ByteArray {
        val sig = keystore.sign(signedBytes)
        require(sig.size == Sign.BYTES) {
            "Unexpected Ed25519 signature length: ${sig.size}"
        }
        return sig
    }

    /**
     * Verify an Ed25519 detached signature.
     *
     * Returns true only if libsodium reports success. Length checks are
     * performed up-front so callers get a structured failure for malformed
     * input instead of a cryptic native error.
     */
    fun verify(
        sodium: LazySodiumAndroid,
        publicKey: ByteArray,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != Sign.PUBLICKEYBYTES) return false
        if (signature.size != Sign.BYTES) return false
        return sodium.cryptoSignVerifyDetached(signature, signedBytes, signedBytes.size, publicKey)
    }

    /** Convenience: verify a [GenesisIssuance] against its embedded issuer key. */
    fun verify(sodium: LazySodiumAndroid, cert: GenesisIssuance): Boolean =
        verify(sodium, cert.issuer, cert.signedBytes(), cert.signature)

    /** Convenience: verify a [Transfer] against its embedded sender key. */
    fun verify(sodium: LazySodiumAndroid, t: Transfer): Boolean =
        verify(sodium, t.sender, t.signedBytes(), t.signature)
}
