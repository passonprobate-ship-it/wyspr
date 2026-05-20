package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    /**
     * Set or replace the friendly name for a peer. Passing
     * `displayName = null` keeps the row but clears the name; the
     * caller should prefer [clear] if they want to drop the row
     * entirely.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contact WHERE peerPub = :peerPub LIMIT 1")
    suspend fun byPub(peerPub: ByteArray): ContactEntity?

    @Query("SELECT * FROM contact")
    suspend fun all(): List<ContactEntity>

    /**
     * Reactive feed of every saved contact. The conversation list
     * observes this to re-render whenever the user renames someone.
     */
    @Query("SELECT * FROM contact")
    fun allFlow(): Flow<List<ContactEntity>>

    @Query("DELETE FROM contact WHERE peerPub = :peerPub")
    suspend fun clear(peerPub: ByteArray)
}
