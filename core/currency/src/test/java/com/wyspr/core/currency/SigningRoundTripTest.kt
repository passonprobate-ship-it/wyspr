package com.wyspr.core.currency

import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end signing test using JVM stdlib Ed25519 (Java 15+). Verifies
 * that the bytes [GenesisIssuance.signedBytes] and [Transfer.signedBytes]
 * produce are valid Ed25519 inputs and round-trip cleanly through sign +
 * verify. The production path swaps stdlib for libsodium (verify) and
 * the hardware keystore (sign); both speak the same Ed25519, so this
 * test demonstrates the canonical bytes are signable.
 */
class SigningRoundTripTest {

    @Test fun ed25519_signs_and_verifies_genesis_signed_bytes() {
        val (pub, priv) = newEd25519KeyPair()
        val pubRaw = extractRawEd25519PublicKey(pub.encoded)

        val signedBytes = GenesisIssuance.signedBytesOf(
            community = ByteArray(32) { 0xAA.toByte() },
            issuer = pubRaw,
            recipients = listOf(
                GenesisIssuance.Recipient(ByteArray(32) { 0x11 }, 1_000L),
            ),
            totalSupply = 1_000L,
            issuedAt = 1_700_000_000L,
            nonce = ByteArray(16) { 0x33 },
        )

        val sig = ed25519Sign(priv.encoded, signedBytes)
        assertEquals(64, sig.size)
        assertTrue(ed25519Verify(pub.encoded, signedBytes, sig))

        // Tamper a single byte in the signed bytes — verify must fail.
        val tampered = signedBytes.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        assertFalse(ed25519Verify(pub.encoded, tampered, sig))
    }

    @Test fun ed25519_signs_and_verifies_transfer_signed_bytes() {
        val (pub, priv) = newEd25519KeyPair()
        val pubRaw = extractRawEd25519PublicKey(pub.encoded)

        val signedBytes = Transfer.signedBytesOf(
            community = ByteArray(32) { 0xAA.toByte() },
            sender = pubRaw,
            recipient = ByteArray(32) { 0x22 },
            amount = 250L,
            seq = 7L,
            memoHash = ByteArray(32) { 0x55 },
            issuedAt = 1_700_000_000L,
            nonce = ByteArray(16) { 0x33 },
        )

        val sig = ed25519Sign(priv.encoded, signedBytes)
        assertTrue(ed25519Verify(pub.encoded, signedBytes, sig))
        // Swap the signature for a different key's signature — must fail.
        val (_, otherPriv) = newEd25519KeyPair()
        val otherSig = ed25519Sign(otherPriv.encoded, signedBytes)
        assertFalse(ed25519Verify(pub.encoded, signedBytes, otherSig))
    }

    private fun newEd25519KeyPair(): Pair<java.security.PublicKey, java.security.PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        return kp.public to kp.private
    }

    private fun ed25519Sign(privEncoded: ByteArray, message: ByteArray): ByteArray {
        val priv = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(privEncoded))
        val s = Signature.getInstance("Ed25519")
        s.initSign(priv)
        s.update(message)
        return s.sign()
    }

    private fun ed25519Verify(
        pubEncoded: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val pub = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(pubEncoded))
        val s = Signature.getInstance("Ed25519")
        s.initVerify(pub)
        s.update(message)
        return s.verify(signature)
    }

    /**
     * Extract the raw 32-byte Ed25519 public key from a SubjectPublicKeyInfo
     * encoding. The X.509 wrapper is fixed-format: the last 32 bytes are
     * the key bytes themselves.
     */
    private fun extractRawEd25519PublicKey(spki: ByteArray): ByteArray =
        spki.copyOfRange(spki.size - 32, spki.size)
}
