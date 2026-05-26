package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.CoordinationRsvpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoordinationRsvpDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rsvp: CoordinationRsvpEntity)

    @Query(
        "UPDATE coordination_rsvp SET status=:status, createdAt=:createdAt, " +
            "signature=:signature WHERE eventId=:eventId AND responderPub=:responderPub " +
            "AND createdAt < :createdAt"
    )
    suspend fun updateIfNewer(
        eventId: ByteArray,
        responderPub: ByteArray,
        status: Int,
        createdAt: Long,
        signature: ByteArray,
    ): Int

    @Query("SELECT * FROM coordination_rsvp WHERE eventId = :eventId")
    fun forEvent(eventId: ByteArray): Flow<List<CoordinationRsvpEntity>>

    @Query("SELECT * FROM coordination_rsvp WHERE eventId = :eventId")
    suspend fun forEventSnapshot(eventId: ByteArray): List<CoordinationRsvpEntity>

    @Query("SELECT * FROM coordination_rsvp WHERE communityId = :communityId")
    suspend fun allForCommunity(communityId: ByteArray): List<CoordinationRsvpEntity>
}
