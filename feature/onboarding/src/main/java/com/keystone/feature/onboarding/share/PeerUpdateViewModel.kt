package com.keystone.feature.onboarding.share

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 *      APK to private cache with SHA-256 verification, then fires
 *      a VIEW intent so the system PackageInstaller can run its
 *      signature check and prompt the user.
 *
 * The receiver never auto-installs — Android's installer requires
 * an explicit user tap. The "auto" in Keystone's peer-update story
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
            _state.value = State.Failed("That doesn't look like a Keystone share URL.")
            return
        }
        _state.value = State.Checking(url)
        activeJob = viewModelScope.launch {
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
            result.fold(
                onSuccess = { file ->
                    val context = getApplication<Application>()
                    val authority = "${context.packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            _state.value = State.Failed(
                                "Downloaded successfully but couldn't open the " +
                                    "installer: ${it.message}",
                            )
                            return@onFailure
                        }
                    _state.value = State.Installing(found.peer)
                },
                onFailure = { t ->
                    _state.value = State.Failed(t.message ?: "Download failed.")
                },
            )
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

    private fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        // Strip a trailing `/version.json` or `/keystone.apk` so the
        // user can paste either the QR URL or the link they grabbed
        // off a download page.
        val stripped = withScheme
            .removeSuffix("/")
            .removeSuffix("/version.json")
            .removeSuffix("/keystone.apk")
            .removeSuffix("/")
        return stripped.takeIf { it.length > "http://".length }
    }

    @Suppress("DEPRECATION")
    private fun localVersionCode(): Int {
        val app = getApplication<Application>()
        return runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionCode
        }.getOrDefault(0)
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
        data class Failed(val message: String) : State

        enum class Comparison { Newer, Same, Older }
    }
}
