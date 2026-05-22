package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One row per "this owner uses this host as a mailbox" mapping.
 *
 * Stores the signed CBOR `MailboxBinding` cert verbatim alongside the
 * parsed fields. The cert is what propagates between peers (sender
 * needs to know the recipient's mailbox before pushing); the parsed
 * fields exist so DAO queries don't have to reparse on every lookup.
 *
 * `owner_pub` is the user this mailbox serves (== the cert's signer);
 * `mailbox_pub` is the device acting as the mailbox. Locally the rows
 * with `owner_pub == ownPub` describe MY mailboxes; rows with other
 * `owner_pub` describe peers' mailboxes that I've learned about via
 * cert sync.
 *
 * Multi-host (schema v14, Sprint 2 of TOR-ACROSS-WEB): an owner can
 * publish N bindings, one per host they delegate to. Composite PK
 * `(owner_pub, mailbox_pub)` means upsert is per (owner, host) pair —
 * adding a second host does NOT evict the first. Senders iterate every
 * known binding for a recipient and push to each reachable host.
 * Receivers pull from whichever of their own hosts is up.
 */
@Entity(tableName = "mailbox_binding", primaryKeys = ["owner_pub", "mailbox_pub"])
data class MailboxBindingEntity(
    @ColumnInfo("owner_pub") val ownerPub: ByteArray,
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
