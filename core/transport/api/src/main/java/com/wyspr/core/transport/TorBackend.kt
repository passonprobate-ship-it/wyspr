package com.wyspr.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform facade for the embedded Tor daemon.
 *
 * Wyspr's "you can be in another country" feature uses Tor
 * hidden services: each install generates an Ed25519 hidden-service
 * key, publishes a `.onion` address to its trusted peers via the
 * handshake, and routes Noise frames over Tor circuits when BLE
 * isn't viable. From the user's side this is invisible — they
 * pair in person, then messages flow through whichever transport
 * works.
 *
 * The interface deliberately leaves the bootstrap path open. The
 * v0.6.1 sprint will bundle Tor via [kmp-tor][1] and implement
 * `EmbeddedTorBackend` against this contract; v0.6.2 wires a
 * `TorHiddenServiceTransport: Transport` that the sync engine
 * uses transparently. Until then, [Stub] reports `Unavailable`
 * and the rest of the app silently falls back to BLE.
 *
 * Lives in `core:transport:api` alongside [Transport],
 * [TransportLifecycle], and [MessagingNotifier] — the standard
 * pattern Wyspr uses for feature-module → app-module bridges.
 *
 * [1]: https://github.com/05nelsonm/kmp-tor
 */
interface TorBackend {

    /** Current bootstrap / runtime state of the embedded daemon. */
    val state: StateFlow<State>

    /**
     * The local hidden-service `.onion` address (HSv3, 56-char
     * lowercase) once Tor has finished bootstrapping and the
     * service is published. Null while [state] is anything other
     * than [State.Ready].
     */
    val onionAddress: StateFlow<String?>

    /**
     * Loopback port Tor's SOCKS5 proxy is listening on. The transport
     * dialer SOCKS5-CONNECTs through this port to reach peer .onion
     * addresses. Null until Tor has finished bootstrapping (kmp-tor's
     * runtime emits the listener address via `RuntimeEvent.LISTENERS`
     * around the same time the daemon flips to Ready).
     */
    val socksPort: StateFlow<Int?>

    /**
     * Loopback port the embedded hidden service forwards `.onion:port`
     * traffic to. Constant for the lifetime of this build — the
     * `TorHiddenServiceTransport` binds its listener here, and remote
     * peers SOCKS-dial `our.onion:hsTargetPort` to reach us.
     */
    val hsTargetPort: Int

    /** Start the daemon. Idempotent — repeated calls are no-ops. */
    suspend fun start()

    /** Stop the daemon and release the hidden service. Idempotent. */
    suspend fun stop()

    sealed interface State {
        /** Daemon not started yet, or stopped. */
        data object Idle : State

        /** Daemon launched; Tor is establishing circuits. */
        data class Bootstrapping(val percent: Int) : State

        /** Bootstrap finished and the hidden service is published. */
        data object Ready : State

        /** Tor is bundled but failed to start or bootstrap. */
        data class Failed(val message: String) : State

        /**
         * Tor binary not yet bundled in this build. The v0.6.1
         * sprint replaces this state by wiring kmp-tor.
         */
        data object Unavailable : State
    }

    /**
     * No-op fallback used until the real backend lands in v0.6.1.
     * Reports [State.Unavailable] indefinitely; never blocks any
     * other transport.
     */
    class Stub : TorBackend {
        private val _state = MutableStateFlow<State>(State.Unavailable)
        override val state: StateFlow<State> = _state
        private val _onion = MutableStateFlow<String?>(null)
        override val onionAddress: StateFlow<String?> = _onion
        private val _socksPort = MutableStateFlow<Int?>(null)
        override val socksPort: StateFlow<Int?> = _socksPort
        override val hsTargetPort: Int = DEFAULT_HS_TARGET_PORT
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
    }

    companion object {
        /**
         * Loopback port the HiddenService maps onion traffic to. Picked
         * to avoid the usual suspects (8080/8443/9050) and to match the
         * placeholder port [com.wyspr.app.transport.EmbeddedTorBackend]
         * configures on the kmp-tor `HiddenServiceDir` builder.
         */
        const val DEFAULT_HS_TARGET_PORT = 9091

        /**
         * Loopback port for the user's personal web page server. The
         * HiddenService also maps onion:80 → 127.0.0.1:WEB_TARGET_PORT,
         * so any Tor-aware browser can fetch
         * `http://<user>.onion/` and see the user's profile page.
         * See [com.wyspr.app.profile.ProfileHttpServer].
         */
        const val WEB_TARGET_PORT = 9092

        /**
         * Loopback port for the mailbox push-notify channel
         * ([com.wyspr.feature.messaging.mailbox.MailboxNotifyHost]).
         * The HiddenService maps `onion:9093 → 127.0.0.1:9093`. Mailbox
         * recipients open a long-lived TCP-over-Tor connection to this
         * port and receive 1-byte notifications when a new envelope is
         * stored for them — driving cross-internet message latency
         * below the 8s auto-sync floor.
         */
        const val MAILBOX_NOTIFY_TARGET_PORT = 9093
    }
}
