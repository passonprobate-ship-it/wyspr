package com.wyspr.core.database

import com.wyspr.core.database.entities.TrustEdgeEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the [WysprDatabase] interface contract using [FakeDatabase].
 *
 * Every implementation — real (SQLCipher) or fake — must satisfy these
 * invariants. Tests that involve actual Room / SQLCipher belong in
 * an instrumented test suite; this class covers the protocol contract
 * only.
 */
class WysprDatabaseContractTest {

    private lateinit var db: FakeDatabase

    @Before
    fun setUp() {
        db = FakeDatabase()
    }

    // ── open / close / isOpen ─────────────────────────────────────────

    @Test
    fun `new database is not open`() {
        assertFalse(db.isOpen)
    }

    @Test
    fun `open sets isOpen true`() = runTest {
        db.open()
        assertTrue(db.isOpen)
    }

    @Test
    fun `close sets isOpen false`() = runTest {
        db.open()
        db.close()
        assertFalse(db.isOpen)
    }

    @Test
    fun `wipe sets isOpen false`() = runTest {
        db.open()
        db.wipe()
        assertFalse(db.isOpen)
    }

    @Test
    fun `reopen after close`() = runTest {
        db.open()
        db.close()
        assertFalse(db.isOpen)
        db.open()
        assertTrue(db.isOpen)
    }

    @Test
    fun `reopen after wipe`() = runTest {
        db.open()
        db.wipe()
        assertFalse(db.isOpen)
        db.open()
        assertTrue(db.isOpen)
    }

    // ── DAO accessors throw before open ───────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun `trustEdgeDao throws before open`() {
        db.trustEdgeDao
    }

    @Test(expected = IllegalStateException::class)
    fun `revocationDao throws before open`() {
        db.revocationDao
    }

    @Test(expected = IllegalStateException::class)
    fun `accountDao throws before open`() {
        db.accountDao
    }

    @Test(expected = IllegalStateException::class)
    fun `currencyEnvelopeDao throws before open`() {
        db.currencyEnvelopeDao
    }

    @Test(expected = IllegalStateException::class)
    fun `communityMembershipDao throws before open`() {
        db.communityMembershipDao
    }

    @Test(expected = IllegalStateException::class)
    fun `messageDao throws before open`() {
        db.messageDao
    }

    // ── DAO accessors throw after close ───────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun `trustEdgeDao throws after close`() = runTest {
        db.open()
        db.close()
        db.trustEdgeDao
    }

    @Test(expected = IllegalStateException::class)
    fun `revocationDao throws after close`() = runTest {
        db.open()
        db.close()
        db.revocationDao
    }

    @Test(expected = IllegalStateException::class)
    fun `accountDao throws after close`() = runTest {
        db.open()
        db.close()
        db.accountDao
    }

    // ── wipe clears state ─────────────────────────────────────────────

    @Test
    fun `wipe clears trust edges`() = runTest {
        db.open()
        db.trustEdgeDao.upsert(
            TrustEdgeEntity(
                fromPub = ByteArray(32) { 0xAA.toByte() },
                toPub = ByteArray(32) { 0xBB.toByte() },
                vouchLevel = "PROVISIONAL",
                establishedAt = 1000L,
                certBlob = ByteArray(10),
                certSigner = ByteArray(32),
            )
        )
        assertEquals(1, db.trustEdgeDao.count())
        db.wipe()
        db.open()
        assertEquals(0, db.trustEdgeDao.count())
    }

    @Test
    fun `wipe clears revocation`() = runTest {
        db.open()
        db.revocationDao.upsert(
            com.wyspr.core.database.entities.RevocationEntity(
                issuerPub = ByteArray(32) { 0x11.toByte() },
                targetPub = ByteArray(32) { 0x22.toByte() },
                issuedAt = 2000L,
                reasonCode = "COMPROMISED",
                signature = ByteArray(64),
                communityId = ByteArray(32) { 0x33.toByte() },
            )
        )
        assertEquals(1, db.revocationDao.all().size)
        db.wipe()
        db.open()
        assertEquals(0, db.revocationDao.all().size)
    }

    // ── DAOs work after open ──────────────────────────────────────────

    @Test
    fun `trustEdgeDao upsert and all work after open`() = runTest {
        db.open()
        val edge = TrustEdgeEntity(
            fromPub = ByteArray(32) { 0x11.toByte() },
            toPub = ByteArray(32) { 0x22.toByte() },
            vouchLevel = "FULL",
            establishedAt = 3000L,
            certBlob = ByteArray(20),
            certSigner = ByteArray(32),
        )
        db.trustEdgeDao.upsert(edge)
        val all = db.trustEdgeDao.all()
        assertEquals(1, all.size)
        assertTrue(all[0].fromPub.contentEquals(edge.fromPub))
    }

    @Test
    fun `two open calls are idempotent`() = runTest {
        db.open()
        db.open()
        assertTrue(db.isOpen)
        db.trustEdgeDao.count() // does not throw
    }

    @Test
    fun `two close calls are idempotent`() = runTest {
        db.open()
        db.close()
        db.close()
        assertFalse(db.isOpen)
    }

    @Test
    fun `two wipe calls are idempotent`() = runTest {
        db.open()
        db.wipe()
        db.wipe()
        assertFalse(db.isOpen)
    }
}
