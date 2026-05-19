package com.keystone.feature.onboarding.share

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Streams the peer's APK from `{baseUrl}/keystone.apk` into the app's
 * private cache directory, verifying the announced SHA-256 as bytes
 * arrive. On hash mismatch the partial file is deleted and the
 * operation fails — never leaves a malformed APK on disk to be
 * accidentally installed later.
 *
 * The output path lives under `context.cacheDir/peer-updates/` so it
 * is automatically scrubbed by the OS under storage pressure and
 * never lingers across uninstalls.
 *
 * Signature verification happens at install time: Android's
 * PackageInstaller refuses any APK whose signer differs from the
 * currently-installed app's signer. We rely on that system
 * enforcement rather than re-implementing it here.
 */
internal object ApkDownloader {

    /**
     * Streams the APK, writing to a private cache file and rejecting
     * any mismatch against [expectedSha256]. Reports byte-level
     * progress through [onProgress] so the UI can render a bar.
     *
     * @return the local file containing the verified APK bytes.
     */
    fun download(
        context: Context,
        baseUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = runCatching {
        require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "expected SHA-256 must be 64 hex chars"
        }
        val cacheDir = File(context.cacheDir, "peer-updates").apply { mkdirs() }
        val dest = File(cacheDir, "keystone-incoming.apk")
        // Always start fresh — a half-finished file from a previous
        // download must not contaminate this one.
        if (dest.exists()) dest.delete()

        val target = "${baseUrl.trim().trimEnd('/')}/keystone.apk"
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "Keystone/UpdateDownloader")
        }
        TrustAllTls.applyTo(conn)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from $target")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSizeBytes

            val md = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            dest.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        bytesRead += n
                        // Refuse to keep going past the announced size
                        // — a peer that claimed 50MB and tries to push
                        // 200 is either misbehaving or hostile.
                        if (bytesRead > expectedSizeBytes + SIZE_TOLERANCE_BYTES) {
                            throw IOException(
                                "peer overran announced size " +
                                    "($bytesRead > $expectedSizeBytes)",
                            )
                        }
                        onProgress(bytesRead, total)
                    }
                }
            }

            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != expectedSha256) {
                dest.delete()
                throw IOException(
                    "downloaded APK SHA-256 doesn't match announced " +
                        "(got $actual, expected $expectedSha256)",
                )
            }
            dest
        } finally {
            conn.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 30_000
    /** Allow a tiny over-read budget for HTTP chunk framing. */
    private const val SIZE_TOLERANCE_BYTES = 1024L
}
