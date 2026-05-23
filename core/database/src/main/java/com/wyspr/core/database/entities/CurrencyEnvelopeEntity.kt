package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Append-only log of every verified currency envelope this device has
 * seen, plus its own outbound envelopes before they're acknowledged.
 * CURRENCY.md §8.
 *
 *   - `community`   — 32-byte community id
 *   - `typeTag`     — `CurrencyTypeTag` int constant (0x10..0x14)
 *   - `primaryKey`  — type-specific composite key from `CurrencyPrimaryKey`
 *   - `body`        — canonical CBOR encoding of the envelope, signature
 *                     included. The wire form, byte-for-byte
 *   - `observedAt`  — local unix-millis when first ingested. UX hint only;
 *                     never sign or sort by this
 *
 * Composite primary key `(community, typeTag, primaryKey)` is what gives
 * us double-spend detection for Transfers: a second insert at the same
 * `(community, TRANSFER, sender||seq)` triggers a constraint violation.
 * The caller then compares `body` bytes to confirm it's an actual
 * double-spend (vs a benign retransmission of the same envelope).
 *
 * The index on `(community, typeTag)` keeps "scan all transfers in this
 * community" queries cheap as the log grows.
 */
@Entity(
    tableName = "currency_envelope",
    primaryKeys = ["community", "typeTag", "primaryKey"],
    indices = [Index(value = ["community", "typeTag"])],
)
data class CurrencyEnvelopeEntity(
    @ColumnInfo(name = "community") val community: ByteArray,
    @ColumnInfo(name = "typeTag") val typeTag: Int,
    @ColumnInfo(name = "primaryKey") val primaryKey: ByteArray,
    @ColumnInfo(name = "body") val body: ByteArray,
    @ColumnInfo(name = "observedAt") val observedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CurrencyEnvelopeEntity) return false
        return typeTag == other.typeTag &&
            observedAt == other.observedAt &&
            community.contentEquals(other.community) &&
            primaryKey.contentEquals(other.primaryKey) &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var r = community.contentHashCode()
        r = 31 * r + typeTag
        r = 31 * r + primaryKey.contentHashCode()
        r = 31 * r + body.contentHashCode()
        r = 31 * r + observedAt.hashCode()
        return r
    }
}
