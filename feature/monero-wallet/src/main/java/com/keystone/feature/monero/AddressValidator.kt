package com.keystone.feature.monero

import im.molly.monero.sdk.PublicAddress

/**
 * Static helper for "is this string a parseable Monero address?"
 * Hides the SDK type from the UI layer; reflection-free.
 *
 * mollyim's [PublicAddress.parse] throws on invalid input. We wrap
 * that in a try/catch and surface a boolean so callers can drive
 * UI state (green check vs red border) without dealing with the
 * exception. Length-bounded to keep the parse cost trivial — long
 * pastes never reach the SDK.
 */
object AddressValidator {

    private const val MIN_LEN = 95   // standard Monero addresses are 95 chars
    private const val MAX_LEN = 110  // integrated + subaddress allow up to ~106

    fun isValidMonero(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.length !in MIN_LEN..MAX_LEN) return false
        // Cheap prefix gate before calling into the SDK — saves a JNI
        // hop when the user is still typing. Real mainnet addresses
        // start with '4' (standard / subaddress) or '8' (integrated).
        val first = trimmed[0]
        if (first != '4' && first != '8') return false
        return try {
            PublicAddress.parse(trimmed)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
