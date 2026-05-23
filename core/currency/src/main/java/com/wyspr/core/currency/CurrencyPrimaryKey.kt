package com.wyspr.core.currency

/**
 * Composer for the `primary_key` BLOB column of the `currency_envelope`
 * table. The shape varies by [CurrencyTypeTag]:
 *
 *   | Type tag         | Layout                          | Bytes |
 *   |------------------|---------------------------------|-------|
 *   | GENESIS_ISSUANCE | issuer (= founder)              | 32    |
 *   | SERVICE_ISSUANCE | issuer ‖ period_start_be64      | 40    |
 *   | TRANSFER         | sender ‖ seq_be64               | 40    |
 *   | TRANSFER_MEMO    | sender ‖ seq_be64               | 40    |
 *   | SLASH            | target                          | 32    |
 *
 * Combined with the PRIMARY KEY of `(community, type_tag, primary_key)`
 * this gives Room a unique row for every distinct envelope identity.
 * A duplicate insert on the same key with a *different* body is a
 * cryptographically self-contained double-spend proof for Transfer rows
 * (CURRENCY.md §6.1).
 *
 * The byte layout is on-disk — never change without bumping the DB
 * schema version and writing a migration that rewrites old keys.
 */
object CurrencyPrimaryKey {

    const val PUBLIC_KEY_LENGTH: Int = 32

    fun forGenesisIssuance(issuer: ByteArray): ByteArray {
        require(issuer.size == PUBLIC_KEY_LENGTH) { "issuer must be 32 bytes" }
        return issuer.copyOf()
    }

    fun forServiceIssuance(issuer: ByteArray, periodStart: Long): ByteArray {
        require(issuer.size == PUBLIC_KEY_LENGTH) { "issuer must be 32 bytes" }
        require(periodStart >= 0) { "period_start must be non-negative" }
        return issuer + encodeLongBe(periodStart)
    }

    fun forTransfer(sender: ByteArray, seq: Long): ByteArray {
        require(sender.size == PUBLIC_KEY_LENGTH) { "sender must be 32 bytes" }
        require(seq >= 0) { "seq must be non-negative" }
        return sender + encodeLongBe(seq)
    }

    fun forTransferMemo(sender: ByteArray, seq: Long): ByteArray =
        forTransfer(sender, seq)

    fun forSlash(target: ByteArray): ByteArray {
        require(target.size == PUBLIC_KEY_LENGTH) { "target must be 32 bytes" }
        return target.copyOf()
    }

    /**
     * Convenience: derive the key directly from a [Transfer] envelope.
     * The pattern repeats for every type — kept explicit so callers in
     * the sync path don't accidentally key by the wrong field.
     */
    fun of(transfer: Transfer): ByteArray = forTransfer(transfer.sender, transfer.seq)
    fun of(genesis: GenesisIssuance): ByteArray = forGenesisIssuance(genesis.issuer)

    private fun encodeLongBe(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - 8 * i)).toByte()
        return out
    }
}
