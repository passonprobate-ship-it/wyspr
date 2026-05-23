package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Membership of [memberPub] in [groupId], proved by [certBytes]
 * (canonical-CBOR `GroupMembership` cert signed by the group creator).
 *
 * The cert is stored verbatim so this device can re-share it with
 * other members on sync without re-signing — only the creator can
 * sign membership certs in v1.
 *
 * [status] = "active" | "removed". "removed" stays in the table so
 * we can show greyed-out historical members in the UI; the sender
 * fan-out skips them.
 */
@Entity(
    tableName = "group_member",
    primaryKeys = ["group_id", "member_pub"],
    indices = [Index(value = ["member_pub"])],
)
data class GroupMemberEntity(
    @ColumnInfo("group_id") val groupId: ByteArray,
    @ColumnInfo("member_pub") val memberPub: ByteArray,
    @ColumnInfo("cert_bytes") val certBytes: ByteArray,
    @ColumnInfo("added_at") val addedAt: Long,
    @ColumnInfo("status") val status: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMemberEntity) return false
        return groupId.contentEquals(other.groupId) &&
            memberPub.contentEquals(other.memberPub) &&
            certBytes.contentEquals(other.certBytes) &&
            addedAt == other.addedAt &&
            status == other.status
    }
    override fun hashCode(): Int {
        var r = groupId.contentHashCode()
        r = 31 * r + memberPub.contentHashCode()
        r = 31 * r + certBytes.contentHashCode()
        r = 31 * r + addedAt.hashCode()
        r = 31 * r + status.hashCode()
        return r
    }

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_REMOVED = "removed"
    }
}
