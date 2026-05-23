package com.wyspr.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * BLAKE2s-256 (RFC 7693) sanity checks. Cross-reference vectors are
 * taken from RFC 7693 Appendix B and the BLAKE2 reference site.
 *
 * The same byte-for-byte digest is the most important property here:
 * any drift means the BLE / WiFi Direct service UUID changes, peers
 * stop seeing each other, and there is no obvious failure mode at the
 * protocol level — the radio simply goes quiet.
 */
class Blake2sTest {

    @Test
    fun emptyInput_matchesRfc7693AppendixB() {
        // RFC 7693 Appendix B: BLAKE2s-256("") =
        //   69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9
        val out = Blake2s.digest(byteArrayOf())
        assertEquals(
            "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            out.toHex(),
        )
    }

    @Test
    fun abcInput_matchesReferenceVector() {
        // BLAKE2s-256("abc") =
        //   508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982
        val out = Blake2s.digest("abc".encodeToByteArray())
        assertEquals(
            "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            out.toHex(),
        )
    }

    @Test
    fun digestLength_isAlways32() {
        assertEquals(Blake2s.DIGEST_BYTES, Blake2s.digest(byteArrayOf()).size)
        assertEquals(Blake2s.DIGEST_BYTES, Blake2s.digest(ByteArray(1000)).size)
    }

    @Test
    fun domainSeparated_appendsDomain() {
        // digest(input, domain) must equal digest(input || domain.utf8).
        val input = byteArrayOf(0x01, 0x02, 0x03)
        val domain = "WYSPR-SVC"
        val combined = input + domain.encodeToByteArray()

        assertEquals(
            Blake2s.digest(combined).toHex(),
            Blake2s.digest(input, domain).toHex(),
        )
    }

    @Test
    fun differentDomains_diverge() {
        val input = byteArrayOf(0x01, 0x02, 0x03)
        val a = Blake2s.digest(input, "WYSPR-SVC").toHex()
        val b = Blake2s.digest(input, "WYSPR-CHAR").toHex()
        assert(a != b) { "domain separation collapsed: $a == $b" }
    }

    @Test
    fun newDigest_returnsFreshInstance() {
        val a = Blake2s.newDigest()
        val b = Blake2s.newDigest()
        assertNotSame(a, b, "digest instances must be independent")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
