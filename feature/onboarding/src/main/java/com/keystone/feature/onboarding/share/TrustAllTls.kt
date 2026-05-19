package com.keystone.feature.onboarding.share

import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The in-app peer-update HTTP client trusts ANY TLS peer the
 * `https://<lan-ip>:8080/` URL resolves to. This is deliberate.
 *
 * Integrity of the downloaded APK is anchored in [ApkDownloader]'s
 * SHA-256 verification — the receiver streams the bytes through a
 * digest as they arrive and refuses any output whose hash doesn't
 * match the value announced by `/version.json`. Even if a MITM
 * substituted a different APK on the wire, the hash check would
 * reject it. PKI offers nothing beyond what the SHA-256 already
 * provides at this trust level (paired peer, hash announced over
 * the same channel, browser-shown for visual cross-check).
 *
 * What HTTPS gives us at the TLS layer:
 *   - Brave / Chrome HTTPS-Only mode loads the URL at all (the
 *     entire reason we generate the cert in the first place).
 *   - Passive same-LAN observers don't get the APK bytes in clear.
 *
 * Neither requires the cert to chain back to a real CA. A
 * self-signed cert per session is enough.
 *
 * ## Scope
 *
 * Applied ONLY to [HttpsURLConnection] instances built explicitly
 * by [UpdateChecker] and [ApkDownloader] — never installed
 * process-wide, never touches OkHttp / Ktor / anything else.
 */
internal object TrustAllTls {

    private val sslContext: SSLContext by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
    }

    private val socketFactory: SSLSocketFactory by lazy { sslContext.socketFactory }

    private val acceptAllHostnames: HostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }

    /**
     * Configure an `HttpURLConnection` to accept any TLS peer. No-op
     * for plain HTTP — those connections aren't TLS-wrapped at all.
     */
    fun applyTo(conn: HttpURLConnection) {
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = socketFactory
            conn.hostnameVerifier = acceptAllHostnames
        }
    }
}
