package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A message sent to or received within a private group. Same fields
 * as [MessageEntity] minus `to_pub` (membership is implied by group)
 * plus `group_id`.
 *
 * Statuses on outbound:
 *   - "pending"   — not yet pushed to ANY member
 *   - "sent"      — pushed to at least one member (per-recipient
 *                   delivery tracking is a v2 feature)
 *   - "delivered" — at least one member acked
 *   - "read"      — every member sent a read receipt (v2)
 *
 * Statuses on inbound:
 *   - "received"  — accepted into the DB
 *   - "viewed"    — the local user opened the group thread after this
 *                   message landed; a read receipt is owed to the sender
 *                   on the next sync round
 *
 * The signature is over the canonical CBOR of `GroupMessageEnvelope`
 * (without `x`). See PROTOCOLS.md and GROUPS.md.
 */
@Entity(
    tableName = "group_message",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["group_id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["group_id", "created_at"]),
        Index(value = ["status"]),
    ],
)
data class GroupMessageEntity(
    @PrimaryKey @ColumnInfo("id") val id: ByteArray,
    @ColumnInfo("group_id") val groupId: ByteArray,
    @ColumnInfo("from_pub") val fromPub: ByteArray,
    @ColumnInfo("body") val body: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("received_at") val receivedAt: Long?,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("signature") val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessageEntity) return false
        return id.contentEquals(other.id) &&
            groupId.contentEquals(other.groupId) &&
            fromPub.contentEquals(other.fromPub) &&
            body == other.body &&
            createdAt == other.createdAt &&
            receivedAt == other.receivedAt &&
            status == other.status &&
            signature.contentEquals(other.signature)
    }
    override fun hashCode(): Int {
        var r = id.contentHashCode()
        r = 31 * r + groupId.contentHashCode()
        r = 31 * r + fromPub.contentHashCode()
        r = 31 * r + body.hashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + (receivedAt?.hashCode() ?: 0)
        r = 31 * r + status.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }
}
