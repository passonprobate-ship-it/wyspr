package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Local-only address book entry for sending to non-paired recipients.
 *
 * Use case: the user wants to pay an XMR address from outside their
 * paired-peer trust graph (e.g. a merchant, an exchange withdrawal
 * target, a friend who isn't on Keystone). Without this, every
 * non-peer send means re-pasting the address from notes.
 *
 * Never synced — this stays on the device. The auto-exchange
 * channel is for paired peers; manually-saved addresses belong to
 * the local user's personal records.
 *
 * Composite PK on `(chain, label)` so the user can have one entry
 * named "Kraken withdrawal" per chain (XMR / future ZEC / etc.)
 * but not two with the same label on the same chain.
 */
@Entity(
    tableName = "address_book",
    primaryKeys = ["chain", "label"],
    indices = [
        // Sort-by-most-recent driven by `last_used_at` desc.
        Index(value = ["chain", "last_used_at"]),
    ],
)
data class AddressBookEntity(
    /** "monero", future: "zcash", "bitcoin-ln", … */
    @ColumnInfo("chain") val chain: String,
    /** User-supplied label ("Kraken withdrawal", "Bob's wallet", …). */
    @ColumnInfo("label") val label: String,
    /** Chain-specific encoded address. */
    @ColumnInfo("address") val address: String,
    @ColumnInfo("created_at") val createdAt: Long,
    /** Updated whenever a send-to-this-entry succeeds. Drives sort. */
    @ColumnInfo("last_used_at") val lastUsedAt: Long,
)
