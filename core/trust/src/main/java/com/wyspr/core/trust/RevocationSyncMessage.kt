package com.wyspr.core.trust

import com.wyspr.core.crypto.Cbor

/**
 * Wire messages for the revocation anti-entropy sub-protocol.
 *
 * A revocation sync round piggybacks on the same [Link] as the main
 * currency sync, but uses a separate set of CBOR type tags so the
 * receiver can dispatch to the right decoder.
 *
 * Message shapes (all definite-length CBOR arrays):
 *
 *   HaveSet (0x31)  —  [0x31, [[issuer, target], ...]]
 *   Want    (0x32)  —  [0x32, [[issuer, target], ...]]
 *   Push    (0x33)  —  [0x33, [wireCert, ...]]
 *
 * Each [issuer, target] pair is a 32-byte Ed25519 public key. In
 * HaveSet and Want the pair is the lightweight diff key; in Push the
 * value is the full wire encoding of the [RevocationCertificate] (7-
 * element CBOR array) so the receiver can decode, verify, and persist
 * without a separate DB lookup.
 *
 * PROTOCOLS.md §4 will carry the full spec when this sub-protocol is
 * folded in; for now the framing follows the same pattern as
 * [com.wyspr.core.sync.SyncMessage].
 */
sealed interface RevocationSyncMessage {

    /** All revocation pairs we have: `(issuerPub, targetPub)`. */
    data class HaveSet(val pairs: List<Pair<ByteArray, ByteArray>>) : RevocationSyncMessage

    /** Pairs we want from the peer: `(issuerPub, targetPub)`. */
    data class Want(val pairs: List<Pair<ByteArray, ByteArray>>) : RevocationSyncMessage

    /** Full wire-encoded [RevocationCertificate]s the peer asked for. */
    data class Push(val certs: List<ByteArray>) : RevocationSyncMessage

    companion object {
        const val TAG_HAVE_SET: Int = 0x31
        const val TAG_WANT: Int = 0x32
        const val TAG_PUSH: Int = 0x33

        const val MAX_FRAME_BYTES: Int = 16_384

        fun encode(msg: RevocationSyncMessage): ByteArray = Cbor.encode {
            when (msg) {
                is HaveSet -> {
                    arrayHeader(2)
                    uint(TAG_HAVE_SET.toLong())
                    writePairs(this, msg.pairs)
                }
                is Want -> {
                    arrayHeader(2)
                    uint(TAG_WANT.toLong())
                    writePairs(this, msg.pairs)
                }
                is Push -> {
                    arrayHeader(2)
                    uint(TAG_PUSH.toLong())
                    arrayHeader(msg.certs.size)
                    for (cert in msg.certs) bytes(cert)
                }
            }
        }

        fun decode(bytes: ByteArray): RevocationSyncMessage = Cbor.decode(bytes) {
            val len = arrayHeader()
            val tag = uint().toIntChecked()
            when (tag) {
                TAG_HAVE_SET -> {
                    require(len == 2) { "RevocationHaveSet must have 2 fields, got $len" }
                    HaveSet(readPairs(this))
                }
                TAG_WANT -> {
                    require(len == 2) { "RevocationWant must have 2 fields, got $len" }
                    Want(readPairs(this))
                }
                TAG_PUSH -> {
                    require(len == 2) { "RevocationPush must have 2 fields, got $len" }
                    val n = arrayHeader()
                    val certs = ArrayList<ByteArray>(n)
                    repeat(n) { certs.add(bytes()) }
                    Push(certs)
                }
                else -> error("Unknown revocation sync message tag: 0x${tag.toString(16)}")
            }
        }

        private fun writePairs(w: Cbor.Writer, pairs: List<Pair<ByteArray, ByteArray>>) {
            w.arrayHeader(pairs.size)
            for ((issuer, target) in pairs) {
                w.arrayHeader(2)
                w.bytes(issuer)
                w.bytes(target)
            }
        }

        private fun readPairs(r: Cbor.Reader): List<Pair<ByteArray, ByteArray>> {
            val n = r.arrayHeader()
            val out = ArrayList<Pair<ByteArray, ByteArray>>(n)
            repeat(n) {
                val pairLen = r.arrayHeader()
                require(pairLen == 2) { "Revocation pair must have 2 fields, got $pairLen" }
                out += r.bytes() to r.bytes()
            }
            return out
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}
