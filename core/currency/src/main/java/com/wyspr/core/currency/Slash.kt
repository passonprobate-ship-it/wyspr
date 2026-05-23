package com.wyspr.core.currency

import com.wyspr.core.crypto.Cbor

/**
 * A signed slash certificate. CURRENCY.md §6.2.
 *
 *     version       : u8 = 1
 *     community     : bstr(32)
 *     target        : bstr(32)            -- the offender (= both transfers' sender)
 *     evidence      : [ bstr, bstr ]      -- two conflicting Transfer wire bodies
 *     issued_at     : u64
 *     witness       : bstr(32)            -- the member who observed the conflict
 *     signature     : bstr(64)            -- Ed25519 over signedBytes()
 *
 * The evidence pair is stored in canonical byte-wise sorted order. Two
 * witnesses that observe the same conflict therefore produce identical
 * `signedBytes`; even though their signatures differ, the structural
 * content (and the PK in the envelope table) is the same. CURRENCY.md
 * §6.1.
 *
 * On the wire this is a CBOR array of the seven fields above, with
 * `evidence` as a 2-element inner array of byte strings.
 */
data class Slash(
    val version: Int,
    val community: ByteArray,
    val target: ByteArray,
    val evidenceA: Transfer,
    val evidenceB: Transfer,
    val issuedAt: Long,
    val witness: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported slash version: $version" }
        require(community.size == COMMUNITY_LENGTH) { "community must be 32 bytes" }
        require(target.size == PUBLIC_KEY_LENGTH) { "target must be 32 bytes" }
        require(witness.size == PUBLIC_KEY_LENGTH) { "witness must be 32 bytes" }
        require(signature.size == SIGNATURE_LENGTH) { "signature must be 64 bytes" }
        require(issuedAt >= 0) { "issued_at must be non-negative" }
        // Evidence ordering is canonical: encoded(A) lex-less-than-or-equal encoded(B).
        // Enforced here so any caller that builds a Slash by hand still gets the
        // same on-the-wire form a SlashDetector would produce.
        val a = evidenceA.encode()
        val b = evidenceB.encode()
        require(compareLex(a, b) <= 0) { "evidence pair is not in canonical order" }
    }

    fun encode(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT)
        writeSignedFields(this, this@Slash)
        bytes(signature)
    }

    fun signedBytes(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT - 1)
        writeSignedFields(this, this@Slash)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Slash) return false
        return version == other.version &&
            issuedAt == other.issuedAt &&
            community.contentEquals(other.community) &&
            target.contentEquals(other.target) &&
            evidenceA == other.evidenceA &&
            evidenceB == other.evidenceB &&
            witness.contentEquals(other.witness) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + community.contentHashCode()
        r = 31 * r + target.contentHashCode()
        r = 31 * r + evidenceA.hashCode()
        r = 31 * r + evidenceB.hashCode()
        r = 31 * r + issuedAt.hashCode()
        r = 31 * r + witness.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        const val VERSION: Int = 1
        const val COMMUNITY_LENGTH: Int = 32
        const val PUBLIC_KEY_LENGTH: Int = 32
        const val SIGNATURE_LENGTH: Int = 64

        private const val FIELD_COUNT: Int = 7

        fun decode(bytes: ByteArray): Slash = Cbor.decode(bytes) {
            val n = arrayHeader()
            require(n == FIELD_COUNT) { "Slash must have $FIELD_COUNT fields, got $n" }
            val version = uint().toIntChecked()
            val community = bytes()
            val target = bytes()
            val (a, b) = readEvidence()
            val issuedAt = uint()
            val witness = bytes()
            val signature = bytes()
            Slash(
                version = version,
                community = community,
                target = target,
                evidenceA = a,
                evidenceB = b,
                issuedAt = issuedAt,
                witness = witness,
                signature = signature,
            )
        }

        /**
         * The bytes the witness signs over. Built without first needing a
         * full Slash instance (so callers can sign before assembling).
         */
        fun signedBytesOf(
            version: Int = VERSION,
            community: ByteArray,
            target: ByteArray,
            evidenceA: Transfer,
            evidenceB: Transfer,
            issuedAt: Long,
            witness: ByteArray,
        ): ByteArray {
            require(community.size == COMMUNITY_LENGTH)
            require(target.size == PUBLIC_KEY_LENGTH)
            require(witness.size == PUBLIC_KEY_LENGTH)
            val (a, b) = orderEvidence(evidenceA, evidenceB)
            val placeholder = ByteArray(SIGNATURE_LENGTH)
            return Slash(
                version = version,
                community = community,
                target = target,
                evidenceA = a,
                evidenceB = b,
                issuedAt = issuedAt,
                witness = witness,
                signature = placeholder,
            ).signedBytes()
        }

        /** Canonicalize an unordered evidence pair. Public so SlashDetector can reuse. */
        fun orderEvidence(a: Transfer, b: Transfer): Pair<Transfer, Transfer> {
            val ae = a.encode()
            val be = b.encode()
            return if (compareLex(ae, be) <= 0) a to b else b to a
        }

        private fun writeSignedFields(w: Cbor.Writer, s: Slash) {
            w.uint(s.version.toLong())
            w.bytes(s.community)
            w.bytes(s.target)
            w.arrayHeader(2)
            w.bytes(s.evidenceA.encode())
            w.bytes(s.evidenceB.encode())
            w.uint(s.issuedAt)
            w.bytes(s.witness)
        }

        private fun Cbor.Reader.readEvidence(): Pair<Transfer, Transfer> {
            val n = arrayHeader()
            require(n == 2) { "evidence pair must have 2 entries, got $n" }
            val a = Transfer.decode(bytes())
            val b = Transfer.decode(bytes())
            return a to b
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

internal fun compareLex(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val ai = a[i].toInt() and 0xFF
        val bi = b[i].toInt() and 0xFF
        if (ai != bi) return ai - bi
    }
    return a.size - b.size
}
