package com.wyspr.core.trust

/**
 * The trust level of a peer relative to the local node. Derived by
 * walking the local Trust Graph. SECURITY-MODEL.md §3.4.
 *
 * Order matters: ordinal comparisons gate privilege checks.
 */
enum class TrustLevel {
    /** Identity is not in the graph. Default for everyone. */
    Unknown,

    /** A revocation has been observed against this identity. */
    Quarantined,

    /** Admitted by a Full member; only one path exists. */
    Provisional,

    /** ≥ K independent paths from a Root, each of length ≤ D, no Provisional edges. */
    Full,

    /** Self-signed; appears as a root in the local graph. */
    Root,
    ;

    fun atLeast(other: TrustLevel): Boolean = this.ordinal >= other.ordinal
}
