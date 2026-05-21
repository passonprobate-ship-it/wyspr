package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.MailboxStoredEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxStoredDao {

    /** IGNORE on conflict: dedup on envelope_id — re-pushed envelopes are silently dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stored: MailboxStoredEntity): Long

    /** Every envelope waiting for [toPub], oldest first. Used by the Pull handler. */
    @Query("SELECT * FROM mailbox_stored WHERE to_pub = :toPub ORDER BY created_at ASC")
    suspend fun forRecipient(toPub: ByteArray): List<MailboxStoredEntity>

    @Query("DELETE FROM mailbox_stored WHERE envelope_id IN (:ids)")
    suspend fun deleteIds(ids: List<ByteArray>)

    /**
     * Retention sweep — drops rows past their `expires_at`. Run hourly
     * by the host service plus once on every mailbox start.
     */
    @Query("DELETE FROM mailbox_stored WHERE expires_at < :nowSeconds")
    suspend fun expireBefore(nowSeconds: Long): Int

    /**
     * Every held envelope sorted by `created_at` ASC — oldest first.
     * Used by the storage-cap eviction path to drop the longest-held
     * envelopes when room is needed for a fresh push. Only the
     * (envelope_id, size_bytes, created_at) fields are needed for the
     * eviction decision; the full row would unnecessarily pull large
     * `ciphertext` BLOBs into memory. v1 returns full rows for
     * simplicity; if eviction becomes hot we can switch to a
     * projection.
     */
    @Query("SELECT * FROM mailbox_stored ORDER BY created_at ASC")
    suspend fun allOldestFirstForEviction(): List<MailboxStoredEntity>

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
