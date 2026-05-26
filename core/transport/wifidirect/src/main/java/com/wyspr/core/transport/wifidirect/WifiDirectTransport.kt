package com.wyspr.core.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.ServiceUuid
import com.wyspr.core.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private const val TCP_PORT = 9094
private const val ACCEPT_BACKLOG = 50

class WifiDirectTransport(private val context: Context) : Transport {

    override val kind: Transport.Kind = Transport.Kind.WifiDirect

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private val lock = Mutex()
    @Volatile private var session: Session? = null

    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Session(
        val community: CommunityId,
        val channel: WifiP2pManager.Channel,
        val serviceName: String,
        val serviceInfo: WifiP2pDnsSdServiceInfo,
        val serviceRequest: WifiP2pDnsSdServiceRequest,
        val receiver: BroadcastReceiver,
        val discovered: MutableSharedFlow<PeerEndpoint>,
        val serverSocket: ServerSocket,
        val acceptJob: Job,
        val accepted: Channel<Link>,
    )

    val isWifiP2pReady: Boolean get() = manager != null

    @SuppressLint("MissingPermission")
    override suspend fun start(communityId: CommunityId): Unit = lock.withLock {
        if (session != null) return@withLock
        val m = manager ?: error("WiFi Direct not available")
        val channel = m.initialize(context, context.mainLooper, null)
            ?: error("WifiP2pManager.initialize returned null")

        val serviceUuid = ServiceUuid.forCommunity(communityId).toString()
        val serviceName = "wyspr-$serviceUuid"

        val discovered = MutableSharedFlow<PeerEndpoint>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val info = WifiP2pDnsSdServiceInfo.newInstance(
            serviceName,
            "_wyspr._tcp",
            emptyMap<String, String>(),
        )
        m.addLocalService(channel, info, null)

        val request = WifiP2pDnsSdServiceRequest.newInstance(serviceName, "_wyspr._tcp")
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
                    m.discoverServices(channel, null)
                }
            }
        }
        val peersFilter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, peersFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, peersFilter)
        }

        val serverSocket = ServerSocket(TCP_PORT, ACCEPT_BACKLOG)
        val accepted = Channel<Link>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        val acceptJob = transportScope.launch {
            try {
                while (true) {
                    val socket = serverSocket.accept()
                    runCatching { socket.tcpNoDelay = true }
                    val link = WifiDirectLink(
                        PeerEndpoint(kind, socket.inetAddress?.hostAddress ?: "unknown"),
                        socket,
                    )
                    accepted.send(link)
                }
            } catch (_: IOException) {
                // server socket closed — stop accepting
            } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                // channel closed during stop — benign
            }
        }

        session = Session(
            community = communityId,
            channel = channel,
            serviceName = serviceName,
            serviceInfo = info,
            serviceRequest = request,
            receiver = receiver,
            discovered = discovered,
            serverSocket = serverSocket,
            acceptJob = acceptJob,
            accepted = accepted,
        )
    }

    override suspend fun stop(): Unit = lock.withLock {
        val current = session ?: return@withLock
        session = null
        val m = manager ?: return@withLock
        runCatching { m.removeLocalService(current.channel, current.serviceInfo, null) }
        runCatching { m.removeServiceRequest(current.channel, current.serviceRequest, null) }
        runCatching { m.clearServiceRequests(current.channel, null) }
        runCatching { context.unregisterReceiver(current.receiver) }
        current.acceptJob.cancel()
        current.accepted.close()
        runCatching { current.serverSocket.close() }
    }

    override fun discovered(): Flow<PeerEndpoint> =
        session?.discovered?.asSharedFlow() ?: emptyFlow()

    override fun acceptedLinks(): Flow<Link> =
        session?.accepted?.receiveAsFlow() ?: emptyFlow()

    @SuppressLint("MissingPermission")
    override suspend fun connect(endpoint: PeerEndpoint): Link = withContext(Dispatchers.IO) {
        require(endpoint.kind == Transport.Kind.WifiDirect)
        val s = session ?: error("WifiDirectTransport not started — call start() first")
        val m = manager ?: error("WiFi Direct not available")

        val config = WifiP2pConfig().apply { deviceAddress = endpoint.opaqueAddress }

        val groupOwnerIp = CompletableDeferred<InetAddress>()

        val connReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                val wifiP2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (networkInfo?.isConnected == true && wifiP2pInfo?.groupFormed == true) {
                    val addr = wifiP2pInfo.groupOwnerAddress
                    if (addr != null) {
                        groupOwnerIp.complete(addr)
                    }
                }
            }
        }

        val connFilter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(connReceiver, connFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(connReceiver, connFilter)
        }

        m.connect(s.channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* wait for broadcast */ }
            override fun onFailure(reason: Int) {
                runCatching { m.cancelConnect(s.channel, null) }
                groupOwnerIp.completeExceptionally(
                    IOException("WiFi P2P connect failed: reason=$reason"),
                )
            }
        })

        try {
            val addr = groupOwnerIp.await()
            val socket = Socket(addr, TCP_PORT)
            WifiDirectLink(endpoint, socket)
        } catch (t: Throwable) {
            runCatching { m.cancelConnect(s.channel, null) }
            throw t
        } finally {
            runCatching { context.unregisterReceiver(connReceiver) }
        }
    }
}
