package com.keystone.feature.monero

/**
 * Native-crypto primitives Keystone needs to be a real Monero
 * wallet. Intentionally an interface with no working
 * implementation in the v0.7.0a slice — the engine ships in
 * v0.7.0b once the monerujo JNI binding is integrated.
 *
 * **Why a stub now?** Locking the API shape before adding ~200 KB
 * of native libraries lets the rest of the wallet code
 * ([MoneroWalletService], the UI, the RPC client) compile and be
 * reviewed without depending on the GPL binding yet. When the
 * binding lands, it replaces [NotImplementedEngine] in Hilt and
 * everything else snaps into place.
 *
 * **Why these methods?** Three categories of Monero crypto need
 * to happen on-device:
 *
 *  1. *Address derivation* — given the wallet's spend / view keys
 *     plus an optional sub-address index, produce the user-facing
 *     `4...` or `8...` address string. Pure-Kotlin in principle
 *     (the math is ed25519 + Keccak) but error-prone enough that
 *     we want the proven C++ implementation.
 *  2. *Block scan* — given a remote-fetched block + the view key,
 *     identify outputs paid to us and decrypt their amounts. The
 *     RingCT/CLSAG math is too heavy to re-implement in Kotlin.
 *  3. *Transaction signing* — given a destination, amount, ring
 *     decoys, and the spend key, produce a signed blob ready to
 *     submit. The ring-signature math is the largest single chunk
 *     of code in monero-core; we will not re-implement.
 *
 * **Library decision.** Confirmed 2026-05-19: GPL contagion is
 * acceptable. The combined APK ships under GPLv3 once this engine
 * is bound. Bound implementation will live in
 * `MoneroJniCryptoEngine.kt` (added in v0.7.0b) and call into the
 * Monerujo monero-android JNI surface.
 *
 * Until then, [NotImplementedEngine] is bound by [di.MoneroModule]
 * and throws with a pointer to the next-steps doc.
 */
interface MoneroCryptoEngine {

    /**
     * Standard primary address for [spendPub] / [viewPub] on
     * [nettype]. Result is the user-facing `4...` (mainnet) or
     * `9...`/`5...` (testnet/stagenet) string.
     */
    fun primaryAddress(
        spendPub: ByteArray,
        viewPub: ByteArray,
        nettype: Nettype,
    ): String

    /**
     * Given a serialised Monero block + the wallet's view secret,
     * return outputs that belong to this wallet. The returned list
     * is paired (output index, amount) — addresses are derived
     * lazily via [primaryAddress] or the sub-address variants.
     */
    fun scanBlock(
        blockBlob: ByteArray,
        viewSec: ByteArray,
        spendPub: ByteArray,
    ): List<ScannedOutput>

    /**
     * Sign a transaction. Caller supplies a fully-prepared tx
     * skeleton (destinations, decoys, fee) plus the spend secret.
     * Return value is the signed tx ready for daemon submission.
     */
    fun signTransaction(
        txSkeleton: ByteArray,
        spendSec: ByteArray,
    ): ByteArray

    /** Output discovered by [scanBlock]. */
    data class ScannedOutput(
        val outputIndex: Int,
        val amount: Long,
        val keyImage: ByteArray,
    )

    /**
     * Which Monero network we're talking to. Mainnet is the only
     * one Keystone surfaces in production; testnet/stagenet exist
     * for development and threat-model exercises.
     */
    enum class Nettype { Mainnet, Stagenet, Testnet }
}

/**
 * Bound by Hilt in the v0.7.0a scaffold slice. Every method
 * throws — Keystone's wallet UI checks at construction time and
 * renders a "wallet engine not yet bundled" placeholder rather
 * than calling through.
 */
class NotImplementedMoneroCryptoEngine : MoneroCryptoEngine {

    override fun primaryAddress(
        spendPub: ByteArray,
        viewPub: ByteArray,
        nettype: MoneroCryptoEngine.Nettype,
    ): String = throwNotImplemented()

    override fun scanBlock(
        blockBlob: ByteArray,
        viewSec: ByteArray,
        spendPub: ByteArray,
    ): List<MoneroCryptoEngine.ScannedOutput> = throwNotImplemented()

    override fun signTransaction(
        txSkeleton: ByteArray,
        spendSec: ByteArray,
    ): ByteArray = throwNotImplemented()

    private fun throwNotImplemented(): Nothing {
        throw IllegalStateException(
            "Monero JNI crypto engine not yet bundled in this build " +
                "(see NEXT-STEPS.md §10, sprint v0.7.0b).",
        )
    }
}
