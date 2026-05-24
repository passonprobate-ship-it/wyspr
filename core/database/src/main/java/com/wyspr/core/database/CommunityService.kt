package com.wyspr.core.database

import com.wyspr.core.database.entities.CommunityMembershipEntity
import com.wyspr.core.identity.CommunityId
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages community membership. v0 enforces one-community-per-device
 * via the API (the schema permits many); the future invitation flow
 * will let a device hold several memberships at once.
 *
 *   - [activeCommunityIdOrNull] — current community, or null if the
 *     device has never founded or joined one.
 *   - [foundNewCommunity] — deterministically derive a fresh
 *     CommunityId from the founder's pub + a random nonce + the wall
 *     clock, persist it, and mark this device as Founder.
 *   - [switchCommunity] — escape-hatch for v0 testing: paste a
 *     community ID from another device and treat it as active. Real
 *     joining (via an InvitationCertificate) replaces this in a
 *     future sprint.
 *
 * The wall clock is mixed into the SHA-256 only as a tie-breaker
 * source of entropy; a device that founds two communities a
 * microsecond apart still gets distinct IDs because the 16-byte
 * nonce dominates.
 */
class CommunityService(
    private val database: WysprDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {

    suspend fun activeCommunityIdOrNull(): CommunityId? {
        ensureOpen()
        return database.communityMembershipDao.firstOrNull()?.let { CommunityId(it.communityId) }
    }

    /**
     * Generate, persist, and return a new community whose founder is
     * the supplied identity. Replaces any existing membership in v0
     * (one-community-per-device); the future flow will append instead.
     */
    suspend fun foundNewCommunity(founderPub: ByteArray): CommunityId {
        require(founderPub.size == FOUNDER_KEY_LENGTH) {
            "founder key must be $FOUNDER_KEY_LENGTH bytes"
        }
        ensureOpen()
        val now = clock()
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val communityId = deriveCommunityId(founderPub, nonce, now)
        // v0 single-membership rule: atomic wipe + insert.
        database.communityMembershipDao.replaceAll(
            CommunityMembershipEntity(
                communityId = communityId.bytes,
                foundedAt = now,
                isFounder = true,
                displayName = null,
            )
        )
        return communityId
    }

    /**
     * Replace the active community with the supplied one. The
     * accompanying [isFounder] flag is the caller's claim; v0 has no
     * way to prove it cryptographically, so the value is advisory.
     * Real joins via invitation will set it false; manual switch in
     * Settings sets it false too.
     */
    suspend fun switchCommunity(communityId: CommunityId, isFounder: Boolean = false) {
        ensureOpen()
        database.communityMembershipDao.replaceAll(
            CommunityMembershipEntity(
                communityId = communityId.bytes,
                foundedAt = clock(),
                isFounder = isFounder,
                displayName = null,
            )
        )
    }

    /** Drop all memberships. Called by the reset flow. */
    suspend fun clearAll() {
        ensureOpen()
        database.communityMembershipDao.deleteAll()
    }

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }

    private fun deriveCommunityId(founderPub: ByteArray, nonce: ByteArray, now: Long): CommunityId {
        // Domain-separated SHA-256. Future versions can swap to BLAKE2s
        // alongside the rest of Wyspr's hash usage when the libsodium
        // hash surface is wired up; the domain prefix bumps to v2 then.
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN)
        md.update(founderPub)
        md.update(nonce)
        md.update(longBe(now))
        val digest = md.digest()
        return CommunityId(digest)
    }

    private fun longBe(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - 8 * i)).toByte()
        return out
    }

    private companion object {
        const val FOUNDER_KEY_LENGTH = 32
        const val NONCE_BYTES = 16
        val DOMAIN = "WYSPR-COMMUNITY/v1".encodeToByteArray()
    }
}
