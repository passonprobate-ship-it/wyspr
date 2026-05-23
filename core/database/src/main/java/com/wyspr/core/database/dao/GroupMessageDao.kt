package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.GroupMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: GroupMessageEntity)

    /** Reactive stream of one group's messages, oldest first. */
    @Query("SELECT * FROM group_message WHERE group_id = :groupId ORDER BY created_at ASC")
    fun threadFlow(groupId: ByteArray): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_message WHERE group_id = :groupId ORDER BY created_at ASC")
    suspend fun threadSnapshot(groupId: ByteArray): List<GroupMessageEntity>

    /**
     * Most recent message per group — fed into the conversation list
     * alongside the 1:1 thread feed.
     */
    @Query(
        """
        SELECT m.* FROM group_message m
        INNER JOIN (
            SELECT group_id, MAX(created_at) AS max_at, MAX(rowid) AS max_rowid
            FROM group_message
            GROUP BY group_id
        ) latest
        ON m.group_id = latest.group_id AND m.rowid = latest.max_rowid
        ORDER BY m.created_at DESC
        """,
    )
    fun latestPerGroupFlow(): Flow<List<GroupMessageEntity>>

    /**
     * Outbound group messages still pending fan-out. The sync engine
     * iterates these per peer to push the appropriate messages each
     * member hasn't yet received.
     */
    @Query("SELECT * FROM group_message WHERE status IN ('pending', 'sent') AND from_pub = :selfPub ORDER BY created_at ASC")
    suspend fun pendingOutboundFrom(selfPub: ByteArray): List<GroupMessageEntity>

    /**
     * Reactive total of unviewed inbound group messages across every
     * group — combines with [MessageDao.totalUnreadFlow] to drive the
     * single unread badge on the app home Messages tile.
     */
    @Query("SELECT COUNT(*) FROM group_message WHERE status = 'received'")
    fun totalUnreadFlow(): Flow<Int>

    @Query("UPDATE group_message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: ByteArray, status: String)

    @Query("SELECT EXISTS(SELECT 1 FROM group_message WHERE id = :id)")
    suspend fun exists(id: ByteArray): Boolean

    @Query("DELETE FROM group_message WHERE id = :id")
    suspend fun delete(id: ByteArray)
}
