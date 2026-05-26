package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.CoordinationEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoordinationEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: CoordinationEventEntity)

    @Query(
        "UPDATE coordination_event SET title=:title, description=:description, " +
            "location=:location, startsAt=:startsAt, endsAt=:endsAt, createdAt=:createdAt, " +
            "status=:status, signature=:signature WHERE id=:id AND createdAt < :createdAt"
    )
    suspend fun updateIfNewer(
        id: ByteArray,
        title: String,
        description: String?,
        location: String?,
        startsAt: Long,
        endsAt: Long?,
        createdAt: Long,
        status: Int,
        signature: ByteArray,
    ): Int

    @Query("SELECT * FROM coordination_event WHERE communityId = :communityId AND status = 0 AND startsAt >= :nowSeconds ORDER BY startsAt ASC")
    fun upcomingForCommunity(communityId: ByteArray, nowSeconds: Long): Flow<List<CoordinationEventEntity>>

    @Query("SELECT * FROM coordination_event WHERE communityId = :communityId AND (status = 1 OR startsAt < :nowSeconds) ORDER BY startsAt DESC")
    fun pastOrCancelledForCommunity(communityId: ByteArray, nowSeconds: Long): Flow<List<CoordinationEventEntity>>

    @Query("SELECT * FROM coordination_event WHERE id = :id")
    suspend fun byId(id: ByteArray): CoordinationEventEntity?

    @Query("SELECT * FROM coordination_event WHERE id = :id")
    fun byIdFlow(id: ByteArray): Flow<CoordinationEventEntity?>

    @Query("SELECT * FROM coordination_event WHERE communityId = :communityId")
    suspend fun allForCommunitySnapshot(communityId: ByteArray): List<CoordinationEventEntity>
}
