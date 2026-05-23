package com.wyspr.feature.onboarding.share

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "Update from a peer" flow on the *receiving* device:
 *
 *   1. User pastes or scans the peer's share URL.
 *   2. [check] fetches `/version.json`. Compares versionCode against
 *      this device's own. Surfaces Older / Same / Newer.
 *   3. If Newer, the user taps Install. [install] downloads the
 *      APK to private cache with SHA-256 verification, then hands
 *      it to the system PackageInstaller via an explicit-package
 *      VIEW intent (the explicit-package constraint defeats any
 *      malicious third-party app that registers for the APK MIME).
 *
 * The receiver never auto-installs — Android's installer requires
 * an explicit user tap. The "auto" in Wyspr's peer-update story
 * is about discovery and download, not bypassing user consent.
 */
@HiltViewModel
class PeerUpdateViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun check(rawUrl: String) {
        cancelActive()
        val url = normaliseUrl(rawUrl) ?: run {
            _state.value = State.Failed("That doesn't look like a Wyspr share URL.")
            return
        }
        _state.value = State.Checking(url)
        activeJob = viewModelScope.launch {
            // Resolve and gate the host BEFORE fetching anything —
            // DNS lookup is blocking so it has to live on IO. The
            // gate rejects loopback / link-local / multicast and
            // anything outside the RFC1918 + CGNAT private ranges
            // a paired peer's share server is realistically reachable
            // on, so the receiver can't be socially-engineered into
            // probing a host on the open internet via a pasted URL.
            val hostOk = withContext(Dispatchers.IO) { isHostAllowed(url) }
            if (!hostOk) {
                _state.value = State.Failed(
                    "That URL points outside your local network. Wyspr " +
                        "only fetches from peers on the same WiFi.",
                )
                return@launch
            }
            val peer = withContext(Dispatchers.IO) { UpdateChecker.fetchVersion(url) }
                .getOrElse { t ->
                    _state.value = State.Failed(t.message ?: "Couldn't reach the peer.")
                    return@launch
                }
            val localCode = localVersionCode()
            val comparison = when {
                peer.versionCode > localCode -> State.Comparison.Newer
                peer.versionCode == localCode -> State.Comparison.Same
                else -> State.Comparison.Older
            }
            _state.value = State.Found(
                peerUrl = url,
                peer = peer,
                localVersionCode = localCode,
                comparison = comparison,
            )
        }
    }

    fun install() {
        val found = _state.value as? State.Found ?: return
        if (found.comparison != State.Comparison.Newer) return

        // Before downloading anything, confirm the OS will actually
        // let us hand the APK to the installer. On Android 8+ the
        // user has to flip "Install unknown apps" for Wyspr — if
        // they haven't, the system installer silently no-ops and the
        // user is stuck with a "Installer launched" message and
        // nothing happens. Surface a settings-redirect state instead.
        if (!canRequestInstalls()) {
            _state.value = State.NeedsInstallPermission(found)
            return
        }

        cancelActive()
        _state.value = State.Downloading(found.peer, bytesRead = 0, total = found.peer.apkSizeBytes)
        activeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApkDownloader.download(
                    context = getApplication(),
                    baseUrl = found.peerUrl,
                    expectedSha256 = found.peer.apkSha256,
                    expectedSizeBytes = found.peer.apkSizeBytes,
                    onProgress = { read, total ->
                        _state.value = State.Downloading(found.peer, read, total)
                    },
                )
            }
            val file = result.getOrElse { t ->
                _state.value = State.Failed(t.message ?: "Download failed.")
                return@launch
            }
            // Build the installer intent and constrain it to a
            // package that holds a system signature — anything else
            // claiming the package-archive MIME (potentially malicious
            // apps the user installed earlier) is rejected before
            // startActivity. The FileProvider URI grant rides along
            // only to the resolved package.
            val context = getApplication<Application>()
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val systemInstaller = resolveSystemPackageInstaller(uri)
            if (systemInstaller == null) {
                _state.value = State.Failed(
                    "No system installer found on this device. Open the APK from your " +
                        "Downloads folder to install manually.",
                )
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                setPackage(systemInstaller)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching { context.startActivity(intent) }
            if (launched.isFailure) {
                _state.value = State.Failed(
                    "Downloaded successfully but couldn't open the installer: " +
                        "${launched.exceptionOrNull()?.message}",
                )
            } else {
                _state.value = State.Installing(found.peer)
            }
        }
    }

    /**
     * Called from the UI when the user taps "Open Settings" on the
     * NeedsInstallPermission panel. Returns an Intent the screen
     * should fire — `startActivityForResult` not required since the
     * user will return on their own and re-tap Install.
     */
    fun installPermissionSettingsIntent(): Intent {
        val pkg = getApplication<Application>().packageName
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun reset() {
        cancelActive()
        _state.value = State.Idle
    }

    private fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * SSRF gate: resolve [url]'s host to an IP and require it be a
     * private-range address that a peer's local-network share
     * server could plausibly hold. Loopback, link-local, multicast,
     * any-cast, and public IPv4/IPv6 are all rejected. IPv6 is
     * deliberately rejected because the share server binds an
     * IPv4 address discovered by [LocalIp].
     */
    private fun isHostAllowed(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        if (addrs.isEmpty()) return false
        return addrs.all { addr ->
            if (addr !is Inet4Address) return@all false
            if (addr.isLoopbackAddress) return@all false
            if (addr.isLinkLocalAddress) return@all false
            if (addr.isMulticastAddress) return@all false
            if (addr.isAnyLocalAddress) return@all false
            val bytes = addr.address
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            when {
                // 10.0.0.0/8
                b0 == 10 -> true
                // 172.16.0.0/12
                b0 == 172 && b1 in 16..31 -> true
                // 192.168.0.0/16
                b0 == 192 && b1 == 168 -> true
                // 100.64.0.0/10 — CGNAT / Tailscale. Permit so users
                // on a paired Tailscale network can update from a peer.
                b0 == 100 && b1 in 64..127 -> true
                else -> false
            }
        }
    }

    private fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // Default to https — the share server now speaks TLS.
            else -> "https://$trimmed"
        }
        // Strip a trailing `/version.json` or `/wyspr.apk` so the
        // user can paste either the QR URL or the link they grabbed
        // off a download page.
        val stripped = withScheme
            .removeSuffix("/")
            .removeSuffix("/version.json")
            .removeSuffix("/wyspr.apk")
            .removeSuffix("/")
        return stripped.takeIf { it.length > "https://".length }
    }

    @Suppress("DEPRECATION")
    private fun localVersionCode(): Int {
        val app = getApplication<Application>()
        return runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionCode
        }.getOrDefault(0)
    }

    private fun canRequestInstalls(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else true
    }

    /**
     * Find the package name of a system-signed package installer
     * that can handle our content URI + MIME. Returns null if none
     * available (custom ROM stripped it, no resolver, etc.).
     *
     * The system-signature check defeats the MIME-intercept attack
     * where a malicious user-installed app registers for the
     * package-archive MIME and tries to read the FileProvider URI.
     */
    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun resolveSystemPackageInstaller(uri: Uri): String? {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolvers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(probe, 0)
        }
        return resolvers.firstOrNull { ri ->
            val appInfo = ri.activityInfo?.applicationInfo ?: return@firstOrNull false
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem =
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            isSystem || isUpdatedSystem
        }?.activityInfo?.packageName
    }

    sealed interface State {
        data object Idle : State
        data class Checking(val url: String) : State
        data class Found(
            val peerUrl: String,
            val peer: PeerVersion,
            val localVersionCode: Int,
            val comparison: Comparison,
        ) : State
        data class Downloading(
            val peer: PeerVersion,
            val bytesRead: Long,
            val total: Long,
        ) : State
        data class Installing(val peer: PeerVersion) : State
        /** Surfaced when the user hasn't granted "Install unknown apps". */
        data class NeedsInstallPermission(val resumeFrom: Found) : State
        data class Failed(val message: String) : State

        enum class Comparison { Newer, Same, Older }
    }

    private companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
