package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    /**
     * Insert-or-replace. The `groupId` is content-addressed (see
     * GROUPS.md) so re-inserting the same group is idempotent — two
     * peers can both call this when they receive their first
     * membership cert and converge on the same row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("SELECT * FROM group_entity WHERE group_id = :groupId LIMIT 1")
    suspend fun byId(groupId: ByteArray): GroupEntity?

    @Query("SELECT * FROM group_entity ORDER BY created_at DESC")
    suspend fun all(): List<GroupEntity>

    /**
     * Reactive feed of every group. The conversation list mixes this
     * with the 1:1 thread feed.
     */
    @Query("SELECT * FROM group_entity ORDER BY created_at DESC")
    fun allFlow(): Flow<List<GroupEntity>>

    @Query("UPDATE group_entity SET local_nickname = :nickname WHERE group_id = :groupId")
    suspend fun setNickname(groupId: ByteArray, nickname: String?)

    @Query("DELETE FROM group_entity WHERE group_id = :groupId")
    suspend fun delete(groupId: ByteArray)
}
