package com.keystone.core.sync

/**
 * Composite identity of one currency envelope on the wire. Matches the
 * `(typeTag, primaryKey)` half of the
 * `(community, typeTag, primaryKey)` primary key in
 * `core:database`'s `currency_envelope` table — the `community` half is
 * carried at the message level instead of repeated per key.
 *
 * Used inside HaveSet and Want messages where bandwidth matters; the
 * full row (with body + observedAt) lives in [EnvelopeRow] inside Push.
 */
data class EnvelopeKey(
    val typeTag: Int,
    val primaryKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnvelopeKey) return false
        return typeTag == other.typeTag && primaryKey.contentEquals(other.primaryKey)
    }
    override fun hashCode(): Int = 31 * typeTag + primaryKey.contentHashCode()
}

/**
 * One row from the `currency_envelope` table, as carried inside Push.
 * `body` is the canonical CBOR of the underlying envelope (Transfer,
 * GenesisIssuance, Slash, …) — sync moves bytes, it never decodes the
 * payload. The receiver dispatches to the right verifier via [typeTag].
 */
data class EnvelopeRow(
    val community: ByteArray,
    val typeTag: Int,
    val primaryKey: ByteArray,
    val body: ByteArray,
    val observedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnvelopeRow) return false
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
