package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.CommunityMembershipEntity

@Dao
interface CommunityMembershipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CommunityMembershipEntity)

    @Query("SELECT * FROM community_membership LIMIT 1")
    suspend fun firstOrNull(): CommunityMembershipEntity?

    @Query("SELECT * FROM community_membership")
    suspend fun all(): List<CommunityMembershipEntity>

    @Query("SELECT COUNT(*) FROM community_membership")
    suspend fun count(): Int

    @Query("DELETE FROM community_membership")
    suspend fun deleteAll()
}
