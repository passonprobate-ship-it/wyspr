package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM message_reaction WHERE msg_id = :msgId AND from_pub = :fromPub")
    suspend fun delete(msgId: ByteArray, fromPub: ByteArray)

    @Query("SELECT * FROM message_reaction WHERE msg_id = :msgId")
    fun forMessageFlow(msgId: ByteArray): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM message_reaction WHERE msg_id IN (:msgIds)")
    fun forMessagesFlow(msgIds: List<ByteArray>): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM message_reaction WHERE msg_id IN (:msgIds)")
    suspend fun forMessagesSnapshot(msgIds: List<ByteArray>): List<ReactionEntity>
}
