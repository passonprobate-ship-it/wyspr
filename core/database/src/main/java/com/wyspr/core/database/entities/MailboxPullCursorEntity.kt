package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks the largest `created_at` we've successfully pulled from each
 * mailbox host. The recipient sends this value as `sinceCursor` on
 * every Pull so a malicious host can't replay already-delivered
 * envelopes — the host's response is bounded to `created_at > cursor`.
 *
 * Keyed by the host's pubkey (`mailbox_pub`), not the owner pub: a
 * user can rotate mailboxes and we want each cursor to be independent.
 */
@Entity(tableName = "mailbox_pull_cursor")
data class MailboxPullCursorEntity(
    @PrimaryKey @ColumnInfo("mailbox_pub") val mailboxPub: ByteArray,
    @ColumnInfo("since_cursor") val sinceCursor: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailboxPullCursorEntity) return false
        return mailboxPub.contentEquals(other.mailboxPub) && sinceCursor == other.sinceCursor
    }
    override fun hashCode(): Int =
        31 * mailboxPub.contentHashCode() + sinceCursor.hashCode()
}
