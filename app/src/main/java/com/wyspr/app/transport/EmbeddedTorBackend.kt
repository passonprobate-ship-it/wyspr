package com.wyspr.app.transport

import android.content.Context
import android.util.Log
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.transport.TorBackend
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorListeners
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.TorState
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * TorBackend backed by kmp-tor's embedded tor daemon.
 *
 * Sprint 2 contract:
 * - Boot an embedded tor process (via kmp-tor's `-exec` resource pack,
 *   which extracts the tor binary to the app's nativeLibraryDir on
 *   install and `fork()`s it as a subprocess).
 * - Publish a single HSv3 hidden service whose Ed25519 key is
 *   deterministically derived from the device's keystore identity, so
 *   the .onion address is stable across reinstalls (assuming the
 *   keystore identity itself survives — wiping the identity is
 *   intentionally destructive).
 * - Surface bootstrap progress + the published .onion to the UI.
 *
 * What this does NOT do yet:
 * - The hidden service has no listener bound to its target port. Sprint
 *   4's `TorHiddenServiceTransport` reconfigures this to point at a
 *   real Noise/Sync listener.
 * - The tor daemon runs as a subprocess of the app process. Android
 *   eventually reclaims backgrounded apps, so for long sessions this
 *   needs to migrate behind the foreground-service pattern already
 *   used by [com.wyspr.app.transport.TransportForegroundService]
 *   for BLE.
 *
 * Thread model: [start] / [stop] are mutex-serialized; everything else
 * runs on kmp-tor's own threads. Observer callbacks use
 * [OnEvent.Executor.Immediate], so [state]/[onionAddress] writes happen
 * on whichever kmp-tor thread fired the event. [MutableStateFlow] is
 * thread-safe by contract.
 */
class EmbeddedTorBackend(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
) : TorBackend {

    private val _state = MutableStateFlow<TorBackend.State>(TorBackend.State.Idle)
    override val state: StateFlow<TorBackend.State> = _state

    private val _onion = MutableStateFlow<String?>(null)
    override val onionAddress: StateFlow<String?> = _onion

    private val _socksPort = MutableStateFlow<Int?>(null)
    override val socksPort: StateFlow<Int?> = _socksPort

    override val hsTargetPort: Int = TorBackend.DEFAULT_HS_TARGET_PORT

    private val startMutex = Mutex()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var runtime: TorRuntime? = null
    @Volatile
    private var watchdogJob: Job? = null

    override suspend fun start(): Unit = startMutex.withLock {
        // If a previous start() set runtime but then failed (state ==
        // Failed), allow a retry to rebuild the runtime — otherwise
        // the UI's manual "try again" sat permanently no-opped because
        // `runtime != null` short-circuited.
        if (runtime != null && _state.value !is TorBackend.State.Failed) return
        if (_state.value is TorBackend.State.Failed) {
            runtime = null
        }

        // The HS-key derivation reads the keystore-wrapped seed. If the
        // wrapping key is biometric-bound and the user hasn't unlocked
        // yet (e.g. cold start before they've touched the biometric
        // gate), the cipher init throws UserNotAuthenticatedException.
        // Bail without flipping to Failed — the post-unlock call site
        // in WysprNavHost retries this idempotently.
        if (keystoreManager.needsAuth()) {
            Log.d(TAG, "Tor start deferred: keystore awaiting biometric unlock")
            return
        }

        val r = try {
            buildRuntime()
        } catch (t: Throwable) {
            Log.w(TAG, "Tor runtime build failed", t)
            _state.value = TorBackend.State.Failed(t.message ?: "Tor init failed")
            return
        }
        runtime = r
        _state.value = TorBackend.State.Bootstrapping(percent = 0)
        try {
            r.startDaemonAsync()
            startWatchdog(r)
        } catch (t: Throwable) {
            Log.w(TAG, "Tor StartDaemon failed", t)
            _state.value = TorBackend.State.Failed(t.message ?: "Tor failed to start")
        }
    }

    override suspend fun stop(): Unit = startMutex.withLock {
        watchdogJob?.cancel()
        watchdogJob = null
        val r = runtime ?: return
        try {
            r.stopDaemonAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "Tor StopDaemon failed", t)
        }
        _state.value = TorBackend.State.Idle
        _onion.value = null
        _socksPort.value = null
    }

    private fun buildRuntime(): TorRuntime {
        // workDirectory holds tor's persistent state (HiddenServiceDir,
        // routerlist, lock). cacheDirectory holds disposable consensus
        // data. kmp-tor requires they be distinct paths.
        val workDir = File(context.filesDir, "torservice").apply { mkdirs() }
        val cacheDir = File(context.cacheDir, "torservice").apply { mkdirs() }

        val hsDir = File(workDir, "wyspr_hs")
        val derivedOnion = prepareHsDir(hsDir)
        _onion.value = derivedOnion
        // Surface the locally-published onion so two-device diagnostics
        // can confirm whether the stored peer_onion on the other device
        // matches what THIS device is actually publishing right now.
        Log.i(TAG, "Local onion: $derivedOnion.onion")

        val env = TorRuntime.Environment.Builder(
            workDirectory = workDir,
            cacheDirectory = cacheDir,
            loader = ResourceLoaderTorExec::getOrCreate,
        )

        return TorRuntime.Builder(env) {
            observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { st ->
                applyTorState(st)
            }
            // RuntimeEvent.READY fires when the control connection is up,
            // NOT when bootstrap completes. Relying on it caused premature
            // Ready state while Tor was still at 0% bootstrap. The STATE
            // observer below correctly gates on d.isBootstrapped.
            observerStatic(RuntimeEvent.ERROR, OnEvent.Executor.Immediate) { err ->
                Log.w(TAG, "Tor runtime error", err)
                _state.value = TorBackend.State.Failed(err.message ?: err::class.simpleName ?: "error")
            }
            // SOCKS port lands here once the control connection reports
            // its bound listeners. Empty set means the proxy isn't up
            // yet (still bootstrapping, or stopped).
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                applyListeners(listeners)
            }

            // Surface Tor's own NOTICE / WARN / HS_DESC log lines into
            // logcat so two-device diagnostics can see what the daemon
            // says about descriptor publication, intro-point selection,
            // and rendezvous attempts. NOTICE on its own includes
            // "Bootstrapped NN%" progress, "Tor has successfully opened
            // a circuit", and "Hidden service descriptor was uploaded
            // successfully" — which is exactly the signal we need to
            // distinguish "Tor: ready" from "HS actually reachable".
            observerStatic(TorEvent.NOTICE, OnEvent.Executor.Immediate) { line ->
                Log.i(TAG, "tor NOTICE: $line")
            }
            observerStatic(TorEvent.WARN, OnEvent.Executor.Immediate) { line ->
                Log.w(TAG, "tor WARN: $line")
            }
            observerStatic(TorEvent.ERR, OnEvent.Executor.Immediate) { line ->
                Log.w(TAG, "tor ERR: $line")
            }
            observerStatic(TorEvent.HS_DESC, OnEvent.Executor.Immediate) { line ->
                Log.i(TAG, "tor HS_DESC: $line")
            }

            config { _ ->
                // Let tor pick the SOCKS port — 9050 may clash with another
                // app on the device. We read the chosen port via
                // RuntimeEvent.LISTENERS above.
                TorOption.__SocksPort.configure { auto() }

                // Publish the keystore-pinned HSv3 service. The target
                // port matches [TorBackend.DEFAULT_HS_TARGET_PORT] so
                // [com.wyspr.app.transport.TorHiddenServiceTransport]
                // can bind its listener on the same port without
                // hardcoding the value in two places.
                //
                // Build via asSetting + put(), not tryConfigure, because
                // tryConfigure silently SWALLOWS an IllegalArgumentException
                // thrown by the builder's build() step (e.g. a port-validation
                // failure). That leaves Tor running with NO hidden service
                // and no error anywhere in logs — the daemon bootstraps
                // fine and never logs "Opening Hidden Service listener",
                // so SOCKS dials from peers return reply code 1 forever.
                // asSetting throws the validation error to us; put() then
                // attaches the built setting to the config explicitly.
                try {
                    val hsSetting = TorOption.HiddenServiceDir.asSetting {
                        directory(hsDir)
                        version(3)
                        // Messaging sync — Noise transport, encrypted frames.
                        port(virtual = hsTargetPort.toPort()) { target(port = hsTargetPort.toPort()) }
                        // Personal web page — plain HTTP, served by
                        // [com.wyspr.app.profile.ProfileHttpServer].
                        // Anyone with the user's .onion can fetch their
                        // profile page over Tor.
                        port(virtual = 80.toPort()) {
                            target(port = TorBackend.WEB_TARGET_PORT.toPort())
                        }
                        // Sprint 3: mailbox push-notify channel. Curl-
                        // empirically verified 2026-05-22 that this third
                        // port forwards correctly through Tor — earlier
                        // reply-code-4 failures were stochastic SOCKS
                        // behaviour, not config dropping. MailboxNotifyClient
                        // now retries on rep-4 to ride out the flake.
                        port(virtual = TorBackend.MAILBOX_NOTIFY_TARGET_PORT.toPort()) {
                            target(port = TorBackend.MAILBOX_NOTIFY_TARGET_PORT.toPort())
                        }
                    }
                    put(hsSetting)
                    Log.i(TAG, "HiddenServiceDir setting attached: $hsSetting")
                } catch (t: Throwable) {
                    Log.w(TAG, "HiddenServiceDir setting build failed — Tor will run with NO hidden service", t)
                }
            }
        }
    }

    private fun applyListeners(listeners: TorListeners) {
        val newPort = listeners.socks.firstOrNull()?.port?.value
        if (_socksPort.value != newPort) {
            Log.d(TAG, "Tor SOCKS port now ${newPort ?: "<none>"}")
            _socksPort.value = newPort
        }
    }

    private fun applyTorState(st: TorState) {
        val d = st.daemon
        when {
            d.isOff || d.isStopping -> {
                if (_state.value != TorBackend.State.Idle) {
                    _state.value = TorBackend.State.Idle
                }
            }
            d.isBootstrapped -> {
                _state.value = TorBackend.State.Ready
                watchdogJob?.cancel()
                watchdogJob = null
            }
            else -> {
                val pct = (d.bootstrap.toInt() and 0xFF).coerceIn(0, 100)
                _state.value = TorBackend.State.Bootstrapping(percent = pct)
            }
        }
    }

    private fun startWatchdog(r: TorRuntime) {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            val current = _state.value
            if (current is TorBackend.State.Ready) return@launch
            val pct = (current as? TorBackend.State.Bootstrapping)?.percent ?: -1
            Log.w(TAG, "Tor watchdog: bootstrap stalled at $pct% after ${WATCHDOG_TIMEOUT_MS / 1000}s — restarting daemon")
            _state.value = TorBackend.State.Bootstrapping(percent = 0)
            try {
                r.stopDaemonAsync()
                delay(1_000)
                r.startDaemonAsync()
                startWatchdog(r)
            } catch (t: Throwable) {
                Log.w(TAG, "Tor watchdog: restart failed", t)
                _state.value = TorBackend.State.Failed("Bootstrap stalled; restart failed: ${t.message}")
            }
        }
    }

    /**
     * Drop our keystore-derived HSv3 key files into [hsDir]. If the
     * same keys already exist (the typical case on every launch after
     * the first), the files are simply rewritten — Tor will detect
     * they're unchanged and reuse the cached descriptor. If the
     * keystore identity has changed (user reset their identity since
     * last run), the old files are overwritten so the new identity's
     * .onion is the only one Tor publishes.
     *
     * Returns the derived address so we can surface it to the UI
     * before Tor finishes bootstrapping.
     */
    private fun prepareHsDir(hsDir: File): String {
        hsDir.mkdirs()
        // POSIX 0700 — tor refuses to start if the HiddenServiceDir is
        // world- or group-readable.
        hsDir.setReadable(false, false); hsDir.setReadable(true, true)
        hsDir.setWritable(false, false); hsDir.setWritable(true, true)
        hsDir.setExecutable(false, false); hsDir.setExecutable(true, true)

        val seed = keystoreManager.deriveSubkey(SUBKEY_INFO)
        var mat: TorHsKey.Material? = null
        try {
            mat = TorHsKey.derive(seed)
            File(hsDir, "hs_ed25519_secret_key").writeBytes(mat.secretKeyFile)
            File(hsDir, "hs_ed25519_public_key").writeBytes(mat.publicKeyFile)
            return mat.onionAddress
        } finally {
            seed.fill(0)
            // The expanded HSv3 secret is on heap after writeBytes(); the
            // GC will eventually reclaim it but a memory dump in the
            // interim could surface it. Zero before letting go.
            mat?.secretKeyFile?.fill(0)
        }
    }

    private companion object {
        private const val TAG = "EmbeddedTorBackend"
        private const val WATCHDOG_TIMEOUT_MS = 120_000L
        // Namespaces this subkey distinctly from the SQLCipher DB key
        // and any future keystore-derived subkeys. See KeystoreManager
        // HKDF info convention in PROTOCOLS.md §5.
        val SUBKEY_INFO: ByteArray = "WYSPR/v1/tor-hs".encodeToByteArray()
    }
}
