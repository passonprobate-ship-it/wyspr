package com.keystone.app.profile

import android.util.Log
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.UserProfileEntity
import com.keystone.core.transport.TorBackend
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
 * Lifecycle: started by [com.keystone.app.profile.ProfileServerLifecycle]
 * when Tor reaches Ready state; stopped when Tor stops or the app
 * shuts down. Bind is on loopback only — the listener is never
 * reachable from the network unless Tor forwards a circuit to it.
 */
@Singleton
class ProfileHttpServer @Inject constructor(
    private val database: KeystoneDatabase,
) {
    private val lock = Mutex()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var acceptJob: Job? = null

    suspend fun start() = lock.withLock {
        if (server != null) {
            Log.d(TAG, "already running")
            return@withLock
        }
        val s = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    java.net.InetSocketAddress(
                        InetAddress.getLoopbackAddress(),
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
            scope?.launch { handleClient(client) }
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

    private fun writeNotFound(client: Socket) {
        val out = OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1)
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    private fun renderProfileHtml(p: UserProfileEntity?): String {
        val displayName = (p?.displayName?.takeIf { it.isNotBlank() } ?: "A Keystone user").htmlEscape()
        val avatar = (p?.avatarEmoji?.takeIf { it.isNotBlank() } ?: "◇").htmlEscape()
        val bio = p?.bio?.takeIf { it.isNotBlank() }?.htmlEscape()
        val links = p?.links?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
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
            append("</head><body><main>\n")
            append("<div class=\"avatar\">$avatar</div>\n")
            append("<h1>$displayName</h1>\n")
            if (bio != null) {
                append("<p class=\"bio\">${bio.replace("\n", "<br>")}</p>\n")
            }
            if (links.isNotEmpty()) {
                append("<ul class=\"links\">\n")
                for (raw in links) {
                    val safe = raw.htmlEscape()
                    append("<li><a href=\"$safe\" rel=\"noopener noreferrer\">$safe</a></li>\n")
                }
                append("</ul>\n")
            }
            append("<footer>served by Keystone over Tor · no JavaScript · no tracking</footer>\n")
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
            body { background: #06080b; color: #e5eaef; font: 16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; margin: 0; padding: 48px 16px; min-height: 100vh; }
            main { max-width: 540px; margin: 0 auto; }
            .avatar { font-size: 72px; line-height: 1; margin-bottom: 16px; }
            h1 { font-size: 28px; margin: 0 0 16px; font-weight: 600; letter-spacing: -0.01em; }
            .bio { color: #a8b2bd; margin: 16px 0 24px; font-size: 17px; }
            ul.links { list-style: none; padding: 0; margin: 24px 0; }
            ul.links li { margin: 6px 0; }
            ul.links a { color: #80e0c0; text-decoration: none; border-bottom: 1px solid #1a3a2e; padding-bottom: 1px; word-break: break-all; }
            ul.links a:hover { border-bottom-color: #80e0c0; }
            footer { margin-top: 48px; font-size: 12px; color: #2a323d; font-family: ui-monospace,SFMono-Regular,Menlo,monospace; }
            @media (prefers-color-scheme: light) {
                body { background: #f7f7f5; color: #101418; }
                .bio { color: #3a4350; }
                ul.links a { color: #1e3a5f; border-bottom-color: #d6e2f0; }
                ul.links a:hover { border-bottom-color: #1e3a5f; }
                footer { color: #7a8290; }
            }
        """.trimIndent()
    }
}
