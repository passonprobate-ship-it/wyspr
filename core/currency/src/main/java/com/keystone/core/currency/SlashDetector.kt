package com.keystone.core.currency

import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.crypto.KeystoreManager
import java.security.SecureRandom

/**
 * Turns a [WalletService.DoubleSpend] outcome into a signed [Slash]
 * envelope, and validates inbound slashes from peers.
 *
 * The detector is the only path that legitimately issues a slash. The
 * cryptographic invariants it relies on (CURRENCY.md §6.1) are:
 *
 *   - Both evidence transfers verify under the same target key.
 *   - Both share the same `(sender, seq)` — that's what makes them a
 *     conflict, not just two separate transfers.
 *   - They differ in body — a benign retransmission isn't a slash.
 *   - They live in the same community.
 *
 * The witness signature is over the slash's canonical [Slash.signedBytes],
 * which includes the canonically-ordered evidence pair. Two honest
 * witnesses observing the same conflict therefore produce slashes with
 * identical signedBytes (different signatures); the database PK
 * `(community, SLASH, target)` keeps only the first one.
 */
class SlashDetector(
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    @Suppress("unused") private val random: SecureRandom = SecureRandom(),
) {

    sealed interface ValidationResult
    object Valid : ValidationResult
    data class Invalid(val reason: Reason) : ValidationResult

    enum class Reason {
        EVIDENCE_TRANSFERS_IDENTICAL,
        EVIDENCE_SENDERS_DIFFER,
        EVIDENCE_SEQS_DIFFER,
        EVIDENCE_SENDER_NOT_TARGET,
        EVIDENCE_COMMUNITY_MISMATCH,
        EVIDENCE_A_SIGNATURE_INVALID,
        EVIDENCE_B_SIGNATURE_INVALID,
        WITNESS_SIGNATURE_INVALID,
    }

    /**
     * Build and sign a Slash certificate from a confirmed double-spend.
     * Caller MUST have verified both transfer signatures before
     * constructing the [WalletService.DoubleSpend] — see WalletService
     * recordInboundTransfer notes.
     */
    fun create(doubleSpend: WalletService.DoubleSpend): Slash {
        val a = doubleSpend.existing
        val b = doubleSpend.incoming
        require(a.sender.contentEquals(b.sender)) {
            "DoubleSpend has mismatched senders — not a slashable conflict"
        }
        require(a.seq == b.seq) {
            "DoubleSpend has mismatched seq — not a slashable conflict"
        }
        require(!a.signedBytes().contentEquals(b.signedBytes())) {
            "DoubleSpend has identical content — benign retransmission"
        }
        require(a.community.contentEquals(b.community)) {
            "DoubleSpend has mismatched communities"
        }

        val (orderedA, orderedB) = Slash.orderEvidence(a, b)
        val witness = keystore.loadOrCreateIdentityKey().publicKey
        val issuedAt = clock()
        val signedBytes = Slash.signedBytesOf(
            community = a.community,
            target = a.sender,
            evidenceA = orderedA,
            evidenceB = orderedB,
            issuedAt = issuedAt,
            witness = witness,
        )
        val signature = CurrencySigning.sign(keystore, signedBytes)
        return Slash(
            version = Slash.VERSION,
            community = a.community.copyOf(),
            target = a.sender.copyOf(),
            evidenceA = orderedA,
            evidenceB = orderedB,
            issuedAt = issuedAt,
            witness = witness,
            signature = signature,
        )
    }

    /**
     * Full validation of an inbound slash — structural checks plus all
     * three Ed25519 signatures (witness + both evidence transfers).
     * Returns [Valid] only when every check passes.
     */
    fun validate(slash: Slash): ValidationResult {
        // Structural checks first — cheapest, most decisive.
        val a = slash.evidenceA
        val b = slash.evidenceB
        if (a.signedBytes().contentEquals(b.signedBytes())) {
            return Invalid(Reason.EVIDENCE_TRANSFERS_IDENTICAL)
        }
        if (!a.sender.contentEquals(b.sender)) {
            return Invalid(Reason.EVIDENCE_SENDERS_DIFFER)
        }
        if (a.seq != b.seq) {
            return Invalid(Reason.EVIDENCE_SEQS_DIFFER)
        }
        if (!a.sender.contentEquals(slash.target)) {
            return Invalid(Reason.EVIDENCE_SENDER_NOT_TARGET)
        }
        if (!a.community.contentEquals(slash.community) ||
            !b.community.contentEquals(slash.community)
        ) {
            return Invalid(Reason.EVIDENCE_COMMUNITY_MISMATCH)
        }
        // Cryptographic checks — verify all three signatures.
        if (!CurrencySigning.verify(sodium, a)) {
            return Invalid(Reason.EVIDENCE_A_SIGNATURE_INVALID)
        }
        if (!CurrencySigning.verify(sodium, b)) {
            return Invalid(Reason.EVIDENCE_B_SIGNATURE_INVALID)
        }
        if (!CurrencySigning.verify(sodium, slash.witness, slash.signedBytes(), slash.signature)) {
            return Invalid(Reason.WITNESS_SIGNATURE_INVALID)
        }
        return Valid
    }
}
