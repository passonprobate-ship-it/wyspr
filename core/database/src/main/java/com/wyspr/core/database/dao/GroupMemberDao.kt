package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_member WHERE group_id = :groupId AND status = 'active' ORDER BY added_at")
    suspend fun activeForGroup(groupId: ByteArray): List<GroupMemberEntity>

    @Query("SELECT * FROM group_member WHERE group_id = :groupId ORDER BY added_at")
    suspend fun allForGroup(groupId: ByteArray): List<GroupMemberEntity>

    @Query("SELECT * FROM group_member WHERE group_id = :groupId ORDER BY added_at")
    fun allForGroupFlow(groupId: ByteArray): Flow<List<GroupMemberEntity>>

    /**
     * Every group that [memberPub] is a member of. Used when a peer
     * connects so we can identify which group threads include them
     * and push the appropriate group messages.
     */
    @Query("SELECT * FROM group_member WHERE member_pub = :memberPub AND status = 'active'")
    suspend fun groupsForMember(memberPub: ByteArray): List<GroupMemberEntity>

    @Query(
        "UPDATE group_member SET status = :status WHERE group_id = :groupId AND member_pub = :memberPub"
    )
    suspend fun setStatus(groupId: ByteArray, memberPub: ByteArray, status: String)

    @Query("DELETE FROM group_member WHERE group_id = :groupId AND member_pub = :memberPub")
    suspend fun delete(groupId: ByteArray, memberPub: ByteArray)
}
