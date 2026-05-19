package com.keystone.core.trust

import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.Fingerprint
import com.keystone.core.identity.PublicKey

/**
 * The Handshake Protocol — establishes a Root of Trust between two
 * physically co-located devices via QR scan + Noise XX.
 *
 * SECURITY-MODEL.md §3.3, PROTOCOLS.md §3.
 *
 *     Inviter                           Invitee
 *     ────────                          ────────
 *     display QR_inv  ──────────►       (scan)
 *     (scan)          ◄──────────       display QR_inv
 *     [user compares fingerprints; both tap "match"]
 *     ── BLE GATT connect ─────────────────────────►
 *     ── Noise XX (3 messages) ────────────────────►
 *     ── InvitationCertificate ────────────────────►
 *                                       persist TrustEdge
 *     persist TrustEdge ◄── IdentityClaim ──────────
 *     ── ACK ──────────────────────────────────────►
 */
interface HandshakeProtocol {

    /** Generate a fresh QR for *me* to display to the peer. */
    fun mintInviterQr(communityId: CommunityId): HandshakeQr

    /** Generate a fresh QR for *me* (as Invitee) to display. */
    fun mintInviteeQr(communityId: CommunityId): HandshakeQr

    /**
     * Begin the handshake state machine after both QRs have been scanned
     * and fingerprints compared. The returned [HandshakeSession] runs the
     * Noise XX exchange + InvitationCertificate exchange over the
     * supplied [com.keystone.core.transport.Link], persists the
     * resulting trust edge on success, and emits state transitions on
     * its [HandshakeSession.state] flow.
     *
     * The link MUST already be open. The session does not own the
     * lifecycle of the underlying transport — callers close it after
     * the session terminates.
     */
    fun open(
        role: Role,
        localQr: HandshakeQr,
        peerQr: HandshakeQr,
        link: com.keystone.core.transport.Link,
    ): HandshakeSession

    enum class Role { Inviter, Invitee }
}

/**
 * PROTOCOLS.md §3.1 — CBOR over base32, max 5 minutes old.
 *
 * NOTE on equality: this is intentionally NOT a `data class` because the
 * `ByteArray` fields (and ByteArray-backed value-class fields like
 * `CommunityId` / `PublicKey`) would inherit reference-equality from
 * Java arrays. Explicit equals/hashCode below use content equality so
 * `assertEquals(qrA, qrB)` and `setOf(qrA, qrB)` behave correctly.
 */
class HandshakeQr(
    val version: Int,
    val communityId: CommunityId,
    val identityPub: PublicKey,
    val ephemeralPub: ByteArray,
    val nonce: ByteArray,
    val mintedAt: Long,
) {
    init {
        require(version == VERSION) { "unsupported QR version: $version" }
        require(ephemeralPub.size == EPHEMERAL_LENGTH)
        require(nonce.size == NONCE_LENGTH)
    }

    val fingerprint: Fingerprint get() = identityPub.fingerprint

    fun isFresh(nowSeconds: Long, maxAgeSeconds: Long = MAX_AGE_SECONDS): Boolean =
        nowSeconds - mintedAt in 0..maxAgeSeconds

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeQr) return false
        return version == other.version &&
            mintedAt == other.mintedAt &&
            communityId.bytes.contentEquals(other.communityId.bytes) &&
            identityPub.bytes.contentEquals(other.identityPub.bytes) &&
            ephemeralPub.contentEquals(other.ephemeralPub) &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + mintedAt.hashCode()
        r = 31 * r + communityId.bytes.contentHashCode()
        r = 31 * r + identityPub.bytes.contentHashCode()
        r = 31 * r + ephemeralPub.contentHashCode()
        r = 31 * r + nonce.contentHashCode()
        return r
    }

    companion object {
        const val VERSION = 1
        const val EPHEMERAL_LENGTH = 32
        const val NONCE_LENGTH = 16
        const val MAX_AGE_SECONDS = 5L * 60
    }
}

interface HandshakeSession {
    val state: kotlinx.coroutines.flow.StateFlow<State>

    /**
     * Drive the handshake to completion. Suspends until both sides have
     * exchanged InvitationCertificate / IdentityClaim and ACKed.
     *
     * On any verification failure: returns [Outcome.Aborted] with reason,
     * the connection is dropped, and the peer is quarantined for 24h.
     */
    suspend fun run(): Outcome

    /** User-driven cancel — also writes a 24h quarantine. */
    fun cancel()

    enum class State {
        AwaitingTransport,
        NoiseHandshakeInProgress,
        ExchangingCertificates,
        Committed,
        Aborted,
    }

    sealed interface Outcome {
        data class Committed(val edge: TrustEdge) : Outcome
        data class Aborted(val reason: AbortReason) : Outcome
    }

    enum class AbortReason {
        QrStale,
        FingerprintMismatch,        // user tapped "no match"
        ChannelBindingMismatch,     // crypto detected MITM
        SignatureInvalid,
        CertificateExpired,
        UserCancelled,
        TransportFailed,
        /**
         * Inviter side: local device is not authorized to issue trust
         * certificates in the active community. SECURITY-MODEL.md §3.4
         * — only Roots and Full members may issue. The TrustGraph
         * consulted at handshake start decides.
         */
        NotAuthorized,
    }
}
