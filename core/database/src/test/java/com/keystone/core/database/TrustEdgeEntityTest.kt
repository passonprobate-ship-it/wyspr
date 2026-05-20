package com.keystone.core.database

import com.keystone.core.database.entities.TrustEdgeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustEdgeEntityTest {

    private val pubA = ByteArray(32) { 0xAA.toByte() }
    private val pubB = ByteArray(32) { 0xBB.toByte() }
    private val certBlob = ByteArray(100) { 0x10.toByte() }
    private val signer = ByteArray(32) { 0xCC.toByte() }
    private val now = 1_000_000L

    @Test
    fun `equals reflexive`() {
        val e = entity()
        assertEquals(e, e)
    }

    @Test
    fun `equals symmetric identical fields`() {
        val a = entity()
        val b = entity()
        assertEquals(a, b)
        assertEquals(b, a)
    }

    @Test
    fun `equals different fromPub`() {
        val a = entity()
        val b = entity(fromPub = ByteArray(32) { 0xDD.toByte() })
        assertNotEquals(a, b)
    }

    @Test
    fun `equals different toPub`() {
        val a = entity()
        val b = entity(toPub = ByteArray(32) { 0xEE.toByte() })
        assertNotEquals(a, b)
    }

    @Test
    fun `equals different vouchLevel`() {
        val a = entity()
        val b = entity(vouchLevel = "FULL")
        assertNotEquals(a, b)
    }

    @Test
    fun `equals different establishedAt`() {
        val a = entity()
        val b = entity(establishedAt = 999L)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals different certBlob`() {
        val a = entity()
        val b = entity(certBlob = ByteArray(50) { 0xFF.toByte() })
        assertNotEquals(a, b)
    }

    @Test
    fun `equals different certSigner`() {
        val a = entity()
        val b = entity(certSigner = ByteArray(32) { 0xDD.toByte() })
        assertNotEquals(a, b)
    }

    @Test
    fun `equals peerOnion null vs non-null`() {
        val a = entity(peerOnion = null)
        val b = entity(peerOnion = "abcdefg.onion")
        assertNotEquals(a, b)
    }

    @Test
    fun `equals peerOnion same value`() {
        val a = entity(peerOnion = "myhost.onion")
        val b = entity(peerOnion = "myhost.onion")
        assertEquals(a, b)
    }

    @Test
    fun `hashCode consistent for same fields`() {
        val a = entity()
        val b = entity()
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode different for different fromPub`() {
        val a = entity()
        val b = entity(fromPub = ByteArray(32) { 0x01.toByte() })
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode includes peerOnion`() {
        val a = entity(peerOnion = null)
        val b = entity(peerOnion = "x.onion")
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `constructor holds field values`() {
        val e = entity(certBlob = certBlob, certSigner = signer)
        assertTrue(e.fromPub.contentEquals(pubA))
        assertTrue(e.toPub.contentEquals(pubB))
        assertEquals("PROVISIONAL", e.vouchLevel)
        assertEquals(now, e.establishedAt)
        assertTrue(e.certBlob.contentEquals(certBlob))
        assertTrue(e.certSigner.contentEquals(signer))
        assertEquals("peer.onion", e.peerOnion)
    }

    @Test
    fun `peerOnion defaults to null`() {
        val e = TrustEdgeEntity(
            fromPub = pubA,
            toPub = pubB,
            vouchLevel = "PROVISIONAL",
            establishedAt = now,
            certBlob = certBlob,
            certSigner = signer,
        )
        assertEquals(null, e.peerOnion)
    }

    private val defaultCertBlob = ByteArray(0)
    private val defaultSigner = ByteArray(32) { 0xBB.toByte() }

    private fun entity(
        fromPub: ByteArray = pubA,
        toPub: ByteArray = pubB,
        vouchLevel: String = "PROVISIONAL",
        establishedAt: Long = now,
        certBlob: ByteArray = defaultCertBlob,
        certSigner: ByteArray = defaultSigner,
        peerOnion: String? = "peer.onion",
    ) = TrustEdgeEntity(
        fromPub = fromPub,
        toPub = toPub,
        vouchLevel = vouchLevel,
        establishedAt = establishedAt,
        certBlob = certBlob,
        certSigner = certSigner,
        peerOnion = peerOnion,
    )
}
