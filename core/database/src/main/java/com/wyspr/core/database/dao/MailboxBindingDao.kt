package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.MailboxBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxBindingDao {

    /**
     * Per-(owner, host) upsert. With the composite PK an upsert touches
     * at most one row; adding a SECOND host for the same owner inserts
     * alongside the first instead of replacing it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: MailboxBindingEntity)

    /**
     * Every mailbox configured for [ownerPub]. Multi-host: N rows
     * possible; empty list when none. Ordered by `created_at` so the
     * UI can render the primary (oldest) first.
     */
    @Query("SELECT * FROM mailbox_binding WHERE owner_pub = :ownerPub ORDER BY created_at ASC")
    suspend fun forOwner(ownerPub: ByteArray): List<MailboxBindingEntity>

    /** Reactive — UI subscribes to render the list of mailboxes. */
    @Query("SELECT * FROM mailbox_binding WHERE owner_pub = :ownerPub ORDER BY created_at ASC")
    fun forOwnerFlow(ownerPub: ByteArray): Flow<List<MailboxBindingEntity>>

    /** Every binding the local device knows about (mine + every peer's). */
    @Query("SELECT * FROM mailbox_binding")
    suspend fun all(): List<MailboxBindingEntity>

    /** Remove every binding for [ownerPub] — used when the user disables their mailbox entirely. */
    @Query("DELETE FROM mailbox_binding WHERE owner_pub = :ownerPub")
    suspend fun deleteForOwner(ownerPub: ByteArray)

    /** Remove a specific (owner, host) binding — used to drop one host while keeping others. */
    @Query("DELETE FROM mailbox_binding WHERE owner_pub = :ownerPub AND mailbox_pub = :mailboxPub")
    suspend fun deleteForOwnerHost(ownerPub: ByteArray, mailboxPub: ByteArray)

    @Query("DELETE FROM mailbox_binding")
    suspend fun deleteAll()
}
