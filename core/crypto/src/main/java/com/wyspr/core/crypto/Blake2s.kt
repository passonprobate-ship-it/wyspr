package com.wyspr.core.crypto

import com.southernstorm.noise.crypto.Blake2sMessageDigest

/**
 * BLAKE2s-256 (RFC 7693) — the keyless hash used throughout Wyspr's
 * wire protocol for domain-separated identifiers.
 *
 * Backed by noise-java's [Blake2sMessageDigest], which is already on
 * the classpath because the Noise XX session uses BLAKE2s for its
 * cipher suite. No extra dependency is needed. Output is always 32
 * bytes — the JCA `MessageDigest` contract zeroes any internal state
 * when [java.security.MessageDigest.reset] is called, so callers
 * that need a fresh hasher should obtain a new instance.
 *
 * PROTOCOLS.md §6:
 *     service_uuid = UUID(BLAKE2s(community_id || "WYSPR-SVC"))
 */
object Blake2s {

    const val DIGEST_BYTES: Int = 32

    /** Single-shot hash. Allocates and discards a digest instance. */
    fun digest(input: ByteArray): ByteArray = newDigest().digest(input)

    /**
     * Domain-separated convenience: `BLAKE2s(input || domain)`. The
     * domain string is encoded as UTF-8 and appended — never prefixed
     * — so the spec text reads left-to-right.
     */
    fun digest(input: ByteArray, domain: String): ByteArray {
        val md = newDigest()
        md.update(input)
        md.update(domain.encodeToByteArray())
        return md.digest()
    }

    /** Fresh BLAKE2s-256 hasher. Use when streaming larger inputs. */
    fun newDigest(): Blake2sMessageDigest = Blake2sMessageDigest()
}
