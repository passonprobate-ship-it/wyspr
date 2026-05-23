package com.wyspr.app.profile

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.transport.TorBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the periodic background poll for newer peer versions and
 * exposes the result to the MainShell. The Chats tab's banner
 * subscribes to [available] and renders an update prompt for any
 * peer whose installed Wyspr is ahead of this one.
 *
 * Also drives the Tor-based APK download: the banner can trigger
 * [startTorDownload] for any peer whose enriched `/version.json`
 * includes `apkSha256` + `apkSizeBytes`. Progress is surfaced via
 * [downloadState]; on completion the system PackageInstaller is
 * launched automatically.
 */
@HiltViewModel
class PeerUpdatesViewModel @Inject constructor(
    application: Application,
    private val checker: PeerVersionChecker,
    private val torBackend: TorBackend,
) : AndroidViewModel(application) {

    val available: StateFlow<List<PeerVersionChecker.PeerUpdate>> = checker.available

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching {
                torBackend.state.first { it is TorBackend.State.Ready }
            }
            delay(FIRST_DELAY_MS)
            while (isActive) {
                runCatching { checker.refresh() }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun dismiss(peerPub: ByteArray) {
        checker.dismiss(peerPub)
    }

    fun startTorDownload(update: PeerVersionChecker.PeerUpdate) {
        if (!update.canDownload) return
        downloadJob?.cancel()
        val socksPort = torBackend.socksPort.value ?: run {
            _downloadState.value = DownloadState.Failed("Tor is not connected.")
            return
        }
        if (!canRequestInstalls()) {
            _downloadState.value = DownloadState.NeedsPermission
            return
        }
        _downloadState.value = DownloadState.Downloading(0, update.apkSizeBytes)
        downloadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                TorApkDownloader.download(
                    context = getApplication(),
                    socksPort = socksPort,
                    onion = update.peerOnion,
                    expectedSha256 = update.apkSha256,
                    expectedSizeBytes = update.apkSizeBytes,
                    onProgress = { read, total ->
                        _downloadState.value = DownloadState.Downloading(read, total)
                    },
                )
            }
            result
                .onSuccess { file -> launchInstaller(file) }
                .onFailure { t ->
                    _downloadState.value =
                        DownloadState.Failed(t.message ?: "Download failed.")
                }
        }
    }

    fun retryPermission() {
        _downloadState.value = DownloadState.Idle
    }

    fun resetDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    fun installPermissionSettingsIntent(): Intent {
        val pkg = getApplication<Application>().packageName
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun launchInstaller(file: File) {
        val context = getApplication<Application>()
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val installer = resolveSystemPackageInstaller(uri) ?: run {
            _downloadState.value = DownloadState.Failed(
                "No system installer found. Open the APK manually to install.",
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            setPackage(installer)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }
        if (launched.isFailure) {
            _downloadState.value = DownloadState.Failed(
                "Downloaded but couldn't open installer: ${launched.exceptionOrNull()?.message}",
            )
        } else {
            _downloadState.value = DownloadState.Installing
        }
    }

    private fun canRequestInstalls(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else true
    }

    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun resolveSystemPackageInstaller(uri: Uri): String? {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolvers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(probe, 0)
        }
        return resolvers.firstOrNull { ri ->
            val appInfo = ri.activityInfo?.applicationInfo ?: return@firstOrNull false
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            isSystem || isUpdated
        }?.activityInfo?.packageName
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val bytesRead: Long, val total: Long) : DownloadState
        data object Installing : DownloadState
        data object NeedsPermission : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private companion object {
        const val FIRST_DELAY_MS = 30_000L
        const val REFRESH_INTERVAL_MS = 15L * 60 * 1000
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
