package com.wyspr.core.identity

import com.wyspr.core.crypto.Blake2s

/**
 * 32-byte content-addressed identifier for a private group thread.
 *
 * Derivation (GROUPS.md §Concepts):
 *
 *     groupId = BLAKE2s-256(
 *         "WYSPR/v1/group-id"
 *         || creatorPub               // 32 bytes Ed25519
 *         || name.encodeToByteArray()
 *         || createdAt.toBigEndian64()
 *     )
 *
 * Two devices that observe the same creation event compute the same
 * id. A member receiving their `GroupMembership` cert can recompute
 * this and verify the cert's [GroupMembership.groupId] matches its
 * declared fields — no separate "create group" gossip is needed.
 *
 * Renaming the group changes its identity; the v1 design intentionally
 * doesn't support rename. Per-device aliases are stored as
 * `GroupEntity.localNickname`.
 */
@JvmInline
value class GroupId(val bytes: ByteArray) {
    init {
        require(bytes.size == LENGTH) { "group id must be $LENGTH bytes" }
    }

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val LENGTH = 32
        private const val DOMAIN = "WYSPR/v1/group-id"

        /**
         * Deterministically derive the group id from its creation
         * material. Callers MUST use the exact bytes the creator
         * used — name encoding is UTF-8 via [String.encodeToByteArray]
         * and [createdAt] is encoded big-endian over 8 bytes.
         */
        fun derive(
            creatorPub: PublicKey,
            name: String,
            createdAt: Long,
        ): GroupId {
            val domain = DOMAIN.encodeToByteArray()
            val nameBytes = name.encodeToByteArray()
            val ts = ByteArray(8) { i ->
                ((createdAt ushr (56 - 8 * i)) and 0xFF).toByte()
            }
            // Concat then single-shot hash. Blake2s.newDigest()'s return
            // type leaks into the API boundary (noise-java class isn't
            // exposed through :core:crypto), so we stick to the byte[]
            // overload here. Group-id inputs are small (~80-300 bytes).
            val input = ByteArray(domain.size + creatorPub.bytes.size + nameBytes.size + ts.size)
            var p = 0
            System.arraycopy(domain, 0, input, p, domain.size); p += domain.size
            System.arraycopy(creatorPub.bytes, 0, input, p, creatorPub.bytes.size); p += creatorPub.bytes.size
            System.arraycopy(nameBytes, 0, input, p, nameBytes.size); p += nameBytes.size
            System.arraycopy(ts, 0, input, p, ts.size)
            return GroupId(Blake2s.digest(input))
        }
    }
}
