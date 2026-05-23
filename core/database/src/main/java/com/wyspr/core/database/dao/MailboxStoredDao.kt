package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.wyspr.core.database.entities.MailboxStoredEntity
import kotlinx.coroutines.flow.Flow

/** Projection used by the storage-cap eviction path so a full table
 * scan doesn't pull the (potentially-large) ciphertext BLOBs into
 * memory. */
data class MailboxEvictionRow(
    val envelopeId: ByteArray,
    val sizeBytes: Int,
    val createdAt: Long,
)

@Dao
interface MailboxStoredDao {

    /** IGNORE on conflict: dedup on envelope_id — re-pushed envelopes are silently dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stored: MailboxStoredEntity): Long

    /**
     * Bounded recipient query. The Pull handler enforces
     * `created_at > sinceCursor` and caps the page; pushing both into
     * SQL means we never load thousands of rows just to return the
     * first MAX_BATCH.
     */
    @Query(
        "SELECT * FROM mailbox_stored WHERE to_pub = :toPub AND created_at > :sinceCursor " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun forRecipientSince(
        toPub: ByteArray,
        sinceCursor: Long,
        limit: Int,
    ): List<MailboxStoredEntity>

    /** All-recipient lookup (no cursor, no limit) — used by Ack handler for the to_pub guard. */
    @Query("SELECT envelope_id FROM mailbox_stored WHERE to_pub = :toPub")
    suspend fun envelopeIdsForRecipient(toPub: ByteArray): List<ByteArray>

    @Query("DELETE FROM mailbox_stored WHERE envelope_id IN (:ids)")
    suspend fun deleteIds(ids: List<ByteArray>)

    /**
     * Retention sweep — drops rows past their `expires_at`. Run hourly
     * by the host service plus once on every mailbox start.
     */
    @Query("DELETE FROM mailbox_stored WHERE expires_at < :nowSeconds")
    suspend fun expireBefore(nowSeconds: Long): Int

    /**
     * Eviction-decision projection. Drops `ciphertext`/`signature`
     * BLOBs from the result so a near-full host (50MB cap × thousands
     * of envelopes) doesn't burn that much heap per push.
     */
    @Query(
        "SELECT envelope_id AS envelopeId, size_bytes AS sizeBytes, created_at AS createdAt " +
            "FROM mailbox_stored ORDER BY created_at ASC",
    )
    suspend fun allOldestFirstForEviction(): List<MailboxEvictionRow>

    /** Atomic delete-then-insert used by the storage-cap path; see
     *  [com.wyspr.feature.messaging.mailbox.MailboxHost.handlePush]. */
    @Transaction
    suspend fun evictAndInsert(evictIds: List<ByteArray>, stored: MailboxStoredEntity) {
        if (evictIds.isNotEmpty()) deleteIds(evictIds)
        insert(stored)
    }

    // ---- Stats queries (drive the "Be a mailbox" screen) ----

    /** Total bytes currently held across all recipients. */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM mailbox_stored")
    fun totalSizeBytesFlow(): Flow<Long>

    /** Total envelopes currently held. */
    @Query("SELECT COUNT(*) FROM mailbox_stored")
    fun totalCountFlow(): Flow<Int>

    /** Distinct recipients we're holding for. */
    @Query("SELECT COUNT(DISTINCT to_pub) FROM mailbox_stored")
    fun uniqueRecipientsFlow(): Flow<Int>

    @Query("DELETE FROM mailbox_stored")
    suspend fun purgeAll()
}
