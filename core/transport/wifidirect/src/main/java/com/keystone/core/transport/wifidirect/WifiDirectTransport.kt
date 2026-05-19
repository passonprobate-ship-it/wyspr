package com.keystone.core.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import com.keystone.core.identity.CommunityId
import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.ServiceUuid
import com.keystone.core.transport.Transport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WiFi Direct transport — discovery half. PROTOCOLS.md §6.
 *
 * v0.1 scope:
 *   - Publish a Bonjour-style local service whose name encodes the
 *     community UUID (no device name, no per-device identifier).
 *   - Discover other peers publishing the same service name.
 *   - Stream them as [PeerEndpoint]s.
 *
 * v0.2 (deferred):
 *   - connect() — invite peer + open TCP socket on the group owner
 *   - Link impl with length-prefixed framing over TCP
 *
 * BLE remains the primary handshake transport. WiFi Direct is reserved
 * for high-bandwidth post-handshake sync sessions, and is initiated
 * only after both sides have authenticated over BLE first.
 */
class WifiDirectTransport(private val context: Context) : Transport {

    override val kind: Transport.Kind = Transport.Kind.WifiDirect

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private val lock = Mutex()
    @Volatile private var session: Session? = null

    private data class Session(
        val community: CommunityId,
        val channel: WifiP2pManager.Channel,
        val serviceName: String,
        val serviceInfo: WifiP2pDnsSdServiceInfo,
        val serviceRequest: WifiP2pDnsSdServiceRequest,
        val receiver: BroadcastReceiver,
        val discovered: MutableSharedFlow<PeerEndpoint>,
    )

    val isWifiP2pReady: Boolean get() = manager != null

    @SuppressLint("MissingPermission")
    override suspend fun start(communityId: CommunityId): Unit = lock.withLock {
        if (session != null) return@withLock
        val m = manager ?: error("WiFi Direct not available")
        val channel = m.initialize(context, context.mainLooper, null)
            ?: error("WifiP2pManager.initialize returned null")

        val serviceUuid = ServiceUuid.forCommunity(communityId).toString()
        val serviceName = "keystone-$serviceUuid"

        val discovered = MutableSharedFlow<PeerEndpoint>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val info = WifiP2pDnsSdServiceInfo.newInstance(
            serviceName,
            "_keystone._tcp",
            emptyMap<String, String>(),
        )
        m.addLocalService(channel, info, null)

        val request = WifiP2pDnsSdServiceRequest.newInstance(serviceName, "_keystone._tcp")
        m.addServiceRequest(channel, request, null)

        val onResponse = WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
            if (instanceName == serviceName) {
                discovered.tryEmit(
                    PeerEndpoint(Transport.Kind.WifiDirect, device.deviceAddress ?: return@DnsSdServiceResponseListener),
                )
            }
        }
        m.setDnsSdResponseListeners(channel, onResponse, null)
        m.discoverServices(channel, null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                    // Peer set changed — re-query services so the listener fires.
                    m.discoverServices(channel, null)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))

        session = Session(
            community = communityId,
            channel = channel,
            serviceName = serviceName,
            serviceInfo = info,
            serviceRequest = request,
            receiver = receiver,
            discovered = discovered,
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop(): Unit = lock.withLock {
        val current = session ?: return@withLock
        session = null
        val m = manager ?: return@withLock
        runCatching { m.removeLocalService(current.channel, current.serviceInfo, null) }
        runCatching { m.removeServiceRequest(current.channel, current.serviceRequest, null) }
        runCatching { m.clearServiceRequests(current.channel, null) }
        runCatching { context.unregisterReceiver(current.receiver) }
    }

    override fun discovered(): Flow<PeerEndpoint> =
        session?.discovered?.asSharedFlow() ?: emptyFlow()

    override suspend fun connect(endpoint: PeerEndpoint): Link {
        require(endpoint.kind == Transport.Kind.WifiDirect)
        TODO(
            "v0.2: implement WifiP2pManager.connect + TCP socket on the " +
                "group-owner IP. v0.1 ships with BLE-only handshakes; this " +
                "transport is currently discovery-only."
        )
    }
}
