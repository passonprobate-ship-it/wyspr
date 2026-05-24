package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "key_rotation",
    primaryKeys = ["oldPub", "newPub"],
)
data class KeyRotationEntity(
    @ColumnInfo(name = "oldPub") val oldPub: ByteArray,
    @ColumnInfo(name = "newPub") val newPub: ByteArray,
    @ColumnInfo(name = "communityId") val communityId: ByteArray,
    @ColumnInfo(name = "issuedAt") val issuedAt: Long,
    @ColumnInfo(name = "newOnion") val newOnion: String?,
    @ColumnInfo(name = "signature") val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyRotationEntity) return false
        return oldPub.contentEquals(other.oldPub) &&
            newPub.contentEquals(other.newPub) &&
            communityId.contentEquals(other.communityId) &&
            issuedAt == other.issuedAt &&
            newOnion == other.newOnion &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = oldPub.contentHashCode()
        result = 31 * result + newPub.contentHashCode()
        result = 31 * result + communityId.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + (newOnion?.hashCode() ?: 0)
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
