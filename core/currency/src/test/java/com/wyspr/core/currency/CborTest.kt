package com.wyspr.core.currency

import com.wyspr.core.crypto.Cbor

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CborTest {

    @Test fun uint_inline_0_to_23() {
        for (v in 0L..23L) {
            val enc = Cbor.encode { uint(v) }
            assertEquals(1, enc.size, "value $v should be one byte")
            assertEquals(v.toInt(), enc[0].toInt() and 0xFF)
        }
    }

    @Test fun uint_1_byte_follow_24_to_255() {
        val enc = Cbor.encode { uint(255) }
        assertContentEquals(byteArrayOf(0x18.toByte(), 0xFF.toByte()), enc)
        assertEquals(255L, Cbor.decode(enc) { uint() })
    }

    @Test fun uint_2_byte_follow_256_to_65535() {
        val enc = Cbor.encode { uint(0xABCD) }
        assertContentEquals(byteArrayOf(0x19, 0xAB.toByte(), 0xCD.toByte()), enc)
        assertEquals(0xABCDL, Cbor.decode(enc) { uint() })
    }

    @Test fun uint_4_byte_follow() {
        val v = 0x01_00_00_00L
        val enc = Cbor.encode { uint(v) }
        assertContentEquals(byteArrayOf(0x1A, 0x01, 0x00, 0x00, 0x00), enc)
        assertEquals(v, Cbor.decode(enc) { uint() })
    }

    @Test fun uint_8_byte_follow() {
        val v = 0x01_00_00_00_00L
        val enc = Cbor.encode { uint(v) }
        assertEquals(9, enc.size)
        assertEquals(0x1B.toByte(), enc[0])
        assertEquals(v, Cbor.decode(enc) { uint() })
    }

    @Test fun decoder_rejects_nonshortest_1_byte() {
        // 0x18 0x05 is a 1-byte follow with value 5, which should have
        // been encoded inline. Strict decoder must reject.
        assertFailsWith<IllegalArgumentException> {
            Cbor.decode(byteArrayOf(0x18.toByte(), 0x05)) { uint() }
        }
    }

    @Test fun decoder_rejects_nonshortest_2_byte() {
        // 0x19 0x00 0xFF — 2-byte follow with value 255, should be 1-byte.
        assertFailsWith<IllegalArgumentException> {
            Cbor.decode(byteArrayOf(0x19, 0x00, 0xFF.toByte())) { uint() }
        }
    }

    @Test fun decoder_rejects_trailing_bytes() {
        assertFailsWith<IllegalStateException> {
            // 0x01 is uint(1); trailing byte 0xFF should be rejected.
            Cbor.decode(byteArrayOf(0x01, 0xFF.toByte())) { uint() }
        }
    }

    @Test fun bytes_round_trip_short_and_long() {
        val short = ByteArray(10) { it.toByte() }
        val mid = ByteArray(100) { it.toByte() }
        val long = ByteArray(300) { (it % 251).toByte() }
        for (sample in listOf(short, mid, long)) {
            val enc = Cbor.encode { bytes(sample) }
            assertContentEquals(sample, Cbor.decode(enc) { bytes() })
        }
    }

    @Test fun array_round_trip() {
        val enc = Cbor.encode {
            arrayHeader(3)
            uint(1)
            uint(2)
            uint(3)
        }
        val out = Cbor.decode(enc) {
            val n = arrayHeader()
            assertEquals(3, n)
            listOf(uint(), uint(), uint())
        }
        assertEquals(listOf(1L, 2L, 3L), out)
    }

    @Test fun null_value() {
        val enc = Cbor.encode { nullValue() }
        assertContentEquals(byteArrayOf(0xF6.toByte()), enc)
        val v = Cbor.decode(enc) { bytesOrNull() }
        assertNull(v)
    }

    @Test fun encoder_is_deterministic_across_calls() {
        val a = Cbor.encode {
            arrayHeader(2)
            bytes(ByteArray(4) { 0x42 })
            uint(0xABCD)
        }
        val b = Cbor.encode {
            arrayHeader(2)
            bytes(ByteArray(4) { 0x42 })
            uint(0xABCD)
        }
        assertContentEquals(a, b)
    }
}
