package com.wyspr.feature.monero

import im.molly.monero.sdk.mnemonics.MoneroMnemonic
import java.util.Locale

/**
 * Thin wrapper around mollyim's [MoneroMnemonic] that exposes the
 * Electrum-style 25-word seed encoder/decoder to Wyspr code
 * without leaking the [MnemonicCode] type into the UI layer.
 *
 * Why a wrapper:
 *  - The reveal-seed UI wants `List<String>` it can render and
 *    copy-to-clipboard. Iterating a `MnemonicCode` yields `char[]`
 *    per word so callers don't have to convert.
 *  - The restore UI accepts a single string of space-separated
 *    words and validates it. Validation is split out so the UI
 *    can render the specific error ("not 25 words", "unknown
 *    word", "bad checksum").
 *  - `MnemonicCode` is `Closeable` and zeros its char buffers on
 *    close. Helpers below honour that — the caller doesn't have
 *    to remember to close().
 */
object SeedMnemonic {

    private const val EXPECTED_WORDS = 25

    /**
     * Encode a 32-byte seed as 25 Electrum-style English words.
     * Uses English locale; mollyim's wordlist supports several
     * other languages — adding a picker is a future polish step.
     */
    fun toWords(seed: ByteArray): List<String> {
        require(seed.size == 32) { "seed must be 32 bytes, got ${seed.size}" }
        val code = MoneroMnemonic.generateMnemonic(seed, Locale.ENGLISH)
            ?: error("MoneroMnemonic.generateMnemonic returned null for 32-byte seed")
        return code.use { it.map { word -> String(word) } }
    }

    /**
     * Decode 25 space-separated Electrum-style words back to the
     * 32-byte seed. Returns null on any decode error — bad word
     * count, unknown word, bad checksum. The caller surfaces a
     * generic "seed not valid" message rather than leak the
     * specific failure (defending against timing-style oracles).
     */
    fun fromWords(input: String): ByteArray? {
        val normalised = input.trim().lowercase(Locale.ENGLISH)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (normalised.size != EXPECTED_WORDS) return null
        return try {
            val code = MoneroMnemonic.recoverEntropy(normalised.joinToString(" "))
                ?: return null
            code.use { it.entropy.copyOf() }
        } catch (_: Throwable) {
            null
        }
    }

    /** Decode a 64-char hex seed → 32 bytes, or null on malformed input. */
    fun fromHex(input: String): ByteArray? {
        val s = input.trim().filter { !it.isWhitespace() }
        if (s.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
