package com.wyspr.app.profile

import android.content.Context
import android.util.Log
import com.wyspr.core.transport.Socks5
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Downloads a Wyspr APK from a paired peer's `.onion` over Tor. Uses
 * [Socks5.dial] to route through the local Tor SOCKS proxy, sends a
 * raw `GET /wyspr.apk` request, and streams the response to disk
 * with on-the-fly SHA-256 verification.
 *
 * Counterpart to `ApkDownloader` in `:feature:onboarding` which
 * fetches over direct HTTPS on the LAN. The Tor path is slower
 * (minutes for ~27 MB) but works from anywhere.
 */
internal object TorApkDownloader {

    fun download(
        context: Context,
        socksPort: Int,
        onion: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = runCatching {
        require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "expected SHA-256 must be 64 hex chars"
        }
        val cacheDir = File(context.cacheDir, "peer-updates").apply { mkdirs() }
        val dest = File(cacheDir, "wyspr-incoming.apk")
        if (dest.exists()) dest.delete()

        val host = "$onion.onion"
        Log.d(TAG, "dialing $host via SOCKS5 port $socksPort")
        val socket = Socks5.dial(socksPort, host, 80, connectTimeoutMs = CONNECT_TIMEOUT_MS)
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val req = buildString {
                append("GET /wyspr.apk HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Accept: application/vnd.android.package-archive\r\n")
                append("User-Agent: Wyspr/TorUpdate\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val input = socket.getInputStream()
            val headerStr = String(readHttpHeaders(input), Charsets.ISO_8859_1)
            val statusLine = headerStr.lineSequence().firstOrNull()
                ?: throw IOException("No HTTP response from $host")
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Malformed HTTP status: $statusLine")
            if (statusCode !in 200..299) {
                throw IOException("HTTP $statusCode from $host/wyspr.apk")
            }

            val contentLength = headerStr.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toLongOrNull()
            val total = contentLength ?: expectedSizeBytes

            val md = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            var ok = false
            try {
                dest.outputStream().use { fileOut ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        if (bytesRead + n > expectedSizeBytes + SIZE_TOLERANCE_BYTES) {
                            throw IOException(
                                "peer overran announced size " +
                                    "(${bytesRead + n} > $expectedSizeBytes)",
                            )
                        }
                        md.update(buf, 0, n)
                        fileOut.write(buf, 0, n)
                        bytesRead += n
                        onProgress(bytesRead, total)
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedSha256) {
                    throw IOException(
                        "Downloaded APK SHA-256 doesn't match " +
                            "(got $actual, expected $expectedSha256)",
                    )
                }
                Log.d(TAG, "download complete: $bytesRead bytes, SHA-256 verified")
                ok = true
                dest
            } finally {
                if (!ok) runCatching { dest.delete() }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHttpHeaders(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream(1024)
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) break
        }
        return buf.toByteArray()
    }

    private const val TAG = "TorApkDl"
    private const val CONNECT_TIMEOUT_MS = 60_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val SIZE_TOLERANCE_BYTES = 1024L
}
