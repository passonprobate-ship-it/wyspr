package com.keystone.core.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.keystone.core.identity.CommunityId
import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.ServiceUuid
import com.keystone.core.transport.Transport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * BLE GATT transport — discovery + connect + accept. PROTOCOLS.md §6.
 *
 * Topology:
 *   - Both devices act as advertiser/scanner AND GATT server/client.
 *     One side calls [connect] (acts as GATT client) and the other
 *     side's GATT server emits the accepted [Link] on [acceptedLinks].
 *   - One service UUID per community (derived from CommunityId).
 *   - One read/write/notify characteristic carries length-prefixed
 *     Noise-encrypted frames. The CCCD descriptor is required so the
 *     client can subscribe to notifications.
 *   - MTU negotiation runs after connect; default 23 is too small for
 *     a single Noise XX message, so we request 247.
 *
 * Permission handling is the caller's job — see [BlePermissions]. We
 * suppress @MissingPermission and let SecurityException propagate.
 */
class BleTransport(private val context: Context) : Transport {

    override val kind: Transport.Kind = Transport.Kind.BluetoothLe

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val lock = Mutex()

    @Volatile private var session: Session? = null

    private class Session(
        val community: CommunityId,
        val serviceUuid: ParcelUuid,
        val characteristicUuid: UUID,
        val advertiseCallback: AdvertiseCallback,
        val scanCallback: ScanCallback,
        val gattServer: BluetoothGattServer,
        val discovered: MutableSharedFlow<PeerEndpoint>,
        /**
         * Channel — not SharedFlow — so a Link emitted while the
         * Invitee's subscriber is still being set up doesn't get lost.
         * The Invitee calls acceptedLinks().first() exactly once per
         * onboarding session, so a single-consumer Channel is correct.
         */
        val accepted: Channel<Link>,
        val acceptedLinks: ConcurrentHashMap<String, BleLink>,
        val ioScope: CoroutineScope,
    )

    val isBluetoothReady: Boolean
        get() = bluetoothManager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override suspend fun start(communityId: CommunityId): Unit = lock.withLock {
        if (session != null) return@withLock
        val adapter = bluetoothManager?.adapter ?: error("BLE not available on this device")
        check(adapter.isEnabled) { "Bluetooth is off; please enable it" }
        check(BlePermissions.allGranted(context)) {
            "Missing BLE runtime permissions: ${BlePermissions.missing(context)}"
        }

        val serviceParcelUuid = ParcelUuid(ServiceUuid.forCommunity(communityId))
        val serviceUuid = serviceParcelUuid.uuid
        val charUuid = ServiceUuid.characteristicForCommunity(communityId)

        val discovered = MutableSharedFlow<PeerEndpoint>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val accepted = Channel<Link>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val acceptedLinks = ConcurrentHashMap<String, BleLink>()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val gattServer = openGattServer(
            ioScope = ioScope,
            serviceUuid = serviceUuid,
            characteristicUuid = charUuid,
            accepted = accepted,
            acceptedLinks = acceptedLinks,
        )

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                discovered.tryEmit(PeerEndpoint(Transport.Kind.BluetoothLe, address))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) { /* telemetry hook */ }
        }
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { /* telemetry hook */ }
        }

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceParcelUuid)
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(serviceParcelUuid)
            .build()

        val advertiser = adapter.bluetoothLeAdvertiser
            ?: error("This device's BLE adapter does not support advertising")
        val scanner = adapter.bluetoothLeScanner
            ?: error("This device's BLE adapter does not support scanning")

        advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        session = Session(
            community = communityId,
            serviceUuid = serviceParcelUuid,
            characteristicUuid = charUuid,
            advertiseCallback = advertiseCallback,
            scanCallback = scanCallback,
            gattServer = gattServer,
            discovered = discovered,
            accepted = accepted,
            acceptedLinks = acceptedLinks,
            ioScope = ioScope,
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop(): Unit = lock.withLock {
        val current = session ?: return@withLock
        session = null
        val adapter = bluetoothManager?.adapter ?: return@withLock
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(current.advertiseCallback) }
        runCatching { adapter.bluetoothLeScanner?.stopScan(current.scanCallback) }
        runCatching { current.gattServer.close() }
        current.accepted.close()
        // close() on BleLink is suspending — drain on the dying scope so
        // ChannelClosed exceptions don't crash the stop() caller.
        current.acceptedLinks.values.forEach { link ->
            current.ioScope.launch { runCatching { link.close() } }
        }
        // Cancel the scope last so the close() launches above get to run.
        current.ioScope.coroutineContext[Job]?.cancel()
    }

    override fun discovered(): Flow<PeerEndpoint> =
        session?.discovered?.asSharedFlow() ?: emptyFlow()

    override fun acceptedLinks(): Flow<Link> =
        session?.accepted?.consumeAsFlow() ?: emptyFlow()

    @SuppressLint("MissingPermission")
    override suspend fun connect(endpoint: PeerEndpoint): Link {
        require(endpoint.kind == Transport.Kind.BluetoothLe)
        val current = session ?: error("transport not started")
        val adapter = bluetoothManager?.adapter ?: error("BLE not available")
        val device = adapter.getRemoteDevice(endpoint.opaqueAddress)
        return openGattClient(device, current.serviceUuid.uuid, current.characteristicUuid, endpoint)
    }

    // ---- GATT server (responder side) ---------------------------------

    @SuppressLint("MissingPermission")
    private fun openGattServer(
        ioScope: CoroutineScope,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        accepted: Channel<Link>,
        acceptedLinks: ConcurrentHashMap<String, BleLink>,
    ): BluetoothGattServer {
        val manager = bluetoothManager ?: error("no BluetoothManager")
        val outboundSinks = ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<ByteArray>>()

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val addr = device.address ?: return
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val link = acceptedLinks.remove(addr)
                    outboundSinks.remove(addr)?.close()
                    link?.let { ioScope.launch { runCatching { it.close() } } }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val server = this@BleTransport.session?.gattServer
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                val link = acceptedLinks[device.address] ?: run {
                    // First write from a new device — open a link
                    val endpoint = PeerEndpoint(Transport.Kind.BluetoothLe, device.address)
                    val (newLink, sink) = newBleLink(endpoint = endpoint, onClose = {
                        outboundSinks.remove(device.address)?.close()
                    })
                    acceptedLinks[device.address] = newLink
                    outboundSinks[device.address] = sink
                    val charForNotify = characteristic
                    ioScope.launch {
                        // Drain outbound sink and notify the client
                        for (frame in sink) {
                            val chunker = BleOutboundChunker(mtuPayload = DEFAULT_MTU_PAYLOAD)
                            for (chunk in chunker.chunk(frame)) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    runCatching {
                                        server?.notifyCharacteristicChanged(device, charForNotify, false, chunk)
                                    }
                                } else {
                                    @Suppress("DEPRECATION")
                                    charForNotify.value = chunk
                                    @Suppress("DEPRECATION")
                                    runCatching {
                                        server?.notifyCharacteristicChanged(device, charForNotify, false)
                                    }
                                }
                            }
                        }
                    }
                    ioScope.launch { runCatching { accepted.send(newLink) } }
                    newLink
                }
                ioScope.launch { link.ingestInbound(value) }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    this@BleTransport.session?.gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null,
                    )
                }
            }
        }

        val server = manager.openGattServer(context, callback)
            ?: error("openGattServer returned null — BLE not supported")
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // CCCD — required for clients to subscribe to notifications.
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        server.addService(service)
        return server
    }

    // ---- GATT client (initiator side) ---------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun openGattClient(
        device: BluetoothDevice,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        endpoint: PeerEndpoint,
    ): Link {
        val ioScope = session?.ioScope ?: error("transport not started")
        return suspendCancellableCoroutine { cont ->
        var gatt: BluetoothGatt?
        val outboundSink = kotlinx.coroutines.channels.Channel<ByteArray>(
            capacity = 32,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        lateinit var link: BleLink
        // Volatile-equivalent via AtomicInteger — @Volatile only applies to
        // class fields, not coroutine-captured locals.
        val clientMtu = java.util.concurrent.atomic.AtomicInteger(23)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(REQUESTED_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                clientMtu.set(mtu)
                g.discoverServices()
            }

            // Tracks the characteristic resolved in onServicesDiscovered so
            // onDescriptorWrite can finish wiring up the link AFTER the
            // CCCD subscription has been ACKed by the peer.
            @Volatile var pendingChar: BluetoothGattCharacteristic? = null

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(serviceUuid)
                if (service == null) {
                    cont.cancel(IllegalStateException("service $serviceUuid not advertised by peer"))
                    return
                }
                val ch = service.getCharacteristic(characteristicUuid)
                if (ch == null) {
                    cont.cancel(IllegalStateException("char $characteristicUuid missing on peer"))
                    return
                }
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    cont.cancel(IllegalStateException("CCCD missing on peer characteristic"))
                    return
                }
                pendingChar = ch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                // Do NOT start the outbound drainer or resume the
                // continuation here — wait for onDescriptorWrite to
                // confirm the peer received our subscription. Otherwise
                // the peer can fire notifications we drop on the floor.
            }

            // T+33 callback signature
            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid != CCCD_UUID) return
                val ch = pendingChar ?: return
                pendingChar = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    cont.cancel(IllegalStateException("CCCD write failed with status $status"))
                    return
                }
                link = BleLink(
                    endpoint = endpoint,
                    outboundSink = outboundSink,
                    onClose = { runCatching { g.disconnect(); g.close() } },
                )
                ioScope.launch {
                    val chunker = BleOutboundChunker(
                        mtuPayload = (clientMtu.get() - 3).coerceAtLeast(MIN_MTU_PAYLOAD),
                    )
                    for (frame in outboundSink) {
                        for (chunk in chunker.chunk(frame)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                g.writeCharacteristic(
                                    ch,
                                    chunk,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                ch.value = chunk
                                @Suppress("DEPRECATION")
                                ch.writeType =
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                @Suppress("DEPRECATION")
                                g.writeCharacteristic(ch)
                            }
                        }
                    }
                }
                if (cont.isActive) cont.resume(link)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                ioScope.launch { link.ingestInbound(value) }
            }

            @Deprecated("kept for pre-T platforms that haven't upgraded to the value-overload")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                onCharacteristicChanged(g, ch, ch.value ?: ByteArray(0))
            }
        }

        gatt = device.connectGatt(context, /* autoConnect = */ false, callback)
        cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
        }
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 247
        // 3-byte ATT header off the negotiated MTU; default of 20 is the
        // BLE 4.0 minimum until MTU negotiation succeeds.
        private const val DEFAULT_MTU_PAYLOAD = 244
        private const val MIN_MTU_PAYLOAD = 20
    }
}
