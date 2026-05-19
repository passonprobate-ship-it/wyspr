package com.keystone.feature.onboarding.share

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the lifecycle of the [ApkShareServer] for the share screen.
 *
 * The server runs only while the screen is on-stack; [start] is
 * idempotent so survived configuration changes (orientation, font
 * scale) don't restart the socket, and [stop] is called from
 * [onCleared] OR explicitly when the user dismisses the screen.
 *
 * State surfaced to the UI:
 *   - [State.Starting] — picking up the local IP / binding the port
 *   - [State.Ready]    — URL + SHA-256 + APK size ready to render
 *   - [State.Failed]   — couldn't bind a port or couldn't find a
 *                       LAN IP; UI shows a "connect to WiFi" hint
 */
@HiltViewModel
class ApkSharingViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: ApkShareServer? = null

    fun start() {
        if (server != null) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { startServerBlocking() }
            _state.value = outcome
        }
    }

    private fun startServerBlocking(): State {
        val ip = LocalIp.find()
            ?: return State.Failed(
                "Couldn't find a WiFi address. Connect your phone to the same WiFi " +
                    "as your peer and try again.",
            )
        val srv = ApkShareServer(getApplication())
        // Build a fresh self-signed cert for THIS IP and attach it
        // before binding — modern browsers (Brave most aggressively)
        // refuse plain HTTP on a non-loopback address, so HTTPS even
        // with a self-signed cert is the only path that loads on
        // every recipient device. The recipient sees a one-time
        // "Not secure" warning; the SHA-256 on the mini-site is the
        // out-of-band trust anchor, PKI is irrelevant.
        val tlsResult = runCatching {
            val factory = SelfSignedCert.makeSocketFactory(ip)
            srv.makeSecure(factory, null)
        }
        if (tlsResult.isFailure) {
            return State.Failed(
                "Couldn't generate a session certificate: ${tlsResult.exceptionOrNull()?.message}",
            )
        }
        val bound = runCatching {
            srv.start(NanoHttpdTimeoutMs, /* daemon = */ true)
        }
        if (bound.isFailure) {
            srv.stop()
            return State.Failed(
                "Couldn't start the local server on port ${ApkShareServer.DEFAULT_PORT}. " +
                    "Another app may be using it. Restart Keystone and try again.",
            )
        }
        server = srv
        return State.Ready(
            url = "https://$ip:${ApkShareServer.DEFAULT_PORT}/",
            apkSha256 = srv.apkSha256,
            apkSizeBytes = srv.apkSizeBytes,
            versionName = srv.versionName,
        )
    }

    fun stop() {
        val srv = server ?: return
        server = null
        // NanoHTTPD's stop() is fast (joins worker threads with a
        // short timeout). Safe to call on the main thread.
        runCatching { srv.stop() }
        _state.value = State.Starting
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    sealed interface State {
        data object Starting : State
        data class Ready(
            val url: String,
            val apkSha256: String,
            val apkSizeBytes: Long,
            val versionName: String,
        ) : State
        data class Failed(val message: String) : State
    }

    private companion object {
        // Tighter than NanoHTTPD's 5000ms default — the share session
        // is interactive and a 1s socket-read budget is plenty for the
        // ~50MB APK transfer over a same-LAN link.
        const val NanoHttpdTimeoutMs = 1_000
    }
}
