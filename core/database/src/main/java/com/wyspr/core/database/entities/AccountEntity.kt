package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Per-identity ledger state for Gem. CURRENCY.md §8.
 *
 *   - `pub`           — 32-byte Ed25519 identity public key (primary key)
 *   - `seq`           — max sequence number observed in any signed Transfer
 *                       from this account. For the local account this is
 *                       also "the last seq we issued"; for remote accounts
 *                       it's "the highest we've witnessed so far"
 *   - `balanceCached` — derived, recomputable from currency_envelope rows.
 *                       The reducer can drop it at any time and rebuild
 *   - `slashed`       — once true, the account's balance is zero and it
 *                       cannot issue or receive (CURRENCY.md §6.2)
 */
@Entity(
    tableName = "account",
    primaryKeys = ["pub"],
)
data class AccountEntity(
    @ColumnInfo(name = "pub") val pub: ByteArray,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "balanceCached") val balanceCached: Long,
    @ColumnInfo(name = "slashed") val slashed: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountEntity) return false
        return seq == other.seq &&
            balanceCached == other.balanceCached &&
            slashed == other.slashed &&
            pub.contentEquals(other.pub)
    }

    override fun hashCode(): Int {
        var r = pub.contentHashCode()
        r = 31 * r + seq.hashCode()
        r = 31 * r + balanceCached.hashCode()
        r = 31 * r + slashed.hashCode()
        return r
    }
}
