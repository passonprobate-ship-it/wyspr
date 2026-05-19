package com.keystone.core.currency

import com.keystone.core.crypto.Cbor

/**
 * A signed transfer of Gem from one identity to another. CURRENCY.md §5.
 *
 *     version       : u8 = 1
 *     community     : bstr(32)
 *     sender        : bstr(32)
 *     recipient     : bstr(32)
 *     amount        : u64
 *     seq           : u64           -- sender's monotonic counter
 *     memo_hash     : bstr(32) | null
 *     issued_at     : u64           -- unix seconds
 *     nonce         : bstr(16)
 *     signature     : bstr(64)
 *
 * On the wire this is a CBOR array of the fields in the order above.
 * `signedBytes()` is the same array minus the trailing signature.
 *
 * The CBOR for a null memo_hash uses the simple-value 22 (CBOR null).
 * Two distinct transfers with the same `(sender, seq)` are a
 * cryptographically self-contained double-spend proof (CURRENCY.md §6.1).
 */
data class Transfer(
    val version: Int,
    val community: ByteArray,
    val sender: ByteArray,
    val recipient: ByteArray,
    val amount: Long,
    val seq: Long,
    val memoHash: ByteArray?,
    val issuedAt: Long,
    val nonce: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported transfer version: $version" }
        require(community.size == COMMUNITY_LENGTH) { "community must be 32 bytes" }
        require(sender.size == PUBLIC_KEY_LENGTH) { "sender must be 32 bytes" }
        require(recipient.size == PUBLIC_KEY_LENGTH) { "recipient must be 32 bytes" }
        require(amount >= 0) { "amount must be non-negative" }
        require(seq >= 0) { "seq must be non-negative" }
        require(memoHash == null || memoHash.size == MEMO_HASH_LENGTH) {
            "memo_hash must be 32 bytes or null"
        }
        require(nonce.size == NONCE_LENGTH) { "nonce must be 16 bytes" }
        require(signature.size == SIGNATURE_LENGTH) { "signature must be 64 bytes" }
    }

    /** Canonical wire bytes — what travels in a SyncEnvelope. */
    fun encode(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT)
        writeSignedFields(this, this@Transfer)
        bytes(signature)
    }

    /** What `sender` signs over. Same fields, signature omitted. */
    fun signedBytes(): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT - 1)
        writeSignedFields(this, this@Transfer)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transfer) return false
        return version == other.version &&
            community.contentEquals(other.community) &&
            sender.contentEquals(other.sender) &&
            recipient.contentEquals(other.recipient) &&
            amount == other.amount &&
            seq == other.seq &&
            memoHashEquals(other.memoHash) &&
            issuedAt == other.issuedAt &&
            nonce.contentEquals(other.nonce) &&
            signature.contentEquals(other.signature)
    }

    private fun memoHashEquals(other: ByteArray?): Boolean = when {
        memoHash == null -> other == null
        other == null -> false
        else -> memoHash.contentEquals(other)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + community.contentHashCode()
        r = 31 * r + sender.contentHashCode()
        r = 31 * r + recipient.contentHashCode()
        r = 31 * r + amount.hashCode()
        r = 31 * r + seq.hashCode()
        r = 31 * r + (memoHash?.contentHashCode() ?: 0)
        r = 31 * r + issuedAt.hashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        const val VERSION: Int = 1
        const val COMMUNITY_LENGTH: Int = 32
        const val PUBLIC_KEY_LENGTH: Int = 32
        const val MEMO_HASH_LENGTH: Int = 32
        const val NONCE_LENGTH: Int = 16
        const val SIGNATURE_LENGTH: Int = 64

        private const val FIELD_COUNT: Int = 10

        fun decode(bytes: ByteArray): Transfer = Cbor.decode(bytes) {
            val n = arrayHeader()
            require(n == FIELD_COUNT) { "Transfer must have $FIELD_COUNT fields, got $n" }
            val version = uint().toIntChecked()
            val community = bytes()
            val sender = bytes()
            val recipient = bytes()
            val amount = uint()
            val seq = uint()
            val memoHash = bytesOrNull()
            val issuedAt = uint()
            val nonce = bytes()
            val signature = bytes()
            Transfer(
                version = version,
                community = community,
                sender = sender,
                recipient = recipient,
                amount = amount,
                seq = seq,
                memoHash = memoHash,
                issuedAt = issuedAt,
                nonce = nonce,
                signature = signature,
            )
        }

        /**
         * The byte sequence the sender must sign — built without first
         * needing a full Transfer instance.
         */
        fun signedBytesOf(
            version: Int = VERSION,
            community: ByteArray,
            sender: ByteArray,
            recipient: ByteArray,
            amount: Long,
            seq: Long,
            memoHash: ByteArray?,
            issuedAt: Long,
            nonce: ByteArray,
        ): ByteArray {
            require(community.size == COMMUNITY_LENGTH)
            require(sender.size == PUBLIC_KEY_LENGTH)
            require(recipient.size == PUBLIC_KEY_LENGTH)
            require(memoHash == null || memoHash.size == MEMO_HASH_LENGTH)
            require(nonce.size == NONCE_LENGTH)
            val placeholderSig = ByteArray(SIGNATURE_LENGTH)
            return Transfer(
                version = version,
                community = community,
                sender = sender,
                recipient = recipient,
                amount = amount,
                seq = seq,
                memoHash = memoHash,
                issuedAt = issuedAt,
                nonce = nonce,
                signature = placeholderSig,
            ).signedBytes()
        }

        private fun writeSignedFields(w: Cbor.Writer, t: Transfer) {
            w.uint(t.version.toLong())
            w.bytes(t.community)
            w.bytes(t.sender)
            w.bytes(t.recipient)
            w.uint(t.amount)
            w.uint(t.seq)
            if (t.memoHash == null) w.nullValue() else w.bytes(t.memoHash)
            w.uint(t.issuedAt)
            w.bytes(t.nonce)
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}
