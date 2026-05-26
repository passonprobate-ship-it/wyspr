package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Insert a message, ignoring if a row with the same PK already exists.
     * Named `insertOrIgnore` (not `upsert`) because IGNORE silently drops
     * the insert on conflict — it does NOT update existing rows.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(message: MessageEntity)

    /**
     * @deprecated Use [insertOrIgnore] — the name "upsert" is misleading
     * since IGNORE does not update on conflict. Kept as an alias for
     * backward compatibility with existing callers.
     */
    @Deprecated("Use insertOrIgnore()", ReplaceWith("insertOrIgnore(message)"))
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(message: MessageEntity)

    /** Reactive stream of every message in a peer's thread, oldest first. */
    @Query("SELECT * FROM message WHERE thread_pub = :peerPub ORDER BY created_at ASC")
    fun threadFlow(peerPub: ByteArray): Flow<List<MessageEntity>>

    /** Snapshot of the same — used by sync push, not the UI. */
    @Query("SELECT * FROM message WHERE thread_pub = :peerPub ORDER BY created_at ASC")
    suspend fun threadSnapshot(peerPub: ByteArray): List<MessageEntity>

    /**
     * Most recent message in each thread; used by ConversationList.
     *
     * Uses a correlated subquery so the rowid picked is always the row
     * with the largest created_at (tie-broken by rowid DESC). The old
     * query selected MAX(rowid) and MAX(created_at) independently,
     * which returned the wrong message when messages arrived out of
     * order.
     */
    @Query(
        """
        SELECT m.* FROM message m
        WHERE m.rowid = (
            SELECT m2.rowid FROM message m2
            WHERE m2.thread_pub = m.thread_pub
            ORDER BY m2.created_at DESC, m2.rowid DESC
            LIMIT 1
        )
        ORDER BY m.created_at DESC
        """,
    )
    fun latestPerThreadFlow(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE thread_pub = :peerPub AND from_pub = :peerPub AND status != 'read'")
    suspend fun unreadCountFor(peerPub: ByteArray): Int

    /**
     * Reactive total of unviewed inbound 1:1 messages across every
     * thread — drives the unread badge on the app home tile.
     * "received" means the row landed locally but the user hasn't
     * opened the conversation yet; once they do, [MessageStore]
     * transitions to `received_viewed` and this count drops.
     */
    @Query("SELECT COUNT(*) FROM message WHERE status = 'received'")
    fun totalUnreadFlow(): Flow<Int>

    /** Outbound messages still waiting to be pushed to the peer. */
    @Query("SELECT * FROM message WHERE status = 'pending' AND from_pub = :selfPub ORDER BY created_at ASC")
    suspend fun pendingOutboundFrom(selfPub: ByteArray): List<MessageEntity>

    /**
     * Pending outbound to a specific peer. SQL-side filter so the sync
     * engine doesn't load every pending row in the database just to
     * pick out one peer's queue.
     */
    @Query(
        "SELECT * FROM message WHERE status = 'pending' " +
            "AND from_pub = :selfPub AND to_pub = :peerPub " +
            "ORDER BY created_at ASC",
    )
    suspend fun pendingOutboundFromTo(selfPub: ByteArray, peerPub: ByteArray): List<MessageEntity>

    /**
     * Inbound rows from [peerPub] currently in `received_viewed` — i.e.
     * the user has opened the chat but we haven't yet sent the read
     * receipt. Used by the read-receipt sync phase.
     */
    @Query(
        "SELECT * FROM message WHERE thread_pub = :peerPub " +
            "AND from_pub = :peerPub AND status = 'received_viewed' " +
            "ORDER BY created_at ASC",
    )
    suspend fun pendingReadAckFor(peerPub: ByteArray): List<MessageEntity>

    @Query("UPDATE message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: ByteArray, status: String)

    /** Single bulk transition. Returns the number of affected rows so
     *  callers can identify how many of [ids] were eligible. */
    @Query("UPDATE message SET status = :newStatus WHERE id IN (:ids) AND status = :fromStatus")
    suspend fun bulkTransitionStatus(
        ids: List<ByteArray>,
        fromStatus: String,
        newStatus: String,
    ): Int

    /** Bulk variant that accepts two old statuses (e.g., sent OR delivered → read). */
    @Query(
        "UPDATE message SET status = :newStatus WHERE id IN (:ids) " +
            "AND status IN (:fromStatusA, :fromStatusB)",
    )
    suspend fun bulkTransitionStatus2(
        ids: List<ByteArray>,
        fromStatusA: String,
        fromStatusB: String,
        newStatus: String,
    ): Int

    /** Fetch only ids whose status is in one of the given values. Used
     *  after a bulk transition to return the actually-affected ids. */
    @Query("SELECT id FROM message WHERE id IN (:ids) AND status = :status")
    suspend fun idsWithStatus(ids: List<ByteArray>, status: String): List<ByteArray>

    @Query("SELECT * FROM message WHERE thread_pub = :peerPub ORDER BY created_at DESC LIMIT 1")
    suspend fun latestInThread(peerPub: ByteArray): MessageEntity?

    @Query("SELECT COUNT(*) FROM message WHERE thread_pub = :peerPub AND from_pub = :peerPub AND status = 'received'")
    suspend fun unreadInboundCount(peerPub: ByteArray): Int

    @Query("SELECT id FROM message WHERE thread_pub = :peerPub AND from_pub = :peerPub AND status = 'received'")
    suspend fun unreadIdsFor(peerPub: ByteArray): List<ByteArray>

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: ByteArray)

    @Query("SELECT * FROM message WHERE id = :id LIMIT 1")
    suspend fun byId(id: ByteArray): MessageEntity?

    @Query(
        "SELECT * FROM message WHERE body LIKE '%' || :query || '%' ESCAPE '\\' " +
            "AND body NOT LIKE 'wyspr:img:%' " +
            "AND body NOT LIKE 'wyspr:audio:%' " +
            "AND body NOT LIKE 'wyspr:react:%' " +
            "AND body NOT LIKE 'wyspr:file:%' " +
            "AND body NOT LIKE 'wyspr:disappear:%' " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 50): List<MessageEntity>

    @Query("DELETE FROM message WHERE expires_at IS NOT NULL AND expires_at <= :nowSeconds")
    suspend fun deleteExpired(nowSeconds: Long): Int

    @Query("UPDATE message SET expires_at = :expiresAt WHERE id = :id")
    suspend fun setExpiresAt(id: ByteArray, expiresAt: Long?)

    /**
     * Atomically transition a message from "pending" to "sent". No-ops if
     * the message is already in a later status, preventing a status
     * regression when concurrent sync rounds race.
     */
    @Query("UPDATE message SET status = 'sent' WHERE id = :id AND status = 'pending'")
    suspend fun markSentIfPending(id: ByteArray)

    @Query("UPDATE message SET thread_pub = :newPub WHERE thread_pub = :oldPub")
    suspend fun rekeyThread(oldPub: ByteArray, newPub: ByteArray)

    @Query("UPDATE message SET from_pub = :newPub WHERE from_pub = :oldPub")
    suspend fun rekeyFromPub(oldPub: ByteArray, newPub: ByteArray)

    @Query("UPDATE message SET to_pub = :newPub WHERE to_pub = :oldPub")
    suspend fun rekeyToPub(oldPub: ByteArray, newPub: ByteArray)
}
