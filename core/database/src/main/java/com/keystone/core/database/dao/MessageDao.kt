package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    /** Reactive stream of every message in a peer's thread, oldest first. */
    @Query("SELECT * FROM message WHERE thread_pub = :peerPub ORDER BY created_at ASC")
    fun threadFlow(peerPub: ByteArray): Flow<List<MessageEntity>>

    /** Snapshot of the same — used by sync push, not the UI. */
    @Query("SELECT * FROM message WHERE thread_pub = :peerPub ORDER BY created_at ASC")
    suspend fun threadSnapshot(peerPub: ByteArray): List<MessageEntity>

    /** Most recent message in each thread; used by ConversationList. */
    @Query(
        """
        SELECT m.* FROM message m
        INNER JOIN (
            SELECT thread_pub, MAX(created_at) AS max_at
            FROM message
            GROUP BY thread_pub
        ) latest
        ON m.thread_pub = latest.thread_pub AND m.created_at = latest.max_at
        ORDER BY m.created_at DESC
        """,
    )
    fun latestPerThreadFlow(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE thread_pub = :peerPub AND from_pub = :peerPub AND status != 'read'")
    suspend fun unreadCountFor(peerPub: ByteArray): Int

    /** Outbound messages still waiting to be pushed to the peer. */
    @Query("SELECT * FROM message WHERE status = 'pending' AND from_pub = :selfPub ORDER BY created_at ASC")
    suspend fun pendingOutboundFrom(selfPub: ByteArray): List<MessageEntity>

    @Query("UPDATE message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: ByteArray, status: String)

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: ByteArray)

    @Query("SELECT * FROM message WHERE id = :id LIMIT 1")
    suspend fun byId(id: ByteArray): MessageEntity?
}
