package com.wyspr.app.profile

import android.util.Log
import com.wyspr.app.BuildConfig
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.Socks5
import com.wyspr.core.transport.TorBackend
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Polls every paired peer's `<onion>:80/version.json` over Tor and
 * exposes a StateFlow of peers whose installed Wyspr is newer than
 * the local build. The Chats tab subscribes to this and shows a
 * banner inviting the user to update from the peer.
 *
 * Why this works: every Wyspr install runs an always-on
 * [ProfileHttpServer] reachable at its HSv3 `.onion`, and that
 * server now exposes `/version.json` — a tiny `{versionCode,
 * versionName}` snapshot. Paired peers already have each other's
 * onion captured at handshake time on the `trust_edge` row.
 *
 * Caller responsibilities: a single background job calls
 * [refresh] periodically (see the auto-sync loop on the
 * conversation list). [available] is the cached set surfaced to
 * UI; it remembers state across screens but doesn't persist to disk
 * — a fresh process re-polls on first refresh.
 */
@Singleton
class PeerVersionChecker @Inject constructor(
    private val database: WysprDatabase,
    private val torBackend: TorBackend,
    private val keystore: KeystoreManager,
) {

    data class PeerUpdate(
        val peerPub: ByteArray,
        val peerOnion: String,
        val peerVersionCode: Int,
        val peerVersionName: String,
        val apkSha256: String,
        val apkSizeBytes: Long,
    ) {
        val canDownload: Boolean get() = apkSha256.length == 64 && apkSizeBytes > 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeerUpdate) return false
            return peerPub.contentEquals(other.peerPub) &&
                peerOnion == other.peerOnion &&
                peerVersionCode == other.peerVersionCode &&
                peerVersionName == other.peerVersionName &&
                apkSha256 == other.apkSha256 &&
                apkSizeBytes == other.apkSizeBytes
        }
        override fun hashCode(): Int {
            var r = peerPub.contentHashCode()
            r = 31 * r + peerOnion.hashCode()
            r = 31 * r + peerVersionCode.hashCode()
            r = 31 * r + peerVersionName.hashCode()
            r = 31 * r + apkSha256.hashCode()
            r = 31 * r + apkSizeBytes.hashCode()
            return r
        }
    }

    private val _available = MutableStateFlow<List<PeerUpdate>>(emptyList())
    /** Peers running a newer versionCode than this build. */
    val available: StateFlow<List<PeerUpdate>> = _available.asStateFlow()

    /** Dismissed in-memory only — re-emerges on next process start. */
    private val dismissed = mutableSetOf<List<Byte>>()
    fun dismiss(peerPub: ByteArray) {
        dismissed.add(peerPub.toList())
        _available.value = _available.value.filterNot { it.peerPub.toList() in dismissed }
    }

    /**
     * Poll every paired peer's `/version.json`. Slow (sequential
     * SOCKS5 dials over Tor; expect 1-5 s each), so call this on a
     * background scope with a generous interval. The result replaces
     * the cache atomically.
     */
    suspend fun refresh() {
        if (!database.isOpen) return
        val socksPort = torBackend.socksPort.value ?: return
        val edges = withContext(Dispatchers.IO) {
            runCatching { database.trustEdgeDao.all() }.getOrDefault(emptyList())
        }
        val onions = edges
            .mapNotNull { it.peerOnion?.takeIf { o -> o.length == 56 } to it }
            .filter { (onion, _) -> onion != null }
            .map { (onion, edge) -> onion!! to edge }
        if (onions.isEmpty()) {
            _available.value = emptyList()
            return
        }
        val results = mutableListOf<PeerUpdate>()
        for ((onion, edge) in onions) {
            val ownPub = keystore.loadOrCreateIdentityKey().publicKey
            val peerPub = if (edge.fromPub.contentEquals(ownPub)) edge.toPub else edge.fromPub
            if (peerPub.toList() in dismissed) continue
            val info = probeOnce(socksPort, onion) ?: continue
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                results.add(
                    PeerUpdate(
                        peerPub = peerPub,
                        peerOnion = onion,
                        peerVersionCode = info.versionCode,
                        peerVersionName = info.versionName,
                        apkSha256 = info.apkSha256,
                        apkSizeBytes = info.apkSizeBytes,
                    ),
                )
            }
        }
        _available.value = results
    }

    private suspend fun probeOnce(socksPort: Int, onion: String): VersionInfo? =
        withContext(Dispatchers.IO) {
            val host = "$onion.onion"
            val socket: Socket = try {
                Socks5.dial(socksPort, host, 80, connectTimeoutMs = PROBE_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.d(TAG, "probe $host: dial failed (${t.message})")
                return@withContext null
            }
            try {
                socket.soTimeout = PROBE_TIMEOUT_MS
                val out = socket.getOutputStream()
                val req = buildString {
                    append("GET /version.json HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Connection: close\r\n")
                    append("Accept: application/json\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
                out.write(req)
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val statusLine = reader.readLine() ?: return@withContext null
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: return@withContext null
                if (statusCode !in 200..299) return@withContext null
                var contentLength: Int? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull()
                    }
                }
                val body = if (contentLength != null) {
                    val buf = CharArray(contentLength.coerceAtMost(2048))
                    var read = 0
                    while (read < buf.size) {
                        val n = reader.read(buf, read, buf.size - read)
                        if (n == -1) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    reader.readText().take(2048)
                }
                runCatching {
                    val json = JSONObject(body)
                    VersionInfo(
                        versionCode = json.getInt("versionCode"),
                        versionName = json.optString("versionName", "?"),
                        apkSha256 = json.optString("apkSha256", ""),
                        apkSizeBytes = json.optLong("apkSizeBytes", -1L),
                    )
                }.getOrNull()
            } catch (t: Throwable) {
                Log.d(TAG, "probe $host: ${t.message}")
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    private data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkSha256: String,
        val apkSizeBytes: Long,
    )

    private companion object {
        private const val TAG = "PeerVersionCheck"
        /** Tor circuits are slow on cold start; allow a generous budget. */
        private const val PROBE_TIMEOUT_MS = 30_000
    }
}
