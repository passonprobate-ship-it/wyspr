package com.wyspr.feature.onboarding

/**
 * The onboarding state machine. Implements the user-facing side of
 * SECURITY-MODEL.md §3.3 — dual-QR handshake.
 *
 * Screens, in strict order, no back-stack escape:
 *
 *   1. Welcome           — explains there is no signup; you need an inviter
 *   2. RolePicker        — "I have an invitation" vs "I'm inviting someone"
 *   3. KeyGeneration     — runs once, ever; generates hardware-keystore identity
 *   4. DisplayQr         — shows my HandshakeQr; regenerated every 5 minutes
 *   5. ScanPeerQr        — camera scan of the peer's QR
 *   6. CompareFingerprints — side-by-side compare; both users tap "match"
 *   7. RunHandshake      — Noise XX + InvitationCertificate exchange (spinner)
 *   8. Result            — Committed → home; Aborted → reason + retry CTA
 *
 * Failure at any step beyond (6) writes a 24h quarantine for the peer
 * public key and disables retry until the window expires.
 */
sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data object RolePicker : OnboardingStep
    data object KeyGeneration : OnboardingStep
    data object DisplayQr : OnboardingStep
    data object ScanPeerQr : OnboardingStep
    data object CompareFingerprints : OnboardingStep
    data object RunHandshake : OnboardingStep
    data class Result(val ok: Boolean, val message: String) : OnboardingStep
}
