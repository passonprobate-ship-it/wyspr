package com.keystone.core.trust

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.keystone.core.crypto.Cbor
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.crypto.NoiseSession
import com.keystone.core.crypto.NoiseSessionImpl
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.TrustEdgeEntity
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.Link
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG_HS = "HandshakeProtocol"
private fun ByteArray.hexHead(n: Int = 6): String =
    take(n).joinToString("") { "%02x".format(it) } + "…"

/**
 * The Inviter mints, scans, runs Noise XX, signs an InvitationCertificate
 * for the Invitee's identity public key. The Invitee mints, scans, runs
 * Noise XX, receives the cert, verifies it against the inviter's claimed
 * identity public key, then commits a trust edge.
 *
 * After Noise reaches Transport, message exchange uses the frame layer:
 *
 *     send  -> noise.encrypt(payload) -> link.send(framed)
 *     recv  -> link.incoming() -> noise.decrypt(payload)
 *
 * The framing is the Transport layer's job — the Link contract already
 * guarantees one [send] = one frame on the receiver's [incoming].
 */
class HandshakeProtocolImpl(
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val database: KeystoneDatabase,
    /**
     * Consulted at the top of [HandshakeSession.run] on the Inviter
     * side to decide whether this device may issue a fresh
     * [InvitationCertificate]. Optional — when null (e.g. a unit
     * test that doesn't care about the authorization gate), the
     * check is skipped and the previous "anyone can issue" behaviour
     * stands. Production wiring via Hilt always passes a real one.
     */
    private val trustGraphService: TrustGraphService? = null,
    /**
     * Sampled at QR-mint time to populate [HandshakeQr.onionAddress].
     * Returns null while Tor is bootstrapping; the resulting QR
     * advertises BLE-only and the peer falls back accordingly.
     * Tests pass `{ null }`; production wiring reads
     * `TorBackend.onionAddress.value` via Hilt.
     */
    private val localOnion: () -> String? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) : HandshakeProtocol {

    /**
     * The QR plus its matching ephemeral private key. The secret is the
     * caller's responsibility — it MUST be zeroed when the handshake
     * session ends or is abandoned. The implementation deliberately
     * keeps no internal reference, so a leaked MintedQr is the caller's
     * problem and not a class-level memory leak.
     */
    data class MintedQr(
        val qr: HandshakeQr,
        val ephemeralSecret: ByteArray,
    ) {
        fun zeroize() { ephemeralSecret.fill(0) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MintedQr) return false
            return qr == other.qr && ephemeralSecret.contentEquals(other.ephemeralSecret)
        }
        override fun hashCode(): Int = 31 * qr.hashCode() + ephemeralSecret.contentHashCode()
    }

    override fun mintInviterQr(communityId: CommunityId): HandshakeQr = mint(communityId)
    override fun mintInviteeQr(communityId: CommunityId): HandshakeQr = mint(communityId)

    fun mintWithSecret(communityId: CommunityId): MintedQr {
        val identity = keystore.loadOrCreateIdentityKey()
        val ephemeralPub = ByteArray(Box.PUBLICKEYBYTES)
        val ephemeralSec = ByteArray(Box.SECRETKEYBYTES)
        require(sodium.cryptoBoxKeypair(ephemeralPub, ephemeralSec)) {
            "libsodium cryptoBoxKeypair failed"
        }
        val nonce = ByteArray(HandshakeQr.NONCE_LENGTH).also { random.nextBytes(it) }
        // Onion may legitimately be null (Tor still bootstrapping, or
        // not built in some flavour). When non-null, [HandshakeQr]'s
        // init block enforces the canonical 56-char length.
        val onion = localOnion()
        val qr = HandshakeQr(
            version = HandshakeQr.VERSION,
            communityId = communityId,
            identityPub = PublicKey(identity.publicKey),
            ephemeralPub = ephemeralPub,
            nonce = nonce,
            mintedAt = clock(),
            onionAddress = onion,
        )
        return MintedQr(qr = qr, ephemeralSecret = ephemeralSec)
    }

    private fun mint(communityId: CommunityId): HandshakeQr {
        val minted = mintWithSecret(communityId)
        minted.zeroize()
        return minted.qr
    }

    override fun open(
        role: HandshakeProtocol.Role,
        localQr: HandshakeQr,
        peerQr: HandshakeQr,
        link: Link,
    ): HandshakeSession {
        require(localQr.communityId.bytes.contentEquals(peerQr.communityId.bytes)) {
            "peer is from a different community"
        }
        // A self-pairing would write a TrustEdge(from=self, to=self) into the
        // graph, which could later masquerade as an independent path in
        // K-quorum. Channel binding can't catch this — both sides hold the
        // same identity key.
        require(!localQr.identityPub.bytes.contentEquals(peerQr.identityPub.bytes)) {
            "refusing to handshake with self"
        }
        val now = clock()
        require(localQr.isFresh(now)) { "local QR is stale" }
        require(peerQr.isFresh(now)) { "peer QR is stale (>5min)" }

        return RealSession(
            role = role,
            localQr = localQr,
            peerQr = peerQr,
            link = link,
            keystore = keystore,
            sodium = sodium,
            database = database,
            trustGraphService = trustGraphService,
            clock = clock,
            random = random,
        )
    }
}

/**
 * Builds the Noise prologue. PROTOCOLS.md §3.2 — both sides MUST compute
 * the same bytes here, otherwise message 2's hash check fails and Noise
 * aborts the session.
 *
 *     prologue = communityId(32)
 *             || inviter_nonce(16)
 *             || invitee_nonce(16)
 *             || inviter_ephPub(32)
 *             || invitee_ephPub(32)
 *
 * The QR roles aren't symmetric here: the "inviter QR" contribution
 * comes from whichever party is the Inviter. Both sides know who's who
 * because the role is part of the user-driven flow (RolePicker), and
 * both sides hold both QRs.
 */
internal fun buildPrologue(
    inviterQr: HandshakeQr,
    inviteeQr: HandshakeQr,
): ByteArray = inviterQr.communityId.bytes +
    inviterQr.nonce +
    inviteeQr.nonce +
    inviterQr.ephemeralPub +
    inviteeQr.ephemeralPub

private class RealSession(
    private val role: HandshakeProtocol.Role,
    private val localQr: HandshakeQr,
    private val peerQr: HandshakeQr,
    private val link: Link,
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val database: KeystoneDatabase,
    private val trustGraphService: TrustGraphService?,
    private val clock: () -> Long,
    private val random: SecureRandom,
) : HandshakeSession {

    private val _state = MutableStateFlow(HandshakeSession.State.AwaitingTransport)
    override val state: StateFlow<HandshakeSession.State> = _state.asStateFlow()

    private var cancelled = false

    override suspend fun run(): HandshakeSession.Outcome {
        if (cancelled) return abort(HandshakeSession.AbortReason.UserCancelled)

        // Inviter-side authorization gate. Consult the local trust
        // graph before doing any I/O — if this device is not allowed
        // to mint a fresh InvitationCertificate, fail fast with
        // NotAuthorized so the user sees a clear error instead of a
        // protocol-level abort downstream. The Invitee side has no
        // analogous gate because it only verifies & ingests certs;
        // it does not produce them.
        if (role == HandshakeProtocol.Role.Inviter && trustGraphService != null) {
            val localPub = PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
            if (!trustGraphService.canIssueInvitations(localPub)) {
                return abort(HandshakeSession.AbortReason.NotAuthorized)
            }
        }

        val inviterQr: HandshakeQr
        val inviteeQr: HandshakeQr
        when (role) {
            HandshakeProtocol.Role.Inviter -> { inviterQr = localQr; inviteeQr = peerQr }
            HandshakeProtocol.Role.Invitee -> { inviterQr = peerQr; inviteeQr = localQr }
        }

        val prologue = buildPrologue(inviterQr, inviteeQr)
        val noiseRole = when (role) {
            HandshakeProtocol.Role.Inviter -> NoiseSession.Role.Initiator
            HandshakeProtocol.Role.Invitee -> NoiseSession.Role.Responder
        }

        val noise = NoiseSessionImpl(noiseRole)
        try {
            _state.value = HandshakeSession.State.NoiseHandshakeInProgress
            noise.start(prologue, keystore)

            // -- XX message exchange ------------------------------------
            //
            //   Initiator -> e                 (msg 1)
            //   Responder -> e, ee, s, es      (msg 2)
            //   Initiator -> s, se             (msg 3)
            //
            // After message 3 both sides split into transport ciphers.
            when (noiseRole) {
                NoiseSession.Role.Initiator -> {
                    sendFrame(noise.writeHandshakeMessage())                              // m1
                    val m2 = receiveFrame()
                        ?: return abort(HandshakeSession.AbortReason.TransportFailed)
                    noise.readHandshakeMessage(m2)                                        // m2
                    sendFrame(noise.writeHandshakeMessage())                              // m3
                }
                NoiseSession.Role.Responder -> {
                    val m1 = receiveFrame()
                        ?: return abort(HandshakeSession.AbortReason.TransportFailed)
                    noise.readHandshakeMessage(m1)
                    sendFrame(noise.writeHandshakeMessage())                              // m2
                    val m3 = receiveFrame()
                        ?: return abort(HandshakeSession.AbortReason.TransportFailed)
                    noise.readHandshakeMessage(m3)
                }
            }

            check(noise.state == NoiseSession.State.Transport) {
                "Noise XX did not reach transport state (got ${noise.state})"
            }

            // -- Channel binding check ----------------------------------
            // The remote static key the handshake exchanged must match
            // the X25519 derivation of the identity public key in the
            // peer QR. A mismatch means the QR fingerprint we showed
            // the user doesn't correspond to the peer we just spoke to:
            // MITM. SECURITY-MODEL.md §3.3.
            val expectedRemoteStatic = ed25519ToX25519Public(peerQr.identityPub.bytes)
                ?: return abort(HandshakeSession.AbortReason.ChannelBindingMismatch)
            val actualRemoteStatic = noise.remoteStaticPublicKey
            if (!expectedRemoteStatic.contentEquals(actualRemoteStatic)) {
                return abort(HandshakeSession.AbortReason.ChannelBindingMismatch)
            }

            _state.value = HandshakeSession.State.ExchangingCertificates

            // -- InvitationCertificate exchange --------------------------
            val localIdentity = keystore.loadOrCreateIdentityKey()
            val localPub = PublicKey(localIdentity.publicKey)
            val peerPub = peerQr.identityPub

            val edge: TrustEdge = when (role) {
                HandshakeProtocol.Role.Inviter -> {
                    // Inviter signs a cert vouching for the Invitee.
                    val cert = InvitationCertificate.issue(
                        keystore = keystore,
                        inviterPub = localPub,
                        inviteePub = peerPub,
                        communityId = localQr.communityId,
                        vouchLevel = InvitationCertificate.VouchLevel.PROVISIONAL,
                        now = clock(),
                        random = random,
                    )
                    Log.d(
                        TAG_HS,
                        "Inviter: issued cert " +
                            "inviterPub=${cert.inviterPub.bytes.hexHead()} " +
                            "inviteePub=${cert.inviteePub.bytes.hexHead()} " +
                            "community=${cert.communityId.bytes.hexHead()} " +
                            "issuedAt=${cert.issuedAt} expiresAt=${cert.expiresAt} " +
                            "sigLen=${cert.signature.size} wireLen=${cert.wireBytes().size}",
                    )
                    sendFrame(noise.encrypt(cert.wireBytes()))
                    // Invitee echoes an ack (empty payload) — confirms it
                    // received and accepted. Any byte != 0 means rejected.
                    val ack = receiveFrame()
                        ?.let { noise.decrypt(it) }
                        ?: return abort(HandshakeSession.AbortReason.TransportFailed)
                    if (ack.isEmpty() || ack[0] != ACK_OK) {
                        return abort(HandshakeSession.AbortReason.UserCancelled)
                    }
                    TrustEdge(
                        from = localPub,
                        to = peerPub,
                        vouchLevel = cert.vouchLevel,
                        establishedAt = cert.issuedAt,
                        certBlob = cert.wireBytes(),
                        certSigner = localPub,
                        peerOnion = peerQr.onionAddress,
                    )
                }
                HandshakeProtocol.Role.Invitee -> {
                    val rawCert = receiveFrame()
                        ?.let { noise.decrypt(it) }
                        ?: run {
                            Log.w(TAG_HS, "Invitee: cert frame missing (transport failed)")
                            return abort(HandshakeSession.AbortReason.TransportFailed)
                        }
                    Log.d(TAG_HS, "Invitee: received rawCert len=${rawCert.size}")
                    val cert = runCatching { InvitationCertificate.fromWire(rawCert) }
                        .getOrElse {
                            Log.w(TAG_HS, "Invitee: fromWire parse failed: ${it::class.simpleName}: ${it.message}")
                            return abort(HandshakeSession.AbortReason.SignatureInvalid)
                        }
                    Log.d(
                        TAG_HS,
                        "Invitee: parsed cert " +
                            "inviterPub=${cert.inviterPub.bytes.hexHead()} " +
                            "inviteePub=${cert.inviteePub.bytes.hexHead()} " +
                            "community=${cert.communityId.bytes.hexHead()} " +
                            "issuedAt=${cert.issuedAt} expiresAt=${cert.expiresAt} " +
                            "sigLen=${cert.signature.size} now=${clock()}",
                    )
                    if (cert.issuedAt > clock() + CLOCK_SKEW_SECONDS) {
                        Log.w(TAG_HS, "Invitee: cert issuedAt in the future (issuedAt=${cert.issuedAt} now=${clock()} skew=$CLOCK_SKEW_SECONDS)")
                        return abort(HandshakeSession.AbortReason.CertificateExpired)
                    }
                    if (cert.expiresAt + CLOCK_SKEW_SECONDS <= clock()) {
                        Log.w(TAG_HS, "Invitee: cert expired (expiresAt=${cert.expiresAt} now=${clock()} skew=$CLOCK_SKEW_SECONDS)")
                        return abort(HandshakeSession.AbortReason.CertificateExpired)
                    }
                    val inviterMatches = cert.inviterPub.bytes.contentEquals(peerPub.bytes)
                    val inviteeMatches = cert.inviteePub.bytes.contentEquals(localPub.bytes)
                    val communityMatches = cert.communityId.bytes.contentEquals(localQr.communityId.bytes)
                    if (!inviterMatches || !inviteeMatches || !communityMatches) {
                        Log.w(
                            TAG_HS,
                            "Invitee: cert key/community mismatch — " +
                                "inviterMatches=$inviterMatches " +
                                "(cert=${cert.inviterPub.bytes.hexHead()} expected=${peerPub.bytes.hexHead()}) " +
                                "inviteeMatches=$inviteeMatches " +
                                "(cert=${cert.inviteePub.bytes.hexHead()} expected=${localPub.bytes.hexHead()}) " +
                                "communityMatches=$communityMatches " +
                                "(cert=${cert.communityId.bytes.hexHead()} expected=${localQr.communityId.bytes.hexHead()})",
                        )
                        return abort(HandshakeSession.AbortReason.SignatureInvalid)
                    }
                    if (!cert.verify(sodium, clock(), CLOCK_SKEW_SECONDS)) {
                        Log.w(
                            TAG_HS,
                            "Invitee: cert.verify() returned false — " +
                                "issuedAt=${cert.issuedAt} expiresAt=${cert.expiresAt} now=${clock()} " +
                                "sigLen=${cert.signature.size} inviterPub=${cert.inviterPub.bytes.hexHead()}",
                        )
                        return abort(HandshakeSession.AbortReason.SignatureInvalid)
                    }
                    Log.d(TAG_HS, "Invitee: cert verified ✓, sending ACK")
                    sendFrame(noise.encrypt(byteArrayOf(ACK_OK)))
                    TrustEdge(
                        from = peerPub,
                        to = localPub,
                        vouchLevel = cert.vouchLevel,
                        establishedAt = cert.issuedAt,
                        certBlob = cert.wireBytes(),
                        certSigner = peerPub,
                        peerOnion = peerQr.onionAddress,
                    )
                }
            }

            persistTrustEdge(edge)
            _state.value = HandshakeSession.State.Committed
            return HandshakeSession.Outcome.Committed(edge)
        } catch (t: Throwable) {
            // AEADBadTagException extends BadPaddingException; either way
            // it's an auth failure → the peer is lying or wired wrong.
            // Anything else is treated as a transport-level issue.
            Log.w(TAG_HS, "run(): caught ${t::class.simpleName}: ${t.message}", t)
            return abort(
                if (t is javax.crypto.BadPaddingException) {
                    HandshakeSession.AbortReason.SignatureInvalid
                } else {
                    HandshakeSession.AbortReason.TransportFailed
                }
            )
        } finally {
            noise.close()
        }
    }

    override fun cancel() {
        cancelled = true
        _state.value = HandshakeSession.State.Aborted
    }

    private suspend fun persistTrustEdge(edge: TrustEdge) {
        database.open()
        database.trustEdgeDao.upsert(
            TrustEdgeEntity(
                fromPub = edge.from.bytes,
                toPub = edge.to.bytes,
                vouchLevel = edge.vouchLevel.name,
                establishedAt = edge.establishedAt,
                certBlob = edge.certBlob,
                certSigner = edge.certSigner.bytes,
                peerOnion = edge.peerOnion,
            )
        )
    }

    private fun abort(reason: HandshakeSession.AbortReason): HandshakeSession.Outcome.Aborted {
        _state.value = HandshakeSession.State.Aborted
        return HandshakeSession.Outcome.Aborted(reason)
    }

    private suspend fun sendFrame(bytes: ByteArray) {
        require(bytes.size <= NoiseSession.MAX_FRAME_BYTES) {
            "frame ${bytes.size} > MAX_FRAME_BYTES ${NoiseSession.MAX_FRAME_BYTES}"
        }
        link.send(bytes)
    }

    private suspend fun receiveFrame(): ByteArray? =
        withTimeoutOrNull(FRAME_TIMEOUT_MS) { link.incoming().firstOrNull() }

    /**
     * Convert an Ed25519 public key into the matching X25519 public
     * key — the same conversion the keystore performs on the secret
     * side. Returns null if libsodium rejects the input.
     */
    private fun ed25519ToX25519Public(edPub: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (sodium.convertPublicKeyEd25519ToCurve25519(out, edPub)) out else null
    }

    companion object {
        /**
         * Per-frame receive timeout. BLE GATT typically completes in
         * <1s, but Reticulum/LoRa backbones can take 10–30s per round
         * trip. 60s is a permissive ceiling that covers both; the
         * Noise XX exchange is only three messages so total worst-
         * case is ~3min on LoRa, which is acceptable for a one-time
         * onboarding handshake. Tighten back to 15s for BLE-only
         * deployments via a transport-aware override if needed.
         */
        const val FRAME_TIMEOUT_MS = 60_000L
        const val ACK_OK: Byte = 0x01
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
