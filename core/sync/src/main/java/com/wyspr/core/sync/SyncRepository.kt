package com.wyspr.core.sync

/**
 * The store-side surface the [SyncEngine] talks to. Each method is a
 * pure suspend operation against persistent state — no I/O networking,
 * no peer awareness.
 *
 * Concrete implementations live in feature modules (e.g.
 * [com.wyspr.core.currency.CurrencySyncRepository]). The engine is
 * agnostic; this layering keeps protocol logic and validation logic
 * cleanly separated.
 */
interface SyncRepository {

    /**
     * Every envelope this device has stored for [community], as a
     * compact `(typeTag, primaryKey)` list. The order doesn't matter
     * — the engine compares as sets.
     */
    suspend fun haveSet(community: ByteArray): List<EnvelopeKey>

    /**
     * Fetch the full rows for the supplied keys. Caller is the engine,
     * which only ever asks for keys it just saw in the peer's Want
     * message; we trust those are well-formed. Returns rows in any
     * order; missing keys are silently dropped.
     */
    suspend fun envelopesByKeys(community: ByteArray, keys: List<EnvelopeKey>): List<EnvelopeRow>

    /**
     * Persist one envelope the peer sent. Implementations MUST verify
     * the envelope's signature before storage — the engine has no
     * cryptographic context (no sodium handle, no keys) and trusts the
     * repository as the policy boundary.
     *
     * Returns true if the envelope was accepted (new or duplicate of
     * existing), false if rejected (bad signature, malformed payload).
     * The engine logs but doesn't otherwise react to rejections — a
     * misbehaving peer is the trust-graph's problem, not sync's.
     */
    suspend fun ingest(row: EnvelopeRow): Boolean
}
