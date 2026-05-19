package com.keystone.core.transport

import com.keystone.core.identity.CommunityId
import java.security.MessageDigest
import java.util.UUID

/**
 * Derive the BLE / WiFi Direct service identifier from the community id.
 *
 * PROTOCOLS.md §6:
 *     service_uuid = UUID(BLAKE2s(community_id || "KEYSTONE-SVC"))
 *
 * Note: stdlib has no BLAKE2s; libsodium provides it. We use SHA-256 as
 * a placeholder here — must be replaced with BLAKE2s before any release
 * build to match the spec exactly.
 */
object ServiceUuid {

    private const val DOMAIN_SERVICE = "KEYSTONE-SVC"
    private const val DOMAIN_CHARACTERISTIC = "KEYSTONE-CHAR"

    fun forCommunity(id: CommunityId): UUID = derive(id, DOMAIN_SERVICE)

    /** Single read/write/notify characteristic that carries framed Noise traffic. */
    fun characteristicForCommunity(id: CommunityId): UUID = derive(id, DOMAIN_CHARACTERISTIC)

    private fun derive(id: CommunityId, domain: String): UUID {
        // TODO: switch to BLAKE2s via libsodium when crypto module wires it
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.bytes + domain.encodeToByteArray())
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
