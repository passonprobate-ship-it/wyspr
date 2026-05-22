package com.keystone.core.crypto

import java.io.ByteArrayOutputStream

/**
 * Minimal deterministic CBOR (RFC 8949 §4.2.1) encoder/decoder.
 *
 * Lives in core:crypto because both core:trust (handshake QR) and
 * core:currency (Gem envelopes) need the same byte-stable encoding for
 * signature inputs. PROTOCOLS.md §1: every signed envelope encodes via
 * this codec; if the bytes differ across devices, signatures break.
 *
 * Covers exactly what Keystone envelopes need:
 *
 *   - Major type 0 — unsigned integers (versions, amounts, seq, timestamps, tags)
 *   - Major type 2 — byte strings (keys, signatures, nonces, hashes)
 *   - Major type 4 — definite-length arrays (the fixed tuple of envelope fields)
 *   - Major type 7 / simple 22 — null (optional fields)
 *
 * Intentionally rejects:
 *
 *   - Indefinite-length items
 *   - Non-shortest integer / length encodings (e.g. encoding 5 as a 1-byte
 *     follow rather than inline)
 *   - Text strings, negative integers, floats, tags, maps, simple values
 *     other than null
 *
 * The reader enforces all of these so that a single envelope on the wire
 * has exactly one valid byte representation. If two devices encode the
 * same envelope content, the bytes — and therefore the signature — match
 * exactly. PROTOCOLS.md §1 and CURRENCY.md §7.
 */
object Cbor {

    private const val MT_UINT: Int = 0
    private const val MT_BSTR: Int = 2
    private const val MT_ARRAY: Int = 4
    private const val MT_PRIMITIVE: Int = 7
    private const val AI_FOLLOW_1: Int = 24
    private const val AI_FOLLOW_2: Int = 25
    private const val AI_FOLLOW_4: Int = 26
    private const val AI_FOLLOW_8: Int = 27
    private const val SIMPLE_NULL: Int = 22

    // --------------------------------------------------------------------
    // Encoder
    // --------------------------------------------------------------------

    class Writer {
        private val out = ByteArrayOutputStream()

        fun uint(value: Long): Writer = apply {
            require(value >= 0) { "CBOR uint cannot be negative: $value" }
            writeHead(MT_UINT, value)
        }

        fun bytes(value: ByteArray): Writer = apply {
            writeHead(MT_BSTR, value.size.toLong())
            out.write(value)
        }

        fun arrayHeader(count: Int): Writer = apply {
            require(count >= 0) { "negative array length" }
            writeHead(MT_ARRAY, count.toLong())
        }

        fun nullValue(): Writer = apply {
            out.write((MT_PRIMITIVE shl 5) or SIMPLE_NULL)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeHead(majorType: Int, value: Long) {
            require(value >= 0)
            val mt = majorType shl 5
            when {
                value < 24L -> out.write(mt or value.toInt())
                value < 0x100L -> {
                    out.write(mt or AI_FOLLOW_1)
                    out.write(value.toInt() and 0xFF)
                }
                value < 0x10000L -> {
                    out.write(mt or AI_FOLLOW_2)
                    out.write(((value ushr 8) and 0xFF).toInt())
                    out.write((value and 0xFF).toInt())
                }
                value < 0x1_0000_0000L -> {
                    out.write(mt or AI_FOLLOW_4)
                    for (i in 3 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
                }
                else -> {
                    out.write(mt or AI_FOLLOW_8)
                    for (i in 7 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // Decoder
    // --------------------------------------------------------------------

    class Reader(private val input: ByteArray, private var pos: Int = 0) {

        fun remaining(): Int = input.size - pos

        fun requireEnd() {
            if (pos != input.size) error("Trailing CBOR bytes: ${input.size - pos}")
        }

        fun uint(): Long {
            val head = readByte()
            val majorType = (head ushr 5) and 0x7
            require(majorType == MT_UINT) { "Expected uint, got major type $majorType" }
            return readLength(head and 0x1F)
        }

        fun bytes(): ByteArray {
            val head = readByte()
            val majorType = (head ushr 5) and 0x7
            require(majorType == MT_BSTR) { "Expected byte string, got major type $majorType" }
            val len = readLength(head and 0x1F).toInt()
            require(len >= 0 && pos + len <= input.size) { "byte string overruns input" }
            val slice = input.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun bytesOrNull(): ByteArray? {
            val head = peekByte()
            return if (head == ((MT_PRIMITIVE shl 5) or SIMPLE_NULL)) {
                pos++ // consume the null
                null
            } else {
                bytes()
            }
        }

        fun arrayHeader(): Int {
            val head = readByte()
            val majorType = (head ushr 5) and 0x7
            require(majorType == MT_ARRAY) { "Expected array, got major type $majorType" }
            val len = readLength(head and 0x1F)
            require(len in 0..Int.MAX_VALUE.toLong()) { "array length out of range" }
            return len.toInt()
        }

        private fun readByte(): Int {
            require(pos < input.size) { "Unexpected end of CBOR input" }
            return input[pos++].toInt() and 0xFF
        }

        private fun peekByte(): Int {
            require(pos < input.size) { "Unexpected end of CBOR input" }
            return input[pos].toInt() and 0xFF
        }

        private fun readLength(additionalInfo: Int): Long {
            return when (additionalInfo) {
                in 0..23 -> additionalInfo.toLong()
                AI_FOLLOW_1 -> {
                    val v = readByte().toLong()
                    require(v >= 24L) {
                        "Non-shortest CBOR encoding: 1-byte follow with value < 24"
                    }
                    v
                }
                AI_FOLLOW_2 -> {
                    val v = (readByte().toLong() shl 8) or readByte().toLong()
                    require(v >= 0x100L) {
                        "Non-shortest CBOR encoding: 2-byte follow with value < 256"
                    }
                    v
                }
                AI_FOLLOW_4 -> {
                    var v = 0L
                    for (i in 0 until 4) v = (v shl 8) or readByte().toLong()
                    require(v >= 0x10000L) {
                        "Non-shortest CBOR encoding: 4-byte follow with value < 65536"
                    }
                    v
                }
                AI_FOLLOW_8 -> {
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or readByte().toLong()
                    require(v >= 0x1_0000_0000L) {
                        "Non-shortest CBOR encoding: 8-byte follow with value < 2^32"
                    }
                    require(v >= 0) { "CBOR uint > Long.MAX_VALUE is unsupported" }
                    v
                }
                else -> error("Reserved or indefinite-length CBOR not allowed (ai=$additionalInfo)")
            }
        }
    }

    inline fun encode(block: Writer.() -> Unit): ByteArray =
        Writer().apply(block).toByteArray()

    inline fun <T> decode(bytes: ByteArray, block: Reader.() -> T): T {
        val r = Reader(bytes)
        val v = r.block()
        r.requireEnd()
        return v
    }
}
