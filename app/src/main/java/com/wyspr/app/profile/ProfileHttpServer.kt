package com.wyspr.app.profile

import android.app.Application
import android.util.Log
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.UserProfileEntity
import com.wyspr.core.transport.TorBackend
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tiny HTTP/1.1 server that serves the local user's profile page on
 * `127.0.0.1:WEB_TARGET_PORT`. The embedded Tor daemon's
 * `HiddenServicePort 80 → 127.0.0.1:WEB_TARGET_PORT` mapping forwards
 * `<onion>:80` traffic to this listener, so any Tor-aware browser
 * (Tor Browser, Orbot+Chrome, curl --socks5 ...) can fetch the page.
 *
 * Scope is intentionally minimal:
 * - Single endpoint: `GET /` returns the user's profile HTML.
 * - No POST, no auth, no cookies, no session state.
 * - Header parsing limited to the request line. Body of incoming
 *   requests is consumed but ignored.
 *
 * The page is recomputed from the DB on every request so edits land
 * instantly.
 *
 * Lifecycle: started by [com.wyspr.app.profile.ProfileServerLifecycle]
 * when Tor reaches Ready state; stopped when Tor stops or the app
 * shuts down. Bind is on loopback only — the listener is never
 * reachable from the network unless Tor forwards a circuit to it.
 */
@Singleton
class ProfileHttpServer @Inject constructor(
    private val application: Application,
    private val database: WysprDatabase,
) {
    private val apkFile: File get() = File(application.applicationInfo.sourceDir)
    private val apkSha256: String by lazy { computeApkSha256() }
    private val apkSizeBytes: Long by lazy { apkFile.length() }

    private val lock = Mutex()
    /** Cap concurrent connections to prevent resource exhaustion. */
    private val connectionSemaphore = Semaphore(10)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var acceptJob: Job? = null

    suspend fun start() = lock.withLock {
        if (server != null) {
            Log.d(TAG, "already running")
            return@withLock
        }
        // Bind explicitly to IPv4 loopback. Tor's `HiddenServicePort
        // 80 WEB_TARGET_PORT` (configured in EmbeddedTorBackend)
        // forwards inbound .onion:80 traffic to 127.0.0.1, ALWAYS
        // IPv4. `InetAddress.getLoopbackAddress()` returns `::1`
        // (IPv6) on dual-stack Androids, which leaves Tor's last hop
        // hitting nothing and the user's profile page silently
        // 404-ing over Tor while the server is "running" locally.
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val s = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    java.net.InetSocketAddress(
                        ipv4Loopback,
                        TorBackend.WEB_TARGET_PORT,
                    ),
                )
            }
        }
        Log.d(TAG, "listening on 127.0.0.1:${TorBackend.WEB_TARGET_PORT}")
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = s
        scope = sc
        acceptJob = sc.launch { runAcceptLoop(s) }
    }

    suspend fun stop() = lock.withLock {
        val s = server ?: return@withLock
        Log.d(TAG, "stopping")
        server = null
        withContext(Dispatchers.IO) { runCatching { s.close() } }
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        acceptJob = null
    }

    private suspend fun runAcceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                withContext(Dispatchers.IO) { s.accept() }
            } catch (t: Throwable) {
                if (!s.isClosed) Log.w(TAG, "accept failed", t)
                return
            }
            scope?.launch {
                connectionSemaphore.acquire()
                try {
                    handleClient(client)
                } finally {
                    connectionSemaphore.release()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                client.soTimeout = REQUEST_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return@withContext
                // Drain request headers; we don't use them, but the
                // client expects us to read past them before responding.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val (method, path) = parseRequestLine(requestLine) ?: run {
                    writeBadRequest(client); return@withContext
                }
                if (method != "GET") {
                    writeMethodNotAllowed(client); return@withContext
                }
                when {
                    path == "/" || path.startsWith("/?") -> {
                        val html = renderHtml()
                        writeOk(client, html)
                    }
                    path == "/version.json" -> {
                        val json = buildString {
                            append("{\"versionCode\":${com.wyspr.app.BuildConfig.VERSION_CODE},")
                            append("\"versionName\":\"${com.wyspr.app.BuildConfig.VERSION_NAME}\",")
                            append("\"apkSha256\":\"$apkSha256\",")
                            append("\"apkSizeBytes\":$apkSizeBytes}")
                        }
                        writeOkJson(client, json)
                    }
                    path == "/wyspr.apk" -> writeApkResponse(client)
                    path == "/favicon.ico" -> writeNoContent(client)
                    else -> writeNotFound(client)
                }
            } catch (t: Throwable) {
                // Log but don't surface — the peer just sees a TCP
                // close, which is fine for a malformed request.
                Log.d(TAG, "client handler error: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        val parts = line.split(' ')
        if (parts.size < 3) return null
        return parts[0] to parts[1]
    }

    /**
     * Snapshot the profile via the DAO and render. If the DB isn't
     * open yet (user hasn't unlocked / launched fully) we fall back
     * to a placeholder page so a Tor visitor doesn't see a TCP RST.
     */
    private suspend fun renderHtml(): String {
        val profile = runCatching {
            if (database.isOpen) database.userProfileDao.get() else null
        }.getOrNull()
        return renderProfileHtml(profile)
    }

    private fun writeOk(client: Socket, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 200 OK\r\n")
        out.write("Content-Type: text/html; charset=utf-8\r\n")
        out.write("Content-Length: ${body.size}\r\n")
        out.write("Connection: close\r\n")
        out.write("X-Content-Type-Options: nosniff\r\n")
        // Tor browser usually disables JS for max safety; we render
        // pure HTML so it works either way. CSP locks down inline
        // execution as a defence-in-depth even though we never emit
        // <script>.
        out.write("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:\r\n")
        out.write("\r\n")
        out.flush()
        client.getOutputStream().write(body)
        client.getOutputStream().flush()
    }

    private fun writeOkJson(client: Socket, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 200 OK\r\n")
        out.write("Content-Type: application/json; charset=utf-8\r\n")
        out.write("Content-Length: ${body.size}\r\n")
        out.write("Connection: close\r\n")
        out.write("X-Content-Type-Options: nosniff\r\n")
        out.write("Cache-Control: no-store\r\n")
        out.write("\r\n")
        out.flush()
        client.getOutputStream().write(body)
        client.getOutputStream().flush()
    }

    private fun writeNoContent(client: Socket) {
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    private fun writeBadRequest(client: Socket) {
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    private fun writeMethodNotAllowed(client: Socket) {
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    private fun writeApkResponse(client: Socket) {
        val file = apkFile
        val size = file.length()
        val out = client.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/vnd.android.package-archive\r\n")
            append("Content-Length: $size\r\n")
            append("Content-Disposition: attachment; filename=\"wyspr.apk\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun computeApkSha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeNotFound(client: Socket) {
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    private fun renderProfileHtml(p: UserProfileEntity?): String {
        val displayName = (p?.displayName?.takeIf { it.isNotBlank() } ?: "A Wyspr user").htmlEscape()
        val avatar = (p?.avatarEmoji?.takeIf { it.isNotBlank() } ?: "◇").htmlEscape()
        val bio = p?.bio?.takeIf { it.isNotBlank() }?.htmlEscape()
        val links = p?.links?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .filter { url ->
                // Validate URL structure — reject anything that can't
                // be parsed as a proper URI.
                runCatching { java.net.URI(url) }.isSuccess
            }
        return buildString {
            append("<!doctype html>\n")
            append("<html lang=\"en\"><head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<meta name=\"referrer\" content=\"no-referrer\">\n")
            append("<title>$displayName</title>\n")
            append("<style>")
            append(BASE_CSS)
            append("</style>\n")
            append("</head><body>\n")
            // Subtle ambient backdrop — two radial gradients laid over
            // each other in the teal/blue Wyspr palette. Pure CSS,
            // no images, no JS.
            append("<div class=\"bg\"></div>\n")
            append("<main>\n")
            append("<div class=\"card\">\n")
            append("<div class=\"avatar\">$avatar</div>\n")
            append("<h1>$displayName</h1>\n")
            if (bio != null) {
                append("<p class=\"bio\">${bio.replace("\n", "<br>")}</p>\n")
            }
            if (links.isNotEmpty()) {
                append("<ul class=\"links\">\n")
                for (raw in links) {
                    val safe = raw.htmlEscape()
                    val label = safe
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .removeSuffix("/")
                    append("<li><a href=\"$safe\" rel=\"noopener noreferrer\">")
                    append("<span class=\"glyph\">↗</span><span>$label</span>")
                    append("</a></li>\n")
                }
                append("</ul>\n")
            }
            append("</div>\n")
            append("<footer>\n")
            append("  <span class=\"footer-glyph\">◇</span>\n")
            append("  <span>served by Wyspr over Tor</span>\n")
            append("  <span class=\"dot\">·</span>\n")
            append("  <span>no JavaScript</span>\n")
            append("  <span class=\"dot\">·</span>\n")
            append("  <span>no tracking</span>\n")
            append("</footer>\n")
            append("</main></body></html>\n")
        }
    }

    private fun String.htmlEscape(): String = buildString(length) {
        for (ch in this@htmlEscape) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#x27;")
            else -> append(ch)
        }
    }

    private companion object {
        private const val TAG = "ProfileHttp"
        private const val REQUEST_TIMEOUT_MS = 5_000
        private val BASE_CSS = """
            * { box-sizing: border-box; }
            html, body { margin: 0; padding: 0; }
            body {
                background: #06080b;
                color: #e5eaef;
                font: 16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
                min-height: 100vh;
                position: relative;
                overflow-x: hidden;
            }
            /* Two soft glows behind the card — top-left teal, bottom-right
             * indigo. Fixed-positioned so they stay anchored when content
             * is short. */
            .bg {
                position: fixed;
                inset: 0;
                z-index: 0;
                pointer-events: none;
                background:
                    radial-gradient(ellipse 60% 50% at 15% 10%, rgba(128, 224, 192, 0.16), transparent 60%),
                    radial-gradient(ellipse 60% 50% at 85% 90%, rgba(99, 132, 224, 0.14), transparent 60%);
            }
            main {
                position: relative;
                z-index: 1;
                max-width: 560px;
                margin: 0 auto;
                padding: 64px 20px 32px;
                min-height: 100vh;
                display: flex;
                flex-direction: column;
            }
            .card {
                background: rgba(18, 23, 30, 0.72);
                border: 1px solid rgba(128, 224, 192, 0.12);
                border-radius: 16px;
                padding: 36px 32px 32px;
                backdrop-filter: blur(20px);
                -webkit-backdrop-filter: blur(20px);
            }
            .avatar {
                font-size: 80px;
                line-height: 1;
                margin: -8px 0 18px;
                filter: drop-shadow(0 4px 24px rgba(128, 224, 192, 0.25));
            }
            h1 {
                font-size: 32px;
                margin: 0 0 4px;
                font-weight: 700;
                letter-spacing: -0.015em;
                background: linear-gradient(135deg, #e5eaef 0%, #a0c8ff 100%);
                -webkit-background-clip: text;
                background-clip: text;
                -webkit-text-fill-color: transparent;
            }
            .bio {
                color: #a8b2bd;
                margin: 20px 0 0;
                font-size: 17px;
                line-height: 1.6;
            }
            ul.links {
                list-style: none;
                padding: 0;
                margin: 28px 0 0;
                display: flex;
                flex-direction: column;
                gap: 8px;
            }
            ul.links a {
                display: flex;
                align-items: center;
                gap: 10px;
                color: #80e0c0;
                text-decoration: none;
                background: rgba(128, 224, 192, 0.06);
                border: 1px solid rgba(128, 224, 192, 0.18);
                border-radius: 10px;
                padding: 12px 14px;
                font-size: 15px;
                font-weight: 500;
                transition: background 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
                word-break: break-all;
            }
            ul.links a:hover {
                background: rgba(128, 224, 192, 0.12);
                border-color: rgba(128, 224, 192, 0.32);
                transform: translateY(-1px);
            }
            ul.links a .glyph {
                color: #80e0c0;
                font-size: 13px;
                opacity: 0.65;
                flex-shrink: 0;
            }
            footer {
                margin-top: auto;
                padding-top: 48px;
                font-size: 11px;
                color: #4a525e;
                font-family: ui-monospace,SFMono-Regular,Menlo,monospace;
                display: flex;
                flex-wrap: wrap;
                gap: 6px;
                align-items: center;
                letter-spacing: 0.02em;
            }
            footer .footer-glyph { color: #80e0c0; opacity: 0.5; }
            footer .dot { opacity: 0.4; }
            @media (max-width: 480px) {
                main { padding: 32px 16px 24px; }
                .card { padding: 28px 22px 24px; border-radius: 14px; }
                .avatar { font-size: 64px; }
                h1 { font-size: 26px; }
            }
            @media (prefers-color-scheme: light) {
                body { background: #f5f6f8; color: #101418; }
                .bg {
                    background:
                        radial-gradient(ellipse 60% 50% at 15% 10%, rgba(96, 180, 150, 0.18), transparent 60%),
                        radial-gradient(ellipse 60% 50% at 85% 90%, rgba(80, 110, 200, 0.14), transparent 60%);
                }
                .card { background: rgba(255, 255, 255, 0.78); border-color: rgba(0, 50, 40, 0.08); }
                h1 { background: linear-gradient(135deg, #101418 0%, #1e3a5f 100%); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
                .bio { color: #3a4350; }
                ul.links a { color: #1e3a5f; background: rgba(30, 58, 95, 0.06); border-color: rgba(30, 58, 95, 0.18); }
                ul.links a:hover { background: rgba(30, 58, 95, 0.10); border-color: rgba(30, 58, 95, 0.32); }
                ul.links a .glyph { color: #1e3a5f; }
                footer { color: #8a929e; }
                footer .footer-glyph { color: #1e3a5f; opacity: 0.55; }
            }
        """.trimIndent()
    }
}
