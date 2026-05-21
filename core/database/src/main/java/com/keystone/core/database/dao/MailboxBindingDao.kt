package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.MailboxBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxBindingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: MailboxBindingEntity)

    /** The mailbox configured for [ownerPub], or null. */
    @Query("SELECT * FROM mailbox_binding WHERE owner_pub = :ownerPub LIMIT 1")
    suspend fun forOwner(ownerPub: ByteArray): MailboxBindingEntity?

    /** Reactive version — UI subscribes to render the "Use a mailbox" row. */
    @Query("SELECT * FROM mailbox_binding WHERE owner_pub = :ownerPub LIMIT 1")
    fun forOwnerFlow(ownerPub: ByteArray): Flow<MailboxBindingEntity?>

    /** Every binding the local device knows about (mine + every peer's). */
    @Query("SELECT * FROM mailbox_binding")
    suspend fun all(): List<MailboxBindingEntity>

    @Query("DELETE FROM mailbox_binding WHERE owner_pub = :ownerPub")
    suspend fun deleteForOwner(ownerPub: ByteArray)

    @Query("DELETE FROM mailbox_binding")
    suspend fun deleteAll()
}
