package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per "this peer's chosen mailbox" mapping.
 *
 * Stores the signed CBOR `MailboxBinding` cert verbatim alongside the
 * parsed fields. The cert is what propagates between peers (sender
 * needs to know the recipient's mailbox before pushing); the parsed
 * fields exist so DAO queries don't have to reparse on every lookup.
 *
 * `owner_pub` is the user this mailbox serves (== the cert's signer);
 * `mailbox_pub` is the device acting as the mailbox. Locally the row
 * with `owner_pub == ownPub` describes MY mailbox; rows with other
 * `owner_pub` describe peers' mailboxes that I've learned about via
 * cert sync (Phase 3).
 *
 * v1 keeps a single row per `owner_pub` (one mailbox per user). v2+
 * can add a composite key for multi-mailbox redundancy without a
 * destructive migration — only the PK changes.
 */
@Entity(tableName = "mailbox_binding")
data class MailboxBindingEntity(
    @PrimaryKey @ColumnInfo("owner_pub") val ownerPub: ByteArray,
    @ColumnInfo("mailbox_pub") val mailboxPub: ByteArray,
    /** Mailbox host's HSv3 .onion (56 chars, no scheme) — null for BLE-only. */
    @ColumnInfo("mailbox_onion") val mailboxOnion: String?,
    /** Verbatim signed CBOR for re-broadcast over sync. */
    @ColumnInfo("cert_bytes") val certBytes: ByteArray,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("expires_at") val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailboxBindingEntity) return false
        return ownerPub.contentEquals(other.ownerPub) &&
            mailboxPub.contentEquals(other.mailboxPub) &&
            mailboxOnion == other.mailboxOnion &&
            certBytes.contentEquals(other.certBytes) &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt
    }
    override fun hashCode(): Int {
        var r = ownerPub.contentHashCode()
        r = 31 * r + mailboxPub.contentHashCode()
        r = 31 * r + (mailboxOnion?.hashCode() ?: 0)
        r = 31 * r + certBytes.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + expiresAt.hashCode()
        return r
    }
}
