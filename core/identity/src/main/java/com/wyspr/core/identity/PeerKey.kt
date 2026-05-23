package com.wyspr.core.identity

/**
 * Lightweight content-equal wrapper for a `ByteArray` so it can be
 * used as a `HashMap`/`HashSet` key or a Compose `key { }` value.
 *
 * Why this exists: `ByteArray.equals` is reference equality and
 * `ByteArray.hashCode` is identity-based, so `someMap[bytes]` only
 * ever hits on the exact same allocation. The previous workaround
 * across the codebase was `bytes.toList()`, which allocates a 32-
 * element `ArrayList<Byte>` of boxed `Byte` objects on every call —
 * ~600 bytes of garbage per usage. `PeerKey` is one small object;
 * content-equality is `contentEquals`/`contentHashCode`, which are
 * intrinsified.
 */
class PeerKey(val raw: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PeerKey && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String =
        raw.joinToString("") { "%02x".format(it) }
}

/** Sugar for the common construction pattern. */
fun ByteArray.asPeerKey(): PeerKey = PeerKey(this)
