package com.keystone.core.database

import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.identity.CommunityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class CommunityServiceTest {

    private lateinit var db: FakeDatabase
    private lateinit var service: CommunityService
    private val founderPub = ByteArray(32) { 0xDD.toByte() }

    @Before
    fun setUp() {
        db = FakeDatabase()
        service = CommunityService(
            database = db,
            clock = { 1000L },
            random = fixedRandom(),
        )
    }

    @Test
    fun `activeCommunityIdOrNull returns null when no membership exists`() = runTest {
        assertNull(service.activeCommunityIdOrNull())
    }

    @Test
    fun `foundNewCommunity creates membership and returns communityId`() = runTest {
        val cid = service.foundNewCommunity(founderPub)
        assertNotNull(cid)
        assertEquals(32, cid.bytes.size)

        val active = service.activeCommunityIdOrNull()
        assertNotNull(active)
        assertTrue(active!!.bytes.contentEquals(cid.bytes))

        val memberships = db.communityMembershipDao.all()
        assertEquals(1, memberships.size)
        assertTrue(memberships[0].communityId.contentEquals(cid.bytes))
        assertEquals(true, memberships[0].isFounder)
        assertEquals(1000L, memberships[0].foundedAt)
    }

    @Test
    fun `foundNewCommunity auto-opens database`() = runTest {
        assertEquals(false, db.isOpen)
        service.foundNewCommunity(founderPub)
        assertEquals(true, db.isOpen)
    }

    @Test
    fun `foundNewCommunity replaces existing membership`() = runTest {
        service.foundNewCommunity(founderPub)
        val firstCid = service.activeCommunityIdOrNull()

        // Clock moved forward — second call produces a different community id
        // because the wall clock is mixed into the SHA-256.
        val service2 = CommunityService(db, clock = { 2000L }, random = SecureRandom())
        val secondCid = service2.foundNewCommunity(founderPub)
        assertTrue(!firstCid!!.bytes.contentEquals(secondCid.bytes))

        val memberships = db.communityMembershipDao.all()
        assertEquals(1, memberships.size)
        assertTrue(memberships[0].communityId.contentEquals(secondCid.bytes))
    }

    @Test
    fun `switchCommunity creates membership with isFounder false`() = runTest {
        val cid = CommunityId(ByteArray(32) { 0xEE.toByte() })
        service.switchCommunity(cid, isFounder = false)

        val active = service.activeCommunityIdOrNull()
        assertNotNull(active)
        assertTrue(active!!.bytes.contentEquals(cid.bytes))

        val memberships = db.communityMembershipDao.all()
        assertEquals(1, memberships.size)
        assertEquals(false, memberships[0].isFounder)
    }

    @Test
    fun `clearAll removes all memberships`() = runTest {
        service.foundNewCommunity(founderPub)
        assertEquals(1, db.communityMembershipDao.count())

        service.clearAll()
        assertEquals(0, db.communityMembershipDao.count())
        assertNull(service.activeCommunityIdOrNull())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `foundNewCommunity rejects wrong key length`() = runTest {
        val shortKey = ByteArray(16) { 0xFF.toByte() }
        service.foundNewCommunity(shortKey)
    }

    /** Deterministic [SecureRandom] that always produces the same nonce. */
    private fun fixedRandom(): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0x42.toByte())
        }
    }
}
