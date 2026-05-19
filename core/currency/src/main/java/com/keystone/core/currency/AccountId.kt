package com.keystone.core.currency

/**
 * Value-equality wrapper around a 32-byte Ed25519 identity public key.
 *
 * Used as the key type in [LedgerReducer.State] maps. A raw [ByteArray]
 * cannot serve as a Map/Set key because its equals/hashCode are
 * reference-based — two arrays with identical content would hash to
 * different buckets and compare unequal. This wrapper fixes that.
 *
 * The constructor defensively copies its input so a caller mutating
 * their buffer cannot corrupt a live ledger snapshot. [bytes] likewise
 * returns a fresh copy on every read.
 */
class AccountId(bytes: ByteArray) {

    private val cached: ByteArray = bytes.copyOf()

    init {
        require(cached.size == LENGTH) { "AccountId must be $LENGTH bytes, got ${cached.size}" }
    }

    val bytes: ByteArray get() = cached.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountId) return false
        return cached.contentEquals(other.cached)
    }

    override fun hashCode(): Int = cached.contentHashCode()

    /**
     * Short hex preview useful in test failure messages and logs. Never
     * the full key — logs that would tie a key to a device are an
     * exfiltration risk on a compromised log pipeline.
     */
    override fun toString(): String {
        val hex = cached.take(4).joinToString("") { "%02x".format(it) }
        return "AccountId($hex…)"
    }

    companion object {
        const val LENGTH: Int = 32
    }
}
