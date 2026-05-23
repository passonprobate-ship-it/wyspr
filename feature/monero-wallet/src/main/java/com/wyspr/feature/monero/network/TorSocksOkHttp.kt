package com.wyspr.feature.monero.network

import android.util.Log
import com.wyspr.core.transport.TorBackend
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * OkHttp client whose every connection routes through the embedded
 * Tor SOCKS proxy advertised by [TorBackend.socksPort]. Plugged into
 * [im.molly.monero.sdk.MoneroNodeClient]'s `httpClient` slot so the
 * mollyim wallet engine talks to the configured Monero RPC node
 * over Tor, never the bare internet.
 *
 * Resolves the SOCKS port lazily through a [ProxySelector] — the Tor
 * daemon may not be bootstrapped when this client is built, so we
 * defer the port lookup to per-request time. If Tor isn't ready,
 * the request fails fast with `Proxy.NO_PROXY` (loud, predictable
 * error) rather than silently leaking out the clear interface.
 */
object TorSocksOkHttp {

    private const val TAG = "TorSocksOkHttp"

    /**
     * Build the singleton OkHttp client. Hilt provides this via
     * [com.wyspr.feature.monero.di.MoneroModule]. Reuse it across
     * all Monero requests — OkHttp pools connections internally.
     */
    fun build(torBackend: TorBackend): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(TorProxySelector(torBackend))
        // Modest read timeout — Tor circuits can be slow but the
        // Monero RPC is mostly small JSON. A 60s ceiling catches
        // wedged circuits without prematurely killing a real-but-
        // slow request.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * ProxySelector that defers Tor SOCKS port resolution to
     * request time. Same instance reused across all routes;
     * [okhttp3.OkHttpClient] consults it per-request.
     */
    private class TorProxySelector(
        private val torBackend: TorBackend,
    ) : java.net.ProxySelector() {

        override fun select(uri: java.net.URI?): List<Proxy> {
            val port = torBackend.socksPort.value
            if (port == null) {
                Log.w(TAG, "select($uri): Tor SOCKS port not yet available; using NO_PROXY (will fail-fast)")
                return listOf(Proxy.NO_PROXY)
            }
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        }

        override fun connectFailed(
            uri: java.net.URI?,
            sa: java.net.SocketAddress?,
            ioe: java.io.IOException?,
        ) {
            Log.w(TAG, "connectFailed($uri via $sa): ${ioe?.javaClass?.simpleName}: ${ioe?.message}")
            // No fallback — Tor is the only allowed route. Caller
            // sees the IOException.
        }
    }
}
