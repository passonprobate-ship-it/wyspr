package com.keystone.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.database.CommunityService
import com.keystone.core.identity.CommunityId
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.bluetooth.BleTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine for the BLE discovery screen.
 *
 * Owns one BleTransport scan/advertise session at a time. The screen
 * starts the session when the user taps "Scan" (and permissions are
 * granted) and stops it when the user leaves, taps "Stop," or revokes
 * permissions. We never auto-start — BLE chews battery and shouldn't
 * run without explicit consent.
 *
 * Discovery only — no handshake yet. Peers are listed by their (current,
 * randomized) BLE MAC, RSSI is not shown for v0 because [PeerEndpoint]
 * doesn't carry it; future work adds an extended `DiscoveredPeer` type
 * with signal strength and last-seen time.
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val transport: BleTransport,
    private val communityService: CommunityService,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun refreshStatus() {
        _state.value = _state.value.copy(
            bluetoothReady = transport.isBluetoothReady,
        )
    }

    fun startScan() {
        if (scanJob != null) return
        if (!transport.isBluetoothReady) {
            _state.value = _state.value.copy(
                error = "Bluetooth is off. Enable it in settings, then try again.",
            )
            return
        }
        scanJob = viewModelScope.launch {
            try {
                // Use the device's active community so the BLE service UUID
                // matches what real peers in the same community advertise.
                // Without a community yet (shouldn't happen post-onboarding,
                // but be safe) we advertise on the all-zero UUID — nothing
                // legitimate uses that, so discovery is effectively offline.
                val communityId = communityService.activeCommunityIdOrNull()
                    ?: CommunityId(ByteArray(32))
                transport.start(communityId)
                _state.value = _state.value.copy(scanning = true, error = null, peers = emptyList())
                transport.discovered().collect { endpoint ->
                    val current = _state.value.peers
                    if (current.none { it.opaqueAddress == endpoint.opaqueAddress }) {
                        _state.value = _state.value.copy(peers = current + endpoint)
                    }
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    error = t.message ?: "Failed to start scan",
                    scanning = false,
                )
                scanJob = null
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch {
            runCatching { transport.stop() }
            _state.value = _state.value.copy(scanning = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        // Best-effort stop — viewModelScope is dead by this point so we
        // can't await transport.stop(); the BleTransport mutex will
        // serialize a later start() against any pending OS callbacks.
    }

    data class UiState(
        val scanning: Boolean = false,
        val bluetoothReady: Boolean = false,
        val peers: List<PeerEndpoint> = emptyList(),
        val error: String? = null,
    )
}
