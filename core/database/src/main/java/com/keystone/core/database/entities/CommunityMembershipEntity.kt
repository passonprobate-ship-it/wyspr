package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One row per community this device is a member of. SECURITY-MODEL.md
 * §6: "a device that belongs to multiple communities holds multiple
 * CommunityId values; each one has its own database, service UUID,
 * and trust graph."
 *
 * v0 enforces a single membership per device — the
 * single-instance constraint lives in [CommunityService], not in the
 * schema, so future multi-community support is just a query change.
 *
 *   - `communityId` — 32-byte SHA-256 of `(domain || founder_pub || nonce || time_be64)`
 *   - `foundedAt`   — local unix seconds when first stored
 *   - `isFounder`   — true if this device created the community; false
 *                     when joining via invitation (when invitation flow lands)
 *   - `displayName` — user-set nickname; never propagated on the wire
 */
@Entity(
    tableName = "community_membership",
    primaryKeys = ["communityId"],
)
data class CommunityMembershipEntity(
    @ColumnInfo(name = "communityId") val communityId: ByteArray,
    @ColumnInfo(name = "foundedAt") val foundedAt: Long,
    @ColumnInfo(name = "isFounder") val isFounder: Boolean,
    @ColumnInfo(name = "displayName") val displayName: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommunityMembershipEntity) return false
        return foundedAt == other.foundedAt &&
            isFounder == other.isFounder &&
            displayName == other.displayName &&
            communityId.contentEquals(other.communityId)
    }
    override fun hashCode(): Int {
        var r = communityId.contentHashCode()
        r = 31 * r + foundedAt.hashCode()
        r = 31 * r + isFounder.hashCode()
        r = 31 * r + (displayName?.hashCode() ?: 0)
        return r
    }
}
