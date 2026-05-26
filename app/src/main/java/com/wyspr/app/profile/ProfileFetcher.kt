package com.wyspr.app.profile

import android.util.Log
import com.wyspr.core.transport.Socks5
import com.wyspr.core.transport.TorBackend
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the profile page of a peer's onion through the local Tor
 * SOCKS proxy. Bypasses any system WebView proxy machinery —
 * we own the request and the network path.
 *
 * Used by the in-app profile viewer. Anonymity / cover-traffic is
 * inherited from Tor; the HTTP layer is plain GET (no cookies, no
 * referer, no JS execution by us — the WebView host renders the
 * returned HTML).
 */
@Singleton
class ProfileFetcher @Inject constructor(
    private val torBackend: TorBackend,
) {

    sealed interface Result {
        data class Ok(val html: String) : Result
        data class HttpError(val code: Int) : Result
        data object TorNotReady : Result
        data class NetworkError(val message: String) : Result
    }

    suspend fun fetch(onion: String, timeoutMs: Int = 30_000): Result {
        val cleanOnion = onion.removeSuffix(".onion")
        val host = "$cleanOnion.onion"
        val socksPort = torBackend.socksPort.value ?: return Result.TorNotReady

        return withContext(Dispatchers.IO) {
            val socket: Socket = try {
                Socks5.dial(socksPort, host, 80, timeoutMs)
            } catch (t: Throwable) {
                Log.w(TAG, "SOCKS5 dial to $host failed: ${t.message}")
                return@withContext Result.NetworkError(t.message ?: "connect failed")
            }
            try {
                socket.soTimeout = timeoutMs
                // Send a minimal HTTP/1.1 GET. No cookies, no
                // User-Agent (default is empty — the page server
                // doesn't care; this also reduces fingerprinting).
                val out = socket.getOutputStream()
                val req = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Connection: close\r\n")
                    append("Accept: text/html\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
                out.write(req)
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val statusLine = reader.readLine()
                    ?: return@withContext Result.NetworkError("empty response")
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: return@withContext Result.NetworkError("malformed status: $statusLine")

                // Drain headers — we don't need them, but the body
                // starts after a blank line per RFC 7230.
                var contentLength: Int? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull()
                    }
                }

                if (statusCode !in 200..299) {
                    return@withContext Result.HttpError(statusCode)
                }

                val body = if (contentLength != null) {
                    val capped = contentLength.coerceAtMost(MAX_BODY_NO_CL)
                    val buf = CharArray(capped)
                    var read = 0
                    while (read < capped) {
                        val n = reader.read(buf, read, capped - read)
                        if (n == -1) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    val buf = CharArray(MAX_BODY_NO_CL)
                    val n = reader.read(buf, 0, MAX_BODY_NO_CL)
                    if (n <= 0) "" else String(buf, 0, n)
                }
                Result.Ok(body)
            } catch (t: Throwable) {
                Log.w(TAG, "fetch error: ${t.message}")
                Result.NetworkError(t.message ?: "I/O error")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private companion object {
        private const val TAG = "ProfileFetcher"
        private const val MAX_BODY_NO_CL = 1024 * 1024
    }
}
