package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "group_message_delivery",
    primaryKeys = ["msg_id", "peer_pub"],
    foreignKeys = [ForeignKey(
        entity = GroupMessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["msg_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class GroupMessageDeliveryEntity(
    @ColumnInfo("msg_id") val msgId: ByteArray,
    @ColumnInfo("peer_pub") val peerPub: ByteArray,
    @ColumnInfo("delivered_at") val deliveredAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessageDeliveryEntity) return false
        return msgId.contentEquals(other.msgId) &&
            peerPub.contentEquals(other.peerPub) &&
            deliveredAt == other.deliveredAt
    }
    override fun hashCode(): Int {
        var r = msgId.contentHashCode()
        r = 31 * r + peerPub.contentHashCode()
        r = 31 * r + deliveredAt.hashCode()
        return r
    }
}
