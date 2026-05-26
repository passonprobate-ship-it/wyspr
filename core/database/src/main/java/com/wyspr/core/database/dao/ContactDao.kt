package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    /**
     * Insert or replace the full contact row. **WARNING**: REPLACE
     * deletes the existing row then re-inserts, which erases `notes`
     * and `disappear_after` if the caller only has the pub + name.
     * Prefer [rename] for display-name-only updates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    /**
     * Update only the display name, preserving `notes` and
     * `disappear_after`. Use this instead of [upsert] when the caller
     * only wants to change the name.
     */
    @Query("UPDATE contact SET displayName = :name WHERE peerPub = :pub")
    suspend fun rename(pub: ByteArray, name: String)

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
