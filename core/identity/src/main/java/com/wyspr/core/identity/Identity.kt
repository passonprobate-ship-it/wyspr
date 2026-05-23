package com.wyspr.core.identity

/**
 * A Wyspr identity. The private key never leaves the hardware keystore;
 * this class only carries the public material and the keystore alias.
 */
data class Identity(
    val publicKey: PublicKey,
    val keystoreAlias: String,
) {
    val fingerprint: Fingerprint get() = publicKey.fingerprint
}

/** Ed25519 public key. 32 bytes; not nullable, not empty. */
@JvmInline
value class PublicKey(val bytes: ByteArray) {
    init {
        require(bytes.size == LENGTH) { "Ed25519 public key must be $LENGTH bytes" }
    }

    val fingerprint: Fingerprint get() = Fingerprint.of(bytes)

    companion object { const val LENGTH = 32 }
}

/**
 * Human-comparable identity fingerprint. SECURITY-MODEL.md §2.
 *
 * First 16 bytes of SHA-256(pub || "WYSPR-FP"), rendered as five
 * groups of base32 (no padding) separated by spaces.
 *
 * Example: K5T2N AB3FR HJ8MQ XYZ4P 7VN2K
 */
@JvmInline
value class Fingerprint private constructor(val text: String) {

    override fun toString(): String = text

    companion object {
        private const val DOMAIN = "WYSPR-FP"
        private const val FP_BYTES = 16

        fun of(publicKey: ByteArray): Fingerprint {
            require(publicKey.size == PublicKey.LENGTH)
            val digest = sha256(publicKey + DOMAIN.encodeToByteArray())
            val truncated = digest.copyOfRange(0, FP_BYTES)
            return Fingerprint(formatBase32Groups(truncated, groupSize = 5, groupCount = 5))
        }

        private fun sha256(input: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(input)

        private fun formatBase32Groups(
            input: ByteArray,
            groupSize: Int,
            groupCount: Int,
        ): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            // RFC 4648 base32, no padding.
            val bits = StringBuilder()
            for (b in input) bits.append(b.toInt().and(0xFF).toString(2).padStart(8, '0'))
            val sb = StringBuilder()
            var i = 0
            while (i + 5 <= bits.length) {
                val v = bits.substring(i, i + 5).toInt(2)
                sb.append(alphabet[v])
                i += 5
            }
            val raw = sb.toString().take(groupSize * groupCount)
            return raw.chunked(groupSize).joinToString(" ")
        }
    }
}
