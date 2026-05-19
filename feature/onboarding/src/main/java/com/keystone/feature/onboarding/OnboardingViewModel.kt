package com.keystone.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.CommunityService
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.Identity
import com.keystone.core.identity.IdentityIssuer
import com.keystone.core.transport.Link
import com.keystone.core.transport.bluetooth.BleTransport
import com.keystone.core.trust.HandshakeProtocol
import com.keystone.core.trust.HandshakeProtocolImpl
import com.keystone.core.trust.HandshakeQr
import com.keystone.core.trust.HandshakeQrCodec
import com.keystone.core.trust.HandshakeSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the onboarding state machine end-to-end:
 *
 *     Welcome → RolePicker → KeyGeneration → DisplayQr →
 *     ScanPeerQr → CompareFingerprints → RunHandshake → Result
 *
 * Each transition is one-way; aborting from any step rewinds to
 * DisplayQr where the user can retry with a fresh QR or restart from
 * RolePicker.
 *
 * The handshake itself runs in a dedicated coroutine so the UI keeps
 * collecting [HandshakeSession.state] updates throughout. Cancelling
 * the screen calls [cancelHandshake] which terminates the coroutine
 * and the session.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: KeystoneDatabase,
    private val handshake: HandshakeProtocolImpl,
    private val communityService: CommunityService,
    private val bleTransport: BleTransport,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Welcome)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    private var activeSession: HandshakeSession? = null
    private var activeJob: Job? = null

    fun continueFromWelcome() { _state.value = UiState.RolePicker }

    fun pickRole(role: Role) {
        _role.value = role
        _state.value = UiState.KeyGeneration(KeyGenStatus.Pending)
    }

    fun back() {
        _state.value = when (val s = state.value) {
            UiState.RolePicker -> UiState.Welcome
            is UiState.KeyGeneration -> UiState.RolePicker
            is UiState.DisplayQr -> UiState.RolePicker
            is UiState.ScanPeerQr -> UiState.DisplayQr(s.identity, s.backing, s.localQr, s.localQrBase32)
            is UiState.CompareFingerprints -> UiState.ScanPeerQr(s.identity, s.backing, s.localQr, s.localQrBase32)
            is UiState.RunHandshake -> { cancelHandshake(); UiState.DisplayQr(s.identity, s.backing, s.localQr, s.localQrBase32) }
            is UiState.Result -> UiState.DisplayQr(s.identity, s.backing, s.localQr, s.localQrBase32)
            UiState.Welcome -> UiState.Welcome
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
                        "This device has no hardware-backed keystore. Keystone cannot run safely here."
                    }
                    val identity = IdentityIssuer.issue(keystore)
                    database.open()
                    val communityId = communityService.activeCommunityIdOrNull()
                        ?: communityService.foundNewCommunity(identity.publicKey.bytes)
                    val qr = handshake.mintInviterQr(communityId)
                    val base32 = HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(qr))
                    Triple(identity, backing, QrPayload(qr, base32))
                }
                _state.value = UiState.DisplayQr(
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
        val s = state.value as? UiState.DisplayQr ?: return
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                val qr = handshake.mintInviterQr(s.qr.communityId)
                QrPayload(qr, HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(qr)))
            }
            _state.value = s.copy(qr = payload.qr, base32 = payload.base32)
        }
    }

    fun startScanning() {
        val s = state.value as? UiState.DisplayQr ?: return
        _state.value = UiState.ScanPeerQr(
            identity = s.identity,
            backing = s.backing,
            localQr = s.qr,
            localQrBase32 = s.base32,
        )
    }

    fun onPeerQrScanned(peerQr: HandshakeQr) {
        val s = state.value as? UiState.ScanPeerQr ?: return
        if (!peerQr.communityId.bytes.contentEquals(s.localQr.communityId.bytes)) {
            // Peer belongs to a different community — silently treat as invalid.
            return
        }
        val now = System.currentTimeMillis() / 1000
        if (!peerQr.isFresh(now)) {
            _state.value = UiState.Result(
                identity = s.identity, backing = s.backing,
                localQr = s.localQr, localQrBase32 = s.localQrBase32,
                outcome = HandshakeSession.Outcome.Aborted(HandshakeSession.AbortReason.QrStale),
            )
            return
        }
        _state.value = UiState.CompareFingerprints(
            identity = s.identity,
            backing = s.backing,
            localQr = s.localQr,
            localQrBase32 = s.localQrBase32,
            peerQr = peerQr,
        )
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

    fun retryFromDisplayQr() {
        val s = state.value as? UiState.Result ?: return
        _state.value = UiState.DisplayQr(s.identity, s.backing, s.localQr, s.localQrBase32)
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
            val link: Link = try {
                openLinkFor(localQr.communityId, peerQr, protocolRole)
            } catch (t: Throwable) {
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
        }
    }

    /**
     * v0.1 BLE link bring-up. The Inviter scans for the first peer
     * advertising the community service UUID and dials it; the Invitee
     * accepts the first incoming connection. The Noise channel-binding
     * check inside HandshakeProtocolImpl rejects any peer whose static
     * key doesn't match the scanned QR — so an attacker can't slip in
     * by being the first to connect.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun openLinkFor(
        communityId: CommunityId,
        peerQr: HandshakeQr,
        role: HandshakeProtocol.Role,
    ): Link {
        // peerQr is captured here so future versions can do a pre-noise
        // filter (e.g. compute the expected service UUID, or pin the
        // peer address against the QR's identityPub via a fingerprint
        // index). v0.1 trusts Noise's channel-binding check downstream.
        bleTransport.start(communityId)
        return when (role) {
            HandshakeProtocol.Role.Inviter -> {
                val endpoint = bleTransport.discovered().first()
                bleTransport.connect(endpoint)
            }
            HandshakeProtocol.Role.Invitee -> {
                bleTransport.acceptedLinks().first()
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
        viewModelScope.launch { runCatching { bleTransport.stop() } }
    }

    enum class Role { Inviter, Invitee }

    sealed interface UiState {
        data object Welcome : UiState
        data object RolePicker : UiState
        data class KeyGeneration(val status: KeyGenStatus) : UiState
        data class DisplayQr(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val qr: HandshakeQr,
            val base32: String,
        ) : UiState
        data class ScanPeerQr(
            val identity: Identity,
            val backing: KeystoreManager.Backing,
            val localQr: HandshakeQr,
            val localQrBase32: String,
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
}
