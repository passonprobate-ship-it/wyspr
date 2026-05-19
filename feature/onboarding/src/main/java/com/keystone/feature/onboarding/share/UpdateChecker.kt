package com.keystone.feature.onboarding.share

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app probe of a peer's [ApkShareServer]. Fetches the
 * `/version.json` endpoint and parses the small JSON document
 * declared there. Pure stdlib — no OkHttp, no Moshi — so the
 * receiver-side update path adds zero new dependencies.
 *
 * Layer-1 of peer-to-peer updates. Layer-2 (automatic discovery
 * over the BLE trust channel) and Layer-3 (K-quorum verification)
 * are deliberately out of scope here; this just lets a paired user
 * pull a newer version from a peer URL they were given.
 */
internal object UpdateChecker {

    /**
     * GET `{baseUrl}/version.json`. Returns a parsed [PeerVersion]
     * on success, or a [Result] failure with a human-readable
     * message ready to surface to the UI.
     */
    internal fun fetchVersion(baseUrl: String): Result<PeerVersion> {
        val normalised = baseUrl.trim().trimEnd('/')
        val target = "$normalised/version.json"
        return runCatching { httpGet(target) }
            .mapCatching { body -> parsePeerVersion(body) }
            .recoverCatching { t ->
                throw IOException("Couldn't fetch update info from $target: ${t.message}", t)
            }
    }

    private fun httpGet(target: String): String {
        val url = URL(target)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Keystone/UpdateChecker")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from ${url.host}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Tiny hand-rolled JSON parser tuned to ApkShareServer's exact
     * output shape: `{versionCode:Int, versionName:String,
     * apkSha256:String, apkSizeBytes:Long}`. Rejects anything
     * else so a misbehaving peer can't smuggle surprises in.
     */
    private fun parsePeerVersion(body: String): PeerVersion {
        val versionCode = extractInt(body, "versionCode")
        val versionName = extractString(body, "versionName")
        val apkSha256 = extractString(body, "apkSha256")
        val apkSizeBytes = extractLong(body, "apkSizeBytes")
        require(versionCode > 0) { "versionCode must be > 0" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(apkSha256.matches(Regex("^[0-9a-f]{64}$"))) { "apkSha256 must be 64 hex chars" }
        require(apkSizeBytes in 1..MAX_APK_BYTES) {
            "apkSizeBytes $apkSizeBytes out of range"
        }
        return PeerVersion(versionCode, versionName, apkSha256, apkSizeBytes)
    }

    private fun extractInt(body: String, key: String): Int {
        val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(body)
            ?: throw IOException("missing integer $key")
        return m.groupValues[1].toInt()
    }

    private fun extractLong(body: String, key: String): Long {
        val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(body)
            ?: throw IOException("missing integer $key")
        return m.groupValues[1].toLong()
    }

    private fun extractString(body: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: throw IOException("missing string $key")
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000
    /** Hard ceiling on the announced APK size — anything over 200MB is
     * either a hostile peer or a serious problem upstream. */
    private const val MAX_APK_BYTES: Long = 200L * 1024 * 1024
}

/**
 * Public view of a peer's `version.json` payload. Lives at the top
 * level (not nested inside `UpdateChecker`) so it can flow through
 * [PeerUpdateViewModel.State] without the wrapping object having to
 * become public.
 */
data class PeerVersion(
    val versionCode: Int,
    val versionName: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
)
