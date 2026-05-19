package com.keystone.core.currency

/**
 * Type tags that route a currency envelope to the right codec inside
 * `core:sync`. Values are stable on the wire — never reuse or reorder.
 * CURRENCY.md §7.
 */
object CurrencyTypeTag {
    const val GENESIS_ISSUANCE: Int = 0x10
    const val SERVICE_ISSUANCE: Int = 0x11
    const val TRANSFER: Int = 0x12
    const val TRANSFER_MEMO: Int = 0x13
    const val SLASH: Int = 0x14
}

/**
 * Issuance kind — encoded as a CBOR uint so future kinds extend cleanly.
 * Stable on the wire.
 */
enum class IssuanceKind(val wire: Int) {
    GENESIS(0),
    SERVICE(1),
    ;

    companion object {
        fun fromWire(wire: Int): IssuanceKind = values().firstOrNull { it.wire == wire }
            ?: error("Unknown IssuanceKind wire value: $wire")
    }
}
