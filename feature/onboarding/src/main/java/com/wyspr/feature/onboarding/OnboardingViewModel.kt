package com.wyspr.feature.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.CommunityService
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.Identity
import com.wyspr.core.identity.IdentityIssuer
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.transport.Transport
import com.wyspr.core.transport.TransportLifecycle
import com.wyspr.core.transport.bluetooth.BleTransport
import com.wyspr.core.trust.HandshakeProtocol
import com.wyspr.core.trust.HandshakeProtocolImpl
import com.wyspr.core.trust.HandshakeQr
import com.wyspr.core.trust.HandshakeQrCodec
import com.wyspr.core.trust.HandshakeSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Drives the onboarding state machine end-to-end:
 *
 *     RolePicker → KeyGeneration → Pair → CompareFingerprints →
 *     RunHandshake → Result
 *
 * The previous DisplayQr / ScanPeerQr split was folded into a single
 * Pair state — both sides now see their own QR and a live scanner on
 * the same screen, so neither has to hand the phone over to advance.
 *
 * Each transition is one-way; aborting from any step rewinds to Pair
 * where the user can retry with a fresh QR or restart from RolePicker.
 *
 * The handshake itself runs in a dedicated coroutine so the UI keeps
 * collecting [HandshakeSession.state] updates throughout. Cancelling
 * the screen calls [cancelHandshake] which terminates the coroutine
 * and the session.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: WysprDatabase,
    private val handshake: HandshakeProtocolImpl,
    private val communityService: CommunityService,
    private val bleTransport: BleTransport,
    private val transportLifecycle: TransportLifecycle,
    private val torBackend: TorBackend,
    @com.wyspr.core.transport.TorTransport private val torTransport: Transport,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Booting)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    private var activeSession: HandshakeSession? = null
    private var activeJob: Job? = null

    init {
        // On app launch, decide whether the user needs to see
        // onboarding at all. If there is already at least one trust
        // edge in the database, the user has already paired with
        // someone — emit AlreadyOnboarded so the host can route
        // straight to the main app. New installs fall through to
        // RolePicker as before. Any failure (e.g. database not yet
        // open and refuses to open) is treated as "not onboarded",
        // so the worst case is the user sees onboarding once more.
        viewModelScope.launch {
            val onboarded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!database.isOpen) {
                        try {
                            database.open()
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "DB open failed at boot, wiping stale file", e)
                            database.wipe()
                            database.open()
                        }
                    }
                    database.trustEdgeDao.count() > 0
                }.getOrDefault(false)
            }
            // Only transition out of Booting — don't trample a state
            // the user might have already advanced into (rotation,
            // process restart while in the middle of onboarding, etc.).
            if (_state.value is UiState.Booting) {
                _state.value = if (onboarded) UiState.AlreadyOnboarded else UiState.RolePicker
            }
        }
    }

    fun pickRole(role: Role) {
        _role.value = role
        _state.value = UiState.KeyGeneration(KeyGenStatus.Pending)
    }

    /**
     * Re-enter the pairing flow from a re-pair entry point (the "Pair
     * a new peer" CTA after first onboarding). The init block routes
     * existing users to [UiState.AlreadyOnboarded] so they don't see
     * onboarding twice; this method walks them BACK into the
     * RolePicker so they can add another peer.
     *
     * Idempotent — calling it during the active flow is a no-op
     * unless we're at [UiState.AlreadyOnboarded].
     */
    fun beginNewPairing() {
        if (_state.value is UiState.AlreadyOnboarded || _state.value is UiState.Booting) {
            _role.value = null
            _state.value = UiState.RolePicker
        }
    }

    fun back() {
        _state.value = when (val s = state.value) {
            // Booting and AlreadyOnboarded aren't user-interactive
            // and shouldn't expose a back affordance — but keep the
            // when exhaustive so future additions to UiState are caught
            // at compile time.
            UiState.Booting -> UiState.Booting
            UiState.AlreadyOnboarded -> UiState.AlreadyOnboarded
            UiState.RolePicker -> UiState.RolePicker
            is UiState.KeyGeneration -> UiState.RolePicker
            is UiState.Pair -> UiState.RolePicker
            is UiState.CompareFingerprints -> UiState.Pair(s.identity, s.backing, s.localQr, s.localQrBase32, peerQr = null)
            is UiState.RunHandshake -> { cancelHandshake(); UiState.Pair(s.identity, s.backing, s.localQr, s.localQrBase32, peerQr = null) }
            is UiState.Result -> UiState.Pair(s.identity, s.backing, s.localQr, s.localQrBase32, peerQr = null)
        }
    }

    fun generateIdentity(prompt: suspend () -> Boolean = { true }) {
        _state.value = UiState.KeyGeneration(KeyGenStatus.Running)
        viewModelScope.launch {
            try {
                if (keystore.needsAuth() && !prompt()) {
                    _state.value = UiState.KeyGeneration(
                        KeyGenStatus.Failed("Biometric authentication is required to lock the identity.")
                    )
                    return@launch
                }
                val (identity, backing, payload) = withContext(Dispatchers.IO) {
                    val backing = keystore.backing
                    check(backing != KeystoreManager.Backing.SOFTWARE_REJECTED) {
                        "This device has no hardware-backed keystore. Wyspr cannot run safely here."
                    }
                    val identity = IdentityIssuer.issue(keystore)
                    try {
                        database.open()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "DB open failed, wiping stale file and retrying", e)
                        database.wipe()
                        database.open()
                    }
                    val communityId = communityService.activeCommunityIdOrNull()
                        ?: communityService.foundNewCommunity(identity.publicKey.bytes)
                    val qr = handshake.mintInviterQr(communityId)
                    val base32 = HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(qr))
                    Triple(identity, backing, QrPayload(qr, base32))
                }
                _state.value = UiState.Pair(
                    identity = identity,
                    backing = backing,
                    qr = payload.qr,
                    base32 = payload.base32,
                )
            } catch (t: Throwable) {
                _state.value = UiState.KeyGeneration(KeyGenStatus.Failed(t.message ?: "unknown"))
            }
        }
    }

    fun refreshQr() {
        val s = state.value as? UiState.Pair ?: return
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                val qr = handshake.mintInviterQr(s.qr.communityId)
                QrPayload(qr, HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(qr)))
            }
            _state.value = s.copy(qr = payload.qr, base32 = payload.base32)
        }
    }

    fun onPeerQrScanned(peerQr: HandshakeQr) {
        val s = state.value as? UiState.Pair ?: return
        // Ignore re-emits once we've already locked in a peerQr — the
        // camera analyzer normally stops itself, but the user may have
        // rotated through Refresh QR between scans and we don't want
        // a stray second emit to silently replace the captured one.
        if (s.peerQr != null) return
        if (!peerQr.communityId.bytes.contentEquals(s.qr.communityId.bytes)) {
            // First-launch pairing: both devices auto-mint random
            // communityIds in generateIdentity(). To converge, the
            // Invitee adopts the Inviter's community and re-mints its
            // local QR; the Inviter cannot adopt because doing so
            // would orphan any pre-existing trust edges (founder
            // semantics in SECURITY-MODEL.md §3.1).
            //
            // For the Inviter, surface an explicit Aborted result so
            // the user knows their counterpart hasn't adopted yet —
            // tapping Retry from the Result screen returns to Pair
            // and the Inviter can wait for the Invitee's re-minted QR.
            when (_role.value) {
                Role.Invitee -> adoptPeerCommunityAndAdvance(s, peerQr)
                Role.Inviter, null -> {
                    _state.value = UiState.Result(
                        identity = s.identity, backing = s.backing,
                        localQr = s.qr, localQrBase32 = s.base32,
                        outcome = HandshakeSession.Outcome.Aborted(
                            HandshakeSession.AbortReason.TransportFailed,
                        ),
                    )
                }
            }
            return
        }
        val now = System.currentTimeMillis() / 1000
        val maxAge = if (peerQr.onionAddress != null) REMOTE_QR_MAX_AGE_SECONDS else HandshakeQr.MAX_AGE_SECONDS
        if (!peerQr.isFresh(now, maxAgeSeconds = maxAge)) {
            _state.value = UiState.Result(
                identity = s.identity, backing = s.backing,
                localQr = s.qr, localQrBase32 = s.base32,
                outcome = HandshakeSession.Outcome.Aborted(HandshakeSession.AbortReason.QrStale),
            )
            return
        }
        // Hold on the Pair screen so our own QR stays visible for the
        // peer to scan. The user taps Continue (onContinueFromPair) to
        // advance once they're satisfied the peer has scanned them.
        _state.value = s.copy(peerQr = peerQr)
    }

    /**
     * Advance from Pair → CompareFingerprints once this side has both
     * QRs in hand. The Noise prologue requires both nonces + ephPubs;
     * the peer's QR comes from [onPeerQrScanned], so we wait on a
     * deliberate user tap rather than auto-advancing.
     */
    fun onContinueFromPair() {
        val s = state.value as? UiState.Pair ?: return
        val peerQr = s.peerQr ?: return
        _state.value = UiState.CompareFingerprints(
            identity = s.identity,
            backing = s.backing,
            localQr = s.qr,
            localQrBase32 = s.base32,
            peerQr = peerQr,
        )
    }

    /**
     * Invitee side of pre-handshake community pairing. Switches the
     * device's active community to the scanned Inviter's, re-mints
     * the local QR (same identity, new ephemeral + nonce), and
     * advances to fingerprint compare using the peer QR we just
     * scanned. The peer QR's freshness is re-validated against the
     * post-adoption clock — adoption can take ~50ms on slow devices.
     */
    private fun adoptPeerCommunityAndAdvance(
        s: UiState.Pair,
        peerQr: HandshakeQr,
    ) {
        viewModelScope.launch {
            val (newLocalQr, newBase32) = withContext(Dispatchers.IO) {
                communityService.switchCommunity(peerQr.communityId)
                val q = handshake.mintInviterQr(peerQr.communityId)
                q to HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(q))
            }
            val now = System.currentTimeMillis() / 1000
            val maxAge = if (peerQr.onionAddress != null) REMOTE_QR_MAX_AGE_SECONDS else HandshakeQr.MAX_AGE_SECONDS
            if (!peerQr.isFresh(now, maxAgeSeconds = maxAge)) {
                _state.value = UiState.Result(
                    identity = s.identity, backing = s.backing,
                    localQr = newLocalQr, localQrBase32 = newBase32,
                    outcome = HandshakeSession.Outcome.Aborted(
                        HandshakeSession.AbortReason.QrStale,
                    ),
                )
                return@launch
            }
            // Same hold-on-Pair behaviour as the same-community path:
            // re-minted QR plus the scanned peer, waiting on the user's
            // Continue tap so our refreshed QR is visible to the peer.
            _state.value = UiState.Pair(
                identity = s.identity,
                backing = s.backing,
                qr = newLocalQr,
                base32 = newBase32,
                peerQr = peerQr,
            )
        }
    }

    /**
     * Called after the user taps "match". Accepts a suspending
     * permission gate that the UI plumbs to the activity-level
     * permission launcher. If the gate returns false we don't even
     * start BLE — we surface a transport-failed result with a
     * dedicated retry path.
     */
    fun onFingerprintsMatched(blePermissionGate: suspend () -> Boolean = { true }) {
        val s = state.value as? UiState.CompareFingerprints ?: return
        val role = _role.value ?: return
        viewModelScope.launch {
            val granted = blePermissionGate()
            if (!granted) {
                _state.value = UiState.Result(
                    identity = s.identity, backing = s.backing,
                    localQr = s.localQr, localQrBase32 = s.localQrBase32,
                    outcome = HandshakeSession.Outcome.Aborted(
                        HandshakeSession.AbortReason.TransportFailed,
                    ),
                )
                return@launch
            }
            startHandshake(s.identity, s.backing, s.localQr, s.localQrBase32, s.peerQr, role)
        }
    }

    fun onFingerprintsNoMatch() {
        val s = state.value as? UiState.CompareFingerprints ?: return
        _state.value = UiState.Result(
            identity = s.identity, backing = s.backing,
            localQr = s.localQr, localQrBase32 = s.localQrBase32,
            outcome = HandshakeSession.Outcome.Aborted(HandshakeSession.AbortReason.FingerprintMismatch),
        )
    }

    fun cancelHandshake() {
        activeJob?.cancel()
        activeSession?.cancel()
        activeJob = null
        activeSession = null
    }

    fun retryFromPair() {
        val s = state.value as? UiState.Result ?: return
        _state.value = UiState.Pair(s.identity, s.backing, s.localQr, s.localQrBase32, peerQr = null)
    }

    private fun startHandshake(
        identity: Identity,
        backing: KeystoreManager.Backing,
        localQr: HandshakeQr,
        localQrBase32: String,
        peerQr: HandshakeQr,
        role: Role,
    ) {
        cancelHandshake()
        val initialState = HandshakeSession.State.AwaitingTransport
        _state.value = UiState.RunHandshake(
            identity = identity, backing = backing,
            localQr = localQr, localQrBase32 = localQrBase32, peerQr = peerQr,
            sessionState = initialState,
        )

        val protocolRole = when (role) {
            Role.Inviter -> HandshakeProtocol.Role.Inviter
            Role.Invitee -> HandshakeProtocol.Role.Invitee
        }

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            // Promote the transport stack to a foreground service for
            // the duration of this handshake — otherwise Android 14's
            // background limits will tear down the GATT server within
            // ~30s if the user backgrounds the app mid-pairing.
            transportLifecycle.acquireForHandshake()
            try {
                val link: Link = try {
                    openLinkFor(localQr.communityId, peerQr, protocolRole)
                } catch (t: Throwable) {
                    Log.w(TAG, "openLinkFor failed (${t::class.simpleName}: ${t.message})")
                    emitResult(
                        identity, backing, localQr, localQrBase32,
                        HandshakeSession.Outcome.Aborted(HandshakeSession.AbortReason.TransportFailed),
                    )
                    return@launch
                }

                val session = handshake.open(protocolRole, localQr, peerQr, link)
                activeSession = session

                // Pipe session state -> UI
                val pipe = launch {
                    session.state.collect { s ->
                        val current = _state.value
                        if (current is UiState.RunHandshake) {
                            _state.value = current.copy(sessionState = s)
                        }
                    }
                }

                val outcome = try {
                    session.run()
                } finally {
                    pipe.cancel()
                    runCatching { link.close() }
                }

                emitResult(identity, backing, localQr, localQrBase32, outcome)
                activeSession = null
                activeJob = null
            } finally {
                transportLifecycle.release()
            }
        }
    }

    /**
     * v0.1 BLE link bring-up. The Inviter scans for the first peer
     * advertising the community service UUID and dials it; the Invitee
     * accepts the first incoming connection. The Noise channel-binding
     * check inside HandshakeProtocolImpl rejects any peer whose static
     * key doesn't match the scanned QR — so an attacker can't slip in
     * by being the first to connect.
     *
     * Wrapped in a [LINK_TIMEOUT_MS] timeout so that the most common
     * failure mode — both devices having picked the same role, so
     * neither side is dialing — surfaces as a [TransportFailed] result
     * instead of an indefinitely frozen "establishing trust" screen.
     */
    private suspend fun openLinkFor(
        communityId: CommunityId,
        peerQr: HandshakeQr,
        role: HandshakeProtocol.Role,
    ): Link {
        Log.d(TAG, "openLinkFor: role=$role community=${communityId.bytes.take(4).joinToString("") { "%02x".format(it) }}…")

        val peerOnion = peerQr.onionAddress
        if (peerOnion != null && torBackend.state.value is TorBackend.State.Ready) {
            Log.d(TAG, "openLinkFor: peer has .onion, attempting Tor handshake")
            return openLinkOverTor(communityId, peerOnion, role)
        }

        bleTransport.start(communityId)
        return withTimeout(LINK_TIMEOUT_MS) {
            when (role) {
                HandshakeProtocol.Role.Inviter -> {
                    val endpoint = bleTransport.discovered().first()
                    Log.d(TAG, "openLinkFor: discovered peer ${endpoint.opaqueAddress}, dialing")
                    bleTransport.connect(endpoint).also {
                        Log.d(TAG, "openLinkFor: dialed link established")
                    }
                }
                HandshakeProtocol.Role.Invitee -> {
                    bleTransport.acceptedLinks().first().also {
                        Log.d(TAG, "openLinkFor: accepted inbound link")
                    }
                }
            }
        }
    }

    private suspend fun openLinkOverTor(
        communityId: CommunityId,
        peerOnion: String,
        role: HandshakeProtocol.Role,
    ): Link {
        torTransport.start(communityId)
        return withTimeout(TOR_LINK_TIMEOUT_MS) {
            when (role) {
                HandshakeProtocol.Role.Inviter -> {
                    val endpoint = PeerEndpoint(
                        kind = Transport.Kind.TorHiddenService,
                        opaqueAddress = peerOnion,
                    )
                    Log.d(TAG, "openLinkOverTor: dialing $peerOnion")
                    torTransport.connect(endpoint).also {
                        Log.d(TAG, "openLinkOverTor: Tor link established")
                    }
                }
                HandshakeProtocol.Role.Invitee -> {
                    Log.d(TAG, "openLinkOverTor: waiting for inbound Tor connection")
                    torTransport.acceptedLinks().first().also {
                        Log.d(TAG, "openLinkOverTor: accepted inbound Tor link")
                    }
                }
            }
        }
    }

    private fun emitResult(
        identity: Identity,
        backing: KeystoreManager.Backing,
        localQr: HandshakeQr,
        localQrBase32: String,
        outcome: HandshakeSession.Outcome,
    ) {
        _state.value = UiState.Result(
            identity = identity, backing = backing,
            localQr = localQr, localQrBase32 = localQrBase32,
            outcome = outcome,
        )
    }

    override fun onCleared() {
        super.onCleared()
        cancelHandshake()
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { bleTransport.stop() }
        }
    }

    enum class Role { Inviter, Invitee }

    sealed interface UiState {
        /**
         * Initial state while we check whether the user has already
         * paired with anyone. Resolves to either [RolePicker] (no
         * existing trust edge → first-launch onboarding) or
         * [AlreadyOnboarded] (skip straight to the main app).
         */
        data object Booting : UiState

        /**
         * Sentinel state emitted when at least one trust edge already
         * exists at app launch. The host catches this and navigates
         * to the main app rather than walking the user through
         * onboarding again.
         */
        data object AlreadyOnboarded : UiState

        data object RolePicker : UiState
        data class KeyGeneration(val status: KeyGenStatus) : UiState

        /**
         * Combined "show + scan" state. Both sides see their own QR
         * and a live camera; once the user scans the peer's QR, the
         * scanned [peerQr] is stored here and the screen switches to
         * a "scanned them — waiting for them to scan you" layout
         * with the local QR still visible. The actual transition to
         * [CompareFingerprints] is gated by the user tapping Continue
         * (see [onContinueFromPair]) so the peer always has time to
         * complete their own scan of our QR — the Noise prologue
         * needs both sides to hold both QRs.
         */
        data class Pair(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val qr: HandshakeQr,
            val base32: String,
            val peerQr: HandshakeQr? = null,
        ) : UiState

        data class CompareFingerprints(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val localQr: HandshakeQr,
            val localQrBase32: String,
            val peerQr: HandshakeQr,
        ) : UiState
        data class RunHandshake(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val localQr: HandshakeQr,
            val localQrBase32: String,
            val peerQr: HandshakeQr,
            val sessionState: HandshakeSession.State,
        ) : UiState
        data class Result(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val localQr: HandshakeQr,
            val localQrBase32: String,
            val outcome: HandshakeSession.Outcome,
        ) : UiState
    }

    sealed interface KeyGenStatus {
        data object Pending : KeyGenStatus
        data object Running : KeyGenStatus
        data class Failed(val message: String) : KeyGenStatus
    }

    private data class QrPayload(val qr: HandshakeQr, val base32: String)

    private companion object {
        private const val TAG = "OnboardingVM"

        /**
         * How long to wait for the BLE link to materialise before
         * giving up. 30s comfortably covers the slowest observed
         * advertise-then-discover round trip on Samsung mid-range
         * devices (~5–8s typical), while still surfacing a frozen
         * pairing attempt to the user quickly enough that they can
         * retry. The most common cause of a timeout in practice is
         * both devices having picked the same role on the role
         * picker — the [TransportFailed] copy on the result screen
         * names that explicitly.
         */
        private const val LINK_TIMEOUT_MS = 30_000L
        private const val TOR_LINK_TIMEOUT_MS = 60_000L
        private const val REMOTE_QR_MAX_AGE_SECONDS = 30L * 60
    }
}
