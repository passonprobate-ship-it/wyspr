package com.wyspr.core.trust

import com.wyspr.core.crypto.Cbor

/**
 * Wire messages for the key-rotation anti-entropy sub-protocol.
 *
 * Runs on the same Link as revocation sync, using a separate set of
 * CBOR type tags so the receiver can dispatch to the right decoder.
 *
 * Message shapes (all definite-length CBOR arrays):
 *
 *   HaveSet (0x34)  —  [0x34, [[oldPub, newPub], ...]]
 *   Want    (0x35)  —  [0x35, [[oldPub, newPub], ...]]
 *   Push    (0x36)  —  [0x36, [wireCert, ...]]
 */
sealed interface KeyRotationSyncMessage {

    data class HaveSet(val pairs: List<Pair<ByteArray, ByteArray>>) : KeyRotationSyncMessage

    data class Want(val pairs: List<Pair<ByteArray, ByteArray>>) : KeyRotationSyncMessage

    data class Push(val certs: List<ByteArray>) : KeyRotationSyncMessage

    companion object {
        const val TAG_HAVE_SET: Int = 0x34
        const val TAG_WANT: Int = 0x35
        const val TAG_PUSH: Int = 0x36

        const val MAX_FRAME_BYTES: Int = 16_384

        fun encode(msg: KeyRotationSyncMessage): ByteArray = Cbor.encode {
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

        fun decode(bytes: ByteArray): KeyRotationSyncMessage = Cbor.decode(bytes) {
            val len = arrayHeader()
            val tag = uint().toIntChecked()
            when (tag) {
                TAG_HAVE_SET -> {
                    require(len == 2) { "KeyRotationHaveSet must have 2 fields, got $len" }
                    HaveSet(readPairs(this))
                }
                TAG_WANT -> {
                    require(len == 2) { "KeyRotationWant must have 2 fields, got $len" }
                    Want(readPairs(this))
                }
                TAG_PUSH -> {
                    require(len == 2) { "KeyRotationPush must have 2 fields, got $len" }
                    val n = arrayHeader()
                    val certs = ArrayList<ByteArray>(n)
                    repeat(n) { certs.add(bytes()) }
                    Push(certs)
                }
                else -> error("Unknown key rotation sync message tag: 0x${tag.toString(16)}")
            }
        }

        private fun writePairs(w: Cbor.Writer, pairs: List<Pair<ByteArray, ByteArray>>) {
            w.arrayHeader(pairs.size)
            for ((oldPub, newPub) in pairs) {
                w.arrayHeader(2)
                w.bytes(oldPub)
                w.bytes(newPub)
            }
        }

        private fun readPairs(r: Cbor.Reader): List<Pair<ByteArray, ByteArray>> {
            val n = r.arrayHeader()
            val out = ArrayList<Pair<ByteArray, ByteArray>>(n)
            repeat(n) {
                val pairLen = r.arrayHeader()
                require(pairLen == 2) { "Key rotation pair must have 2 fields, got $pairLen" }
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
