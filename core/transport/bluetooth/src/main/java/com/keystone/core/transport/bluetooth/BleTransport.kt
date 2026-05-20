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
import android.util.Log
import com.keystone.core.identity.CommunityId
import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.ServiceUuid
import com.keystone.core.transport.Transport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        if (session != null) {
            Log.d(TAG, "start: already running for community ${communityId.bytes.take(4)}…")
            return@withLock
        }
        val adapter = bluetoothManager?.adapter ?: error("BLE not available on this device")
        check(adapter.isEnabled) { "Bluetooth is off; please enable it" }
        check(BlePermissions.allGranted(context)) {
            "Missing BLE runtime permissions: ${BlePermissions.missing(context)}"
        }

        val serviceParcelUuid = ParcelUuid(ServiceUuid.forCommunity(communityId))
        Log.d(TAG, "start: communityId=${communityId.bytes.take(4).joinToString("") { "%02x".format(it) }}… serviceUuid=${serviceParcelUuid.uuid}")
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
                Log.d(TAG, "scan: discovered peer $address")
                discovered.tryEmit(PeerEndpoint(Transport.Kind.BluetoothLe, address))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan: onScanFailed code=$errorCode")
            }
        }
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "advertise: onStartSuccess settings=$settingsInEffect")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise: onStartFailure code=$errorCode")
            }
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
        Log.d(TAG, "start: advertising + scanning started")

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
        // Per-device flow-control signal for the notify drainer.
        // Android's BluetoothGattServer.notifyCharacteristicChanged
        // silently drops chunks if you fire them faster than the
        // internal queue drains — every chunk past the first 1-3
        // disappears. The fix is to wait for `onNotificationSent`
        // between chunks. Each device gets its own 1-slot Channel;
        // the drainer awaits a receive after every notify; the
        // callback trySends. RENDEZVOUS would deadlock if the
        // callback fires before the drainer is back at receive, so
        // capacity=1 with DROP_OLDEST gives us a "latest signal
        // wins" semantics that's robust to harmless races.
        val notifyAcks = ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<Unit>>()

        // Forward reference to the GATT characteristic this server
        // will host — needed inside the connection-state callback to
        // wire the outbound notify drainer, but the characteristic
        // itself can't be constructed until after the callback is
        // built (the server reference is passed in to addService).
        // Set after `openGattServer` returns; safe because no peer
        // can connect before the surrounding `start()` finishes
        // advertising.
        val registeredCharRef = AtomicReference<BluetoothGattCharacteristic?>(null)

        // Create-or-get the Link for a peer. Idempotent via
        // computeIfAbsent — called from BOTH onConnectionStateChange
        // (so the accept side of `firstAvailableLink` can win as
        // soon as the GATT connection is up) and from
        // onCharacteristicWriteRequest (defensive: covers the rare
        // case where the first write race-arrives before the
        // CONNECTED callback fires).
        //
        // Previously the Link was only created on first write, which
        // meant the accept-side flow only emitted AFTER the peer had
        // already sent a payload — far too late for sync rounds
        // where both peers race to dial and the accept signal needs
        // to win the `select` before our own dial returns.
        fun createOrGetLink(device: BluetoothDevice): BleLink? {
            val char = registeredCharRef.get() ?: return null
            return acceptedLinks.computeIfAbsent(device.address) { _ ->
                val endpoint = PeerEndpoint(Transport.Kind.BluetoothLe, device.address)
                val (newLink, sink) = newBleLink(endpoint = endpoint, onClose = {
                    outboundSinks.remove(device.address)?.close()
                    notifyAcks.remove(device.address)?.close()
                })
                outboundSinks[device.address] = sink
                val notifyAck = kotlinx.coroutines.channels.Channel<Unit>(
                    capacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                notifyAcks[device.address] = notifyAck
                // The drainer feeds raw BLE chunks through the
                // assembler in arrival order on a single coroutine
                // — see BleLink kdoc. Must be started BEFORE any
                // ingestInbound call or the first chunk queues with
                // no consumer.
                newLink.startInboundDrainer(ioScope)
                ioScope.launch {
                    for (frame in sink) {
                        val chunker = BleOutboundChunker(mtuPayload = DEFAULT_MTU_PAYLOAD)
                        for (chunk in chunker.chunk(frame)) {
                            // Drain any stale signal so receive() below
                            // actually waits for THIS write's ack.
                            notifyAck.tryReceive()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching {
                                    this@BleTransport.session?.gattServer?.notifyCharacteristicChanged(device, char, false, chunk)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                char.value = chunk
                                @Suppress("DEPRECATION")
                                runCatching {
                                    this@BleTransport.session?.gattServer?.notifyCharacteristicChanged(device, char, false)
                                }
                            }
                            // Wait for onNotificationSent — Android's BLE
                            // stack only queues a few notifies at a time;
                            // firing the next before the previous is
                            // confirmed silently drops it. The timeout is
                            // a backstop against a stack that decides
                            // never to deliver the callback for some
                            // reason — we'd rather a slow link than a
                            // wedged one. runCatching catches
                            // ClosedReceiveChannelException for the link-
                            // teardown race: an engine error closes the
                            // link, which closes notifyAck, and the
                            // already-suspended receive() throws. That
                            // exception used to crash the worker thread
                            // (see run 21 A02s FATAL).
                            runCatching {
                                withTimeoutOrNull(NOTIFY_ACK_TIMEOUT_MS) { notifyAck.receive() }
                            }
                        }
                    }
                }
                ioScope.launch { runCatching { accepted.send(newLink) } }
                newLink
            }
        }

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val addr = device.address ?: return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Materialise the Link immediately on connect
                        // so `acceptedLinks` can fire before the peer
                        // sends any data. Idempotent — if the dialer
                        // somehow gets a write in first, the
                        // characteristic-write path is a no-op.
                        createOrGetLink(device)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val link = acceptedLinks.remove(addr)
                        outboundSinks.remove(addr)?.close()
                        link?.let { ioScope.launch { runCatching { it.close() } } }
                    }
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
                // Defensive fallback: if the CONNECTED callback hasn't
                // fired yet (rare on Android binder ordering), the
                // first write is sufficient to create the Link.
                val link = createOrGetLink(device) ?: return
                // ingestInbound is non-suspending — safe from the BLE
                // binder thread without an extra dispatcher hop.
                link.ingestInbound(value)
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

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                // Flow-control signal for the per-device notify drainer.
                // Fires after each `notifyCharacteristicChanged` is
                // accepted by the local BLE stack — the drainer awaits
                // this before sending the next chunk so we don't
                // overflow the kernel's internal notify queue.
                notifyAcks[device.address]?.trySend(Unit)
            }
        }

        val server = manager.openGattServer(context, callback)
            ?: error("openGattServer returned null — BLE not supported")
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // PROPERTY_WRITE_NO_RESPONSE is required because the client side
        // uses WRITE_TYPE_NO_RESPONSE for its outbound chunks (see the
        // client drainer further below). Android 13+ rejects a no-response
        // write against a characteristic that only advertises
        // write-with-response, with the symptom that every chunk after the
        // first is silently dropped and the handshake stalls in
        // ExchangingCertificates.
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
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
        // Publish the characteristic to the callback's createOrGetLink
        // helper. Must happen before the surrounding `start()` begins
        // advertising — otherwise an early CONNECTED callback would
        // see a null ref and skip the Link.
        registeredCharRef.set(characteristic)
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
        // Initialised to null because the BleLink.onClose lambda below
        // captures `gatt` before `device.connectGatt(...)` returns.
        var gatt: BluetoothGatt? = null
        val outboundSink = kotlinx.coroutines.channels.Channel<ByteArray>(
            capacity = 32,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        // Build the BleLink eagerly so onCharacteristicChanged (which
        // can fire any time after the peer receives our CCCD write —
        // including BEFORE our local onDescriptorWrite callback lands
        // on Samsung devices that batch GATT events aggressively) has
        // a non-null target. ingestInbound buffers raw chunks on the
        // link itself; the drainer is started below in
        // onDescriptorWrite, after which any pre-buffered chunks
        // flow through.
        val link = BleLink(
            endpoint = endpoint,
            outboundSink = outboundSink,
            onClose = { runCatching { gatt?.disconnect(); gatt?.close() } },
        )
        // Tracks whether the continuation has already been resolved.
        // We must resume exactly once across the disconnect-vs-success
        // race; AtomicBoolean.compareAndSet is the gate.
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        // Volatile-equivalent via AtomicInteger — @Volatile only applies to
        // class fields, not coroutine-captured locals.
        val clientMtu = java.util.concurrent.atomic.AtomicInteger(23)
        // Flow-control signal for the client-side write drainer. Same
        // rationale as the server's notifyAck (see openGattServer):
        // BluetoothGatt.writeCharacteristic with WRITE_TYPE_NO_RESPONSE
        // can only have a small number of writes outstanding (typically
        // 1-3). If the drainer fires chunks faster than the stack drains
        // them, the excess get silently dropped. The 13KB Push frame
        // that surfaced this in run-20 fragmented to ~27 chunks; only
        // the first few reached the peer. Wait for onCharacteristicWrite
        // between chunks to throttle to the stack's actual capacity.
        val writeAck = kotlinx.coroutines.channels.Channel<Unit>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        fun resumeWith(action: () -> Unit) {
            if (resolved.compareAndSet(false, true) && cont.isActive) action()
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // requestMtu can synchronously return false on some
                    // Samsung devices when invoked before encryption
                    // settles. If that happens we skip MTU negotiation
                    // and proceed at the BLE 4.0 floor of 23 bytes —
                    // chunking still works, just at minimum throughput.
                    val accepted = runCatching { g.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
                    if (!accepted) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    // Disconnect before the link was ready: resume the
                    // suspending connect() with an exception so the
                    // caller surfaces TransportFailed instead of
                    // hanging forever waiting on a callback that will
                    // never arrive.
                    resumeWith {
                        cont.resumeWithException(
                            IllegalStateException("peer disconnected before link ready (status=$status)")
                        )
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) clientMtu.set(mtu)
                // Always advance — a failed MTU negotiation leaves us at
                // the default 23 but service discovery and subsequent
                // chunking still work.
                g.discoverServices()
            }

            // Tracks the characteristic resolved in onServicesDiscovered so
            // onDescriptorWrite can finish wiring up the link AFTER the
            // CCCD subscription has been ACKed by the peer.
            @Volatile var pendingChar: BluetoothGattCharacteristic? = null

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(serviceUuid)
                if (service == null) {
                    resumeWith {
                        cont.resumeWithException(
                            IllegalStateException("service $serviceUuid not advertised by peer")
                        )
                    }
                    return
                }
                val ch = service.getCharacteristic(characteristicUuid)
                if (ch == null) {
                    resumeWith {
                        cont.resumeWithException(
                            IllegalStateException("char $characteristicUuid missing on peer")
                        )
                    }
                    return
                }
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    resumeWith {
                        cont.resumeWithException(
                            IllegalStateException("CCCD missing on peer characteristic")
                        )
                    }
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
                    resumeWith {
                        cont.resumeWithException(
                            IllegalStateException("CCCD write failed with status $status")
                        )
                    }
                    return
                }
                // Now that the peer has acked subscription, drain any
                // pre-buffered chunks through the assembler in order.
                link.startInboundDrainer(ioScope)
                ioScope.launch {
                    val chunker = BleOutboundChunker(
                        mtuPayload = (clientMtu.get() - 3)
                            .coerceAtLeast(MIN_MTU_PAYLOAD)
                            .coerceAtMost(MAX_MTU_PAYLOAD),
                    )
                    for (frame in outboundSink) {
                        for (chunk in chunker.chunk(frame)) {
                            writeAck.tryReceive()
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
                            // See `writeAck` kdoc — wait for the local
                            // stack's onCharacteristicWrite callback so
                            // we never have more than one outstanding
                            // write per BLE peer. Timeout protects
                            // against a stack that drops the callback
                            // (rare but observed on Samsung firmware).
                            // runCatching catches the link-teardown race
                            // where the engine errors, closes the link,
                            // closes writeAck, and the suspended receive
                            // throws ClosedReceiveChannelException.
                            runCatching {
                                withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { writeAck.receive() }
                            }
                        }
                    }
                }
                resumeWith { cont.resume(link) }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                // ingestInbound is non-suspending and the link is
                // pre-allocated above, so it's safe to call from the
                // binder thread without a launch hop. Chunks queue on
                // the link's rawChunks Channel until the drainer is
                // started in onDescriptorWrite.
                link.ingestInbound(value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // Flow-control signal for the outbound drainer — fires
                // once the local stack has accepted the write into its
                // internal queue. See the `writeAck` kdoc above.
                writeAck.trySend(Unit)
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
        private const val TAG = "BleTransport"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 247
        // 3-byte ATT header off the negotiated MTU; default of 20 is the
        // BLE 4.0 minimum until MTU negotiation succeeds.
        private const val DEFAULT_MTU_PAYLOAD = 244
        private const val MIN_MTU_PAYLOAD = 20
        // Android caps writeCharacteristic value at GATT_MAX_ATTR_LEN
        // (512). Even when ATT MTU negotiates to 517 (Samsung stack),
        // the actual write payload must not exceed 512 or the BLE
        // stack throws IllegalArgumentException.
        private const val MAX_MTU_PAYLOAD = 512
        // Per-chunk wait for the BLE stack's local ack callback
        // (onCharacteristicWrite on the client, onNotificationSent
        // on the server). 1500ms is conservative — typical ack is
        // sub-100ms — but it's a backstop, not the normal path. The
        // drainer almost always wakes within milliseconds.
        private const val WRITE_ACK_TIMEOUT_MS: Long = 1500
        private const val NOTIFY_ACK_TIMEOUT_MS: Long = 1500
    }
}
