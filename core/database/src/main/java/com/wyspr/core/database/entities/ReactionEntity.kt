package com.wyspr.core.database.entities

import androidx.compose.runtime.Stable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "message_reaction",
    primaryKeys = ["msg_id", "from_pub"],
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["msg_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
@Stable
data class ReactionEntity(
    @ColumnInfo("msg_id") val msgId: ByteArray,
    @ColumnInfo("from_pub") val fromPub: ByteArray,
    @ColumnInfo("emoji") val emoji: String,
    @ColumnInfo("created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReactionEntity) return false
        return msgId.contentEquals(other.msgId) &&
            fromPub.contentEquals(other.fromPub) &&
            emoji == other.emoji &&
            createdAt == other.createdAt
    }
    override fun hashCode(): Int {
        var r = msgId.contentHashCode()
        r = 31 * r + fromPub.contentHashCode()
        r = 31 * r + emoji.hashCode()
        r = 31 * r + createdAt.hashCode()
        return r
    }
}
