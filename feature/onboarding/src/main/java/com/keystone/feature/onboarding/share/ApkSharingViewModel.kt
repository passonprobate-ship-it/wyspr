package com.keystone.feature.onboarding.share

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.transport.TransportLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val transportLifecycle: TransportLifecycle,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Serialises [start] and [stop]. The check `server != null` and
     * the assignment that publishes a freshly-started server are
     * separated by an IO suspension; without the mutex, two rapid
     * `start()` calls (e.g. config change + LaunchedEffect both
     * firing) could both pass the null check and spin up two
     * NanoHTTPD instances, leaking the first.
     */
    private val lifecycleLock = Mutex()

    @Volatile private var server: ApkShareServer? = null

    fun start() {
        viewModelScope.launch {
            lifecycleLock.withLock {
                if (server != null) return@withLock
                // Acquire the foreground service BEFORE binding the
                // listening socket — Android 14+ requires the service
                // be in foreground state before any non-loopback bind.
                transportLifecycle.acquireForSharing()
                val outcome = withContext(Dispatchers.IO) { startServerBlocking() }
                if (outcome !is State.Ready) {
                    // Couldn't actually start — release the FGS hold
                    // so we don't leak a foreground notification.
                    transportLifecycle.release()
                }
                _state.value = outcome
            }
        }
    }

    private fun startServerBlocking(): State {
        val ip = LocalIp.find()
            ?: return State.Failed(
                "Couldn't find a WiFi address. Connect your phone to the same WiFi " +
                    "as your peer and try again.",
            )
        // Bind explicitly to the chosen LAN IP so the listening
        // socket isn't exposed on Tailscale, USB tether, or any other
        // up interface — see KDoc on ApkShareServer.bindHost.
        val srv = ApkShareServer(context = getApplication(), bindHost = ip)
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
        // NanoHTTPD's stop() joins worker threads with up to a
        // socket-read-timeout budget per worker — under a few
        // hundred ms on a quiet share session but easily 1-2s if
        // the recipient is mid-download. That's well over the 16ms
        // frame budget; run it on IO.
        viewModelScope.launch {
            lifecycleLock.withLock {
                val srv = server ?: return@withLock
                server = null
                withContext(Dispatchers.IO) { runCatching { srv.stop() } }
                transportLifecycle.release()
                _state.value = State.Starting
            }
        }
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
        /**
         * Per-socket-read timeout (NanoHTTPD's
         * SOCKET_READ_TIMEOUT). The connection is interactive but
         * an APK transfer over a slow link can stall well over a
         * second between reads; matching NanoHTTPD's own 5s default
         * is safer than the aggressive 1s we had before.
         */
        const val NanoHttpdTimeoutMs = 5_000
    }
}
