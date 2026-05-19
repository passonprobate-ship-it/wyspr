package com.keystone.core.sync

import com.keystone.core.crypto.Cbor

/**
 * The three sync wire messages. Anti-entropy session between two peers
 * is exactly:
 *
 *   1. Both peers send [HaveSet]
 *   2. Both peers send [Want] (peer.have − mine)
 *   3. Both peers send [Push] (the envelope rows the peer wanted)
 *
 * Messages are CBOR arrays prefixed with a 1-byte type tag so a single
 * `decode` can disambiguate. Field order is fixed and MUST match the
 * doc.
 *
 * PROTOCOLS.md §4 (anti-entropy). The currency-specific envelope shape
 * it carries lives in CURRENCY.md §7.
 */
sealed interface SyncMessage {
    data class HaveSet(val community: ByteArray, val keys: List<EnvelopeKey>) : SyncMessage
    data class Want(val community: ByteArray, val keys: List<EnvelopeKey>) : SyncMessage
    data class Push(val rows: List<EnvelopeRow>) : SyncMessage

    companion object {
        // Type tags. Stable on the wire — never reuse, never reorder.
        const val TAG_HAVE_SET: Int = 0x21
        const val TAG_WANT: Int = 0x22
        const val TAG_PUSH: Int = 0x23

        /** Single-frame size guard. PROTOCOLS.md §1: max 16,384 bytes. */
        const val MAX_FRAME_BYTES: Int = 16_384

        fun encode(msg: SyncMessage): ByteArray = Cbor.encode {
            when (msg) {
                is HaveSet -> {
                    arrayHeader(3)
                    uint(TAG_HAVE_SET.toLong())
                    bytes(msg.community)
                    writeKeys(this, msg.keys)
                }
                is Want -> {
                    arrayHeader(3)
                    uint(TAG_WANT.toLong())
                    bytes(msg.community)
                    writeKeys(this, msg.keys)
                }
                is Push -> {
                    arrayHeader(2)
                    uint(TAG_PUSH.toLong())
                    writeRows(this, msg.rows)
                }
            }
        }

        fun decode(bytes: ByteArray): SyncMessage = Cbor.decode(bytes) {
            val len = arrayHeader()
            val tag = uint().toIntChecked()
            when (tag) {
                TAG_HAVE_SET -> {
                    require(len == 3) { "HaveSet must have 3 fields, got $len" }
                    val community = bytes()
                    val keys = readKeys(this)
                    HaveSet(community, keys)
                }
                TAG_WANT -> {
                    require(len == 3) { "Want must have 3 fields, got $len" }
                    val community = bytes()
                    val keys = readKeys(this)
                    Want(community, keys)
                }
                TAG_PUSH -> {
                    require(len == 2) { "Push must have 2 fields, got $len" }
                    val rows = readRows(this)
                    Push(rows)
                }
                else -> error("Unknown sync message tag: 0x${tag.toString(16)}")
            }
        }

        private fun writeKeys(w: Cbor.Writer, keys: List<EnvelopeKey>) {
            w.arrayHeader(keys.size)
            for (k in keys) {
                w.arrayHeader(2)
                w.uint(k.typeTag.toLong())
                w.bytes(k.primaryKey)
            }
        }

        private fun readKeys(r: Cbor.Reader): List<EnvelopeKey> {
            val n = r.arrayHeader()
            val out = ArrayList<EnvelopeKey>(n)
            repeat(n) {
                val pairLen = r.arrayHeader()
                require(pairLen == 2) { "EnvelopeKey must have 2 fields, got $pairLen" }
                val tag = r.uint().toIntChecked()
                val pk = r.bytes()
                out.add(EnvelopeKey(tag, pk))
            }
            return out
        }

        private fun writeRows(w: Cbor.Writer, rows: List<EnvelopeRow>) {
            w.arrayHeader(rows.size)
            for (row in rows) {
                w.arrayHeader(5)
                w.bytes(row.community)
                w.uint(row.typeTag.toLong())
                w.bytes(row.primaryKey)
                w.bytes(row.body)
                w.uint(row.observedAt)
            }
        }

        private fun readRows(r: Cbor.Reader): List<EnvelopeRow> {
            val n = r.arrayHeader()
            val out = ArrayList<EnvelopeRow>(n)
            repeat(n) {
                val rowLen = r.arrayHeader()
                require(rowLen == 5) { "EnvelopeRow must have 5 fields, got $rowLen" }
                val community = r.bytes()
                val tag = r.uint().toIntChecked()
                val pk = r.bytes()
                val body = r.bytes()
                val observedAt = r.uint()
                out.add(EnvelopeRow(community, tag, pk, body, observedAt))
            }
            return out
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}
