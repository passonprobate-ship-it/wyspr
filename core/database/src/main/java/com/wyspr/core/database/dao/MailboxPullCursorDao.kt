package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.MailboxPullCursorEntity

@Dao
interface MailboxPullCursorDao {

    /** Returns the persisted cursor for [mailboxPub], or 0 if never pulled. */
    @Query("SELECT since_cursor FROM mailbox_pull_cursor WHERE mailbox_pub = :mailboxPub")
    suspend fun getCursor(mailboxPub: ByteArray): Long?

    /** Advance the cursor — REPLACE conflict so each pull writes the
     *  new high-water mark. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MailboxPullCursorEntity)

    @Query("DELETE FROM mailbox_pull_cursor WHERE mailbox_pub = :mailboxPub")
    suspend fun clear(mailboxPub: ByteArray)
}
