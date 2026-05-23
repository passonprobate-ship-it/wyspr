package com.wyspr.feature.onboarding.share

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

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
            setRequestProperty("User-Agent", "Wyspr/UpdateChecker")
        }
        // Accept peer's self-signed TLS cert — see TrustAllTls
        // KDoc for the integrity argument (the SHA-256 of the APK
        // is the real trust anchor, not PKI).
        TrustAllTls.applyTo(conn)
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
     * Structural JSON parse via the Android SDK's [JSONObject].
     *
     * An earlier version used hand-rolled regex over the raw body,
     * which a hostile peer could attack by encoding spoofed
     * key/value pairs inside the `versionName` string — `Regex.find`
     * returns matches in source order, so a versionName containing
     * `","apkSha256":"<spoofed>"` would cause subsequent
     * `extractString("apkSha256")` calls to pull the spoofed hex
     * and bypass the integrity check. A real JSON parser dodges
     * that whole class of cross-key confusion: keys live in the
     * object's name space, not in the raw text.
     */
    private fun parsePeerVersion(body: String): PeerVersion {
        val obj = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("malformed JSON: ${e.message}", e)
        }
        val versionCode = obj.optInt("versionCode", -1)
        val versionName = obj.optString("versionName", "")
        val apkSha256 = obj.optString("apkSha256", "")
        val apkSizeBytes = obj.optLong("apkSizeBytes", -1L)
        require(versionCode in 1..MAX_VERSION_CODE) {
            "versionCode $versionCode out of range"
        }
        require(versionName.isNotBlank() && versionName.length <= MAX_VERSION_NAME_LEN) {
            "versionName length out of range (was ${versionName.length})"
        }
        require(apkSha256.matches(Regex("^[0-9a-f]{64}$"))) { "apkSha256 must be 64 hex chars" }
        require(apkSizeBytes in 1..MAX_APK_BYTES) {
            "apkSizeBytes $apkSizeBytes out of range"
        }
        return PeerVersion(versionCode, versionName, apkSha256, apkSizeBytes)
    }

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000
    /** Hard ceiling on the announced APK size — anything over 200MB is
     * either a hostile peer or a serious problem upstream. */
    private const val MAX_APK_BYTES: Long = 200L * 1024 * 1024
    /** A peer claiming a version code more than 10k ahead of ours is
     * almost certainly hostile (real release cadence won't approach
     * this for years). Cap defends against the "always newer" trick. */
    private const val MAX_VERSION_CODE: Int = Int.MAX_VALUE - 1
    /** UI shows the versionName; a multi-MB string would OOM Compose. */
    private const val MAX_VERSION_NAME_LEN: Int = 64
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
