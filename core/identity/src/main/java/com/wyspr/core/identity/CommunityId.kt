package com.wyspr.core.identity

/**
 * The community a node belongs to. Derived from the genesis block hash.
 * A device that belongs to multiple communities holds multiple CommunityId
 * values; each one has its own database, service UUID, and trust graph.
 *
 * SECURITY-MODEL.md §6: cross-community discovery must not leak.
 */
@JvmInline
value class CommunityId(val bytes: ByteArray) {
    init {
        require(bytes.size == LENGTH) { "community id must be $LENGTH bytes" }
    }

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    companion object { const val LENGTH = 32 }
}
