package com.keystone.app.transport

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest

/**
 * Deterministic Tor HSv3 hidden-service key derivation from a 32-byte seed.
 *
 * The output byte arrays are byte-identical to the files Tor writes to a
 * `HiddenServiceDir` when it generates a key itself, so dropping them into
 * the directory before Tor starts pins the .onion address to one we
 * control instead of one Tor picks at random.
 *
 * File formats (tor-spec rend-spec-v3.txt §6, §A.2):
 * - `hs_ed25519_secret_key`: 32-byte ASCII header `== ed25519v1-secret: type0 ==`
 *   padded with three NULs to a 32-byte boundary, followed by the 64-byte
 *   expanded Ed25519 secret (RFC 8032 §5.1.5: SHA-512(seed) with the lower-32
 *   clamped).
 * - `hs_ed25519_public_key`: 32-byte ASCII header `== ed25519v1-public: type0 ==`
 *   padded with three NULs, followed by the 32-byte raw public key.
 *
 * .onion address (rend-spec-v3.txt §1.3):
 * `base32(pub || checksum || 0x03)`, lowercase, where
 * `checksum = SHA3-256(".onion checksum" || pub || 0x03)[0..2]`.
 */
internal object TorHsKey {

    data class Material(
        val secretKeyFile: ByteArray,
        val publicKeyFile: ByteArray,
        val onionAddress: String,
    )

    fun derive(seed: ByteArray): Material {
        require(seed.size == 32) { "HSv3 seed must be 32 bytes (got ${seed.size})" }

        val pub = ByteArray(PUB_KEY_BYTES)
        Ed25519.generatePublicKey(seed, 0, pub, 0)

        val expanded = MessageDigest.getInstance("SHA-512").digest(seed)
        check(expanded.size == EXPANDED_SECRET_BYTES)
        expanded[0] = (expanded[0].toInt() and 0xF8).toByte()
        expanded[31] = ((expanded[31].toInt() and 0x7F) or 0x40).toByte()

        val onion = onionAddress(pub)

        val secretFile = ByteArray(HEADER_BYTES + EXPANDED_SECRET_BYTES)
        System.arraycopy(SECRET_HEADER, 0, secretFile, 0, HEADER_BYTES)
        System.arraycopy(expanded, 0, secretFile, HEADER_BYTES, EXPANDED_SECRET_BYTES)
        expanded.fill(0)

        val publicFile = ByteArray(HEADER_BYTES + PUB_KEY_BYTES)
        System.arraycopy(PUBLIC_HEADER, 0, publicFile, 0, HEADER_BYTES)
        System.arraycopy(pub, 0, publicFile, HEADER_BYTES, PUB_KEY_BYTES)

        return Material(
            secretKeyFile = secretFile,
            publicKeyFile = publicFile,
            onionAddress = onion,
        )
    }

    private fun onionAddress(pub: ByteArray): String {
        val sha3 = SHA3Digest(256)
        sha3.update(CHECKSUM_DOMAIN, 0, CHECKSUM_DOMAIN.size)
        sha3.update(pub, 0, pub.size)
        sha3.update(byteArrayOf(HSV3_VERSION), 0, 1)
        val checksumFull = ByteArray(32)
        sha3.doFinal(checksumFull, 0)

        val payload = ByteArray(PUB_KEY_BYTES + 2 + 1).also {
            System.arraycopy(pub, 0, it, 0, PUB_KEY_BYTES)
            it[PUB_KEY_BYTES] = checksumFull[0]
            it[PUB_KEY_BYTES + 1] = checksumFull[1]
            it[PUB_KEY_BYTES + 2] = HSV3_VERSION
        }
        val onion = base32Lower(payload)
        check(onion.length == ONION_ADDRESS_LEN) {
            "HSv3 address must be $ONION_ADDRESS_LEN chars, got ${onion.length}"
        }
        return onion
    }

    private fun base32Lower(bytes: ByteArray): String {
        val sb = StringBuilder(((bytes.size * 8 + 4) / 5))
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(BASE32_ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }

    private const val PUB_KEY_BYTES = 32
    private const val EXPANDED_SECRET_BYTES = 64
    private const val HEADER_BYTES = 32
    private const val ONION_ADDRESS_LEN = 56
    private const val HSV3_VERSION: Byte = 0x03

    private val SECRET_HEADER: ByteArray = header("== ed25519v1-secret: type0 ==")
    private val PUBLIC_HEADER: ByteArray = header("== ed25519v1-public: type0 ==")
    private val CHECKSUM_DOMAIN: ByteArray = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()

    /**
     * Tor's HSv3 key files start with a 32-byte type tag: ASCII label,
     * padded to 32 bytes with NULs (never spaces). Tor's parser checks
     * the trailing bytes are 0x00 exactly.
     */
    private fun header(label: String): ByteArray {
        val ascii = label.toByteArray(Charsets.US_ASCII)
        require(ascii.size <= HEADER_BYTES) { "header too long: ${ascii.size}" }
        val out = ByteArray(HEADER_BYTES)
        System.arraycopy(ascii, 0, out, 0, ascii.size)
        return out
    }
}
