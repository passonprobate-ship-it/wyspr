package com.keystone.feature.onboarding.share

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Peer-to-peer APK share server. The Inviter starts this when they
 * meet someone in person who doesn't yet have Keystone installed; the
 * Invitee scans a QR encoding `http://<inviter-ip>:8080/` with their
 * stock camera, lands on a tiny single-page explainer, taps Agree,
 * and downloads the APK directly from the Inviter's device.
 *
 * No app store. No central server. No internet — same-WiFi only in
 * Phase 1. The traffic never leaves the local network.
 *
 * ## Routes
 *   GET /                 → mini-site (HTML, inline CSS, no JS)
 *   GET /version.json     → machine-readable metadata used by the in-app
 *                           "Update from a peer" flow on the receiving
 *                           device. Returns `{versionCode, versionName,
 *                           apkSha256, apkSizeBytes}`.
 *   GET /keystone.apk     → APK bytes (Content-Disposition: attachment)
 *   GET /robots.txt       → `User-agent: *  Disallow: /` (out of caution
 *                           in case the device is briefly on a public
 *                           network and an indexer happens past)
 *   anything else         → 404
 *
 * ## Lifecycle
 *
 * Start once per share session (the [ApkSharingViewModel] manages it),
 * stop when the user leaves the share screen. Sockets are released
 * synchronously on [stop]; restart is supported but discouraged.
 *
 * ## Privacy / security notes
 *
 *   - HTTP, not HTTPS. The Invitee's browser will show "Not secure" in
 *     the URL bar. Acceptable: the user just scanned a QR from a phone
 *     standing next to them, and self-signed HTTPS would prompt a
 *     scarier-looking certificate warning. The APK SHA-256 is shown on
 *     the mini-site so the Invitee can verify out-of-band.
 *   - The server only responds while the share screen is open; no
 *     background socket lingers.
 *   - Anyone on the same LAN can hit the URL during that window, but
 *     the worst they can do is download the same APK we'd give the
 *     Invitee. There is no path through this server into the
 *     Inviter's data — the APK file is the only thing served.
 */
internal class ApkShareServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    val apkSha256: String by lazy { computeApkSha256() }
    val apkSizeBytes: Long by lazy { apkFile().length() }
    val versionName: String by lazy { resolveVersionName() }
    val versionCode: Int by lazy { resolveVersionCode() }

    override fun serve(session: IHTTPSession): Response = when (session.uri.trimEnd('/')) {
        "", "/index.html" -> indexResponse()
        "/version.json" -> versionJsonResponse()
        "/keystone.apk" -> apkResponse()
        "/robots.txt" -> newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            "User-agent: *\nDisallow: /\n",
        )
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
    }

    /**
     * Hand-rolled JSON — no parser dep, no escaping subtleties. Every
     * value is a hex string, a quoted string, or a positive integer,
     * so we control the entire output and don't need a library.
     */
    private fun versionJsonResponse(): Response {
        val json = buildString {
            append('{')
            append("\"versionCode\":").append(versionCode).append(',')
            append("\"versionName\":\"").append(jsonEscape(versionName)).append("\",")
            append("\"apkSha256\":\"").append(apkSha256).append("\",")
            append("\"apkSizeBytes\":").append(apkSizeBytes)
            append('}')
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json,
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    private fun indexResponse(): Response {
        val html = ShareApkSite.renderHtml(
            versionName = versionName,
            apkSha256 = apkSha256,
            apkSizeBytes = apkSizeBytes,
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html,
        ).apply {
            // Don't let middleboxes/caches keep a copy: the share window
            // is bounded and the URL is single-use.
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun apkResponse(): Response {
        val file = apkFile()
        val stream = FileInputStream(file)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.android.package-archive",
            stream,
            file.length(),
        )
        response.addHeader(
            "Content-Disposition",
            "attachment; filename=\"keystone-$versionName.apk\"",
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun apkFile(): File = File(context.applicationInfo.sourceDir)

    private fun computeApkSha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(apkFile()).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveVersionName(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    @Suppress("DEPRECATION")
    private fun resolveVersionCode(): Int =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrNull() ?: 0

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
