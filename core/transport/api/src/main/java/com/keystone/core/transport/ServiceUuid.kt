package com.keystone.core.transport

import com.keystone.core.crypto.Blake2s
import com.keystone.core.identity.CommunityId
import java.util.UUID

/**
 * Derive the BLE / WiFi Direct service identifier from the community id.
 *
 * PROTOCOLS.md §6:
 *     service_uuid = UUID(BLAKE2s(community_id || "KEYSTONE-SVC"))
 *
 * The first 16 bytes of BLAKE2s-256 become the 128-bit UUID. Big-endian
 * across `high` (offset 0..7) and `low` (8..15) matches `UUID(long, long)`
 * semantics so the same hash collation works on every endian.
 */
object ServiceUuid {

    private const val DOMAIN_SERVICE = "KEYSTONE-SVC"
    private const val DOMAIN_CHARACTERISTIC = "KEYSTONE-CHAR"

    fun forCommunity(id: CommunityId): UUID = derive(id, DOMAIN_SERVICE)

    /** Single read/write/notify characteristic that carries framed Noise traffic. */
    fun characteristicForCommunity(id: CommunityId): UUID = derive(id, DOMAIN_CHARACTERISTIC)

    private fun derive(id: CommunityId, domain: String): UUID {
        val digest = Blake2s.digest(id.bytes, domain)
        val high = bytesToLong(digest, 0)
        val low = bytesToLong(digest, 8)
        return UUID(high, low)
    }

    private fun bytesToLong(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }
}
