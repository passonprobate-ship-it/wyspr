package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A private group thread — a set of trusted peers receiving the same
 * messages. See `docs/GROUPS.md` for the design.
 *
 * The [groupId] is derived deterministically from the creator's pubkey,
 * the chosen name, and the creation timestamp:
 *
 *     groupId = BLAKE2s-256("WYSPR/v1/group-id"
 *                  || creatorPub || name.utf8 || createdAt.be64)
 *
 * Two devices that observe the same creation event compute the same id,
 * so a member who receives a `GroupMembership` cert and the matching
 * `GroupMessageEnvelope` can verify the id is consistent without needing
 * a separate "create group" gossip message.
 *
 * The creator is authoritative in v1 — only `creatorPub` can sign new
 * `GroupMembership` certs. v2+ will allow delegation through cert
 * chains.
 *
 * [localNickname] is per-device — letting a user rename a group for
 * themselves without changing its identity. Null means render [name].
 */
@Entity(tableName = "group_entity")
data class GroupEntity(
    @PrimaryKey @ColumnInfo("group_id") val groupId: ByteArray,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("creator_pub") val creatorPub: ByteArray,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("local_nickname") val localNickname: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupEntity) return false
        return groupId.contentEquals(other.groupId) &&
            name == other.name &&
            creatorPub.contentEquals(other.creatorPub) &&
            createdAt == other.createdAt &&
            localNickname == other.localNickname
    }
    override fun hashCode(): Int {
        var r = groupId.contentHashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + creatorPub.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + (localNickname?.hashCode() ?: 0)
        return r
    }
}
