package com.keystone.core.currency

import com.keystone.core.crypto.Cbor

/**
 * The community's one-time pre-mine. Signed by the community founder's
 * identity key. CURRENCY.md §4.1.
 *
 *     version        : u8 = 1
 *     kind           : uint = 0 (GENESIS)
 *     community      : bstr(32)
 *     issuer         : bstr(32)            -- must equal community founder
 *     recipients     : [(bstr(32), u64)]   -- account → amount in base units
 *     total_supply   : u64                 -- MUST equal Σ amounts
 *     issued_at      : u64                 -- unix seconds
 *     nonce          : bstr(16)
 *     signature      : bstr(64)            -- Ed25519 over signedBytes()
 *
 * On the wire this is a CBOR array of the fields in the order above.
 * `signedBytes()` is the same array minus the trailing signature.
 */
data class GenesisIssuance(
    val version: Int,
    val community: ByteArray,
    val issuer: ByteArray,
    val recipients: List<Recipient>,
    val totalSupply: Long,
    val issuedAt: Long,
    val nonce: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported genesis version: $version" }
        require(community.size == COMMUNITY_LENGTH) { "community must be 32 bytes" }
        require(issuer.size == PUBLIC_KEY_LENGTH) { "issuer must be 32 bytes" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be 16 bytes" }
        require(signature.size == SIGNATURE_LENGTH) { "signature must be 64 bytes" }
        require(recipients.isNotEmpty()) { "genesis must mint to at least one recipient" }
        require(totalSupply >= 0) { "total supply must be non-negative" }
        val sum = recipients.fold(0L) { acc, r ->
            val next = acc + r.amount
            require(next >= acc) { "recipient sum overflows i64" }
            next
        }
        require(sum == totalSupply) { "total_supply ($totalSupply) != Σ amounts ($sum)" }
    }

    data class Recipient(val account: ByteArray, val amount: Long) {
        init {
            require(account.size == PUBLIC_KEY_LENGTH) { "recipient must be 32 bytes" }
            require(amount >= 0) { "recipient amount must be non-negative" }
        }
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Recipient) return false
            return amount == other.amount && account.contentEquals(other.account)
        }
        override fun hashCode(): Int = 31 * account.contentHashCode() + amount.hashCode()
    }

    /** Canonical wire bytes — what travels in a SyncEnvelope. */
    fun encode(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT)
        writeSignedFields(this, this@GenesisIssuance)
        bytes(signature)
    }

    /** What `issuer` signs over. Same fields, signature omitted. */
    fun signedBytes(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT - 1)
        writeSignedFields(this, this@GenesisIssuance)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GenesisIssuance) return false
        return version == other.version &&
            community.contentEquals(other.community) &&
            issuer.contentEquals(other.issuer) &&
            recipients == other.recipients &&
            totalSupply == other.totalSupply &&
            issuedAt == other.issuedAt &&
            nonce.contentEquals(other.nonce) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + community.contentHashCode()
        r = 31 * r + issuer.contentHashCode()
        r = 31 * r + recipients.hashCode()
        r = 31 * r + totalSupply.hashCode()
        r = 31 * r + issuedAt.hashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        const val VERSION: Int = 1
        const val COMMUNITY_LENGTH: Int = 32
        const val PUBLIC_KEY_LENGTH: Int = 32
        const val NONCE_LENGTH: Int = 16
        const val SIGNATURE_LENGTH: Int = 64

        /** Number of top-level fields in the CBOR array — used by the codec. */
        private const val FIELD_COUNT: Int = 9

        fun decode(bytes: ByteArray): GenesisIssuance = Cbor.decode(bytes) {
            val n = arrayHeader()
            require(n == FIELD_COUNT) { "GenesisIssuance must have $FIELD_COUNT fields, got $n" }
            val version = uint().toIntChecked()
            val kind = uint().toIntChecked()
            require(kind == IssuanceKind.GENESIS.wire) {
                "Expected GENESIS kind=${IssuanceKind.GENESIS.wire}, got $kind"
            }
            val community = bytes()
            val issuer = bytes()
            val recipients = readRecipients()
            val totalSupply = uint()
            val issuedAt = uint()
            val nonce = bytes()
            val signature = bytes()
            GenesisIssuance(
                version = version,
                community = community,
                issuer = issuer,
                recipients = recipients,
                totalSupply = totalSupply,
                issuedAt = issuedAt,
                nonce = nonce,
                signature = signature,
            )
        }

        /**
         * Compose a GenesisIssuance from unsigned fields plus a signature
         * produced over [signedBytesOf]. Throws if the fields would
         * produce an invalid certificate.
         */
        fun assemble(
            version: Int = VERSION,
            community: ByteArray,
            issuer: ByteArray,
            recipients: List<Recipient>,
            totalSupply: Long,
            issuedAt: Long,
            nonce: ByteArray,
            signature: ByteArray,
        ): GenesisIssuance = GenesisIssuance(
            version = version,
            community = community,
            issuer = issuer,
            recipients = recipients,
            totalSupply = totalSupply,
            issuedAt = issuedAt,
            nonce = nonce,
            signature = signature,
        )

        /**
         * The byte sequence the issuer must sign — exposes signing without
         * requiring callers to first build a fake certificate.
         */
        fun signedBytesOf(
            version: Int = VERSION,
            community: ByteArray,
            issuer: ByteArray,
            recipients: List<Recipient>,
            totalSupply: Long,
            issuedAt: Long,
            nonce: ByteArray,
        ): ByteArray {
            require(community.size == COMMUNITY_LENGTH)
            require(issuer.size == PUBLIC_KEY_LENGTH)
            require(nonce.size == NONCE_LENGTH)
            // Build a temporary instance for shared encoding logic; the
            // signature field is a placeholder and never read here.
            val placeholderSig = ByteArray(SIGNATURE_LENGTH)
            val tmp = GenesisIssuance(
                version = version,
                community = community,
                issuer = issuer,
                recipients = recipients,
                totalSupply = totalSupply,
                issuedAt = issuedAt,
                nonce = nonce,
                signature = placeholderSig,
            )
            return tmp.signedBytes()
        }

        private fun writeSignedFields(w: Cbor.Writer, c: GenesisIssuance) {
            w.uint(c.version.toLong())
            w.uint(IssuanceKind.GENESIS.wire.toLong())
            w.bytes(c.community)
            w.bytes(c.issuer)
            w.arrayHeader(c.recipients.size)
            for (r in c.recipients) {
                w.arrayHeader(2)
                w.bytes(r.account)
                w.uint(r.amount)
            }
            w.uint(c.totalSupply)
            w.uint(c.issuedAt)
            w.bytes(c.nonce)
        }

        private fun Cbor.Reader.readRecipients(): List<Recipient> {
            val n = arrayHeader()
            val list = ArrayList<Recipient>(n)
            repeat(n) {
                val pairLen = arrayHeader()
                require(pairLen == 2) { "recipient pair must have 2 fields, got $pairLen" }
                val account = bytes()
                val amount = uint()
                list += Recipient(account = account, amount = amount)
            }
            return list
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}
