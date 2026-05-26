package com.wyspr.core.transport.bluetooth

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
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.ServiceUuid
import com.wyspr.core.transport.Transport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

        // Dedup advertisements so a single nearby peer doesn't flood
        // the discovered SharedFlow buffer with ~10 emissions/sec.
        // Pre-fix, ALL_MATCHES mode emitted on every advert; the
        // buffer of 64 saturated within seconds of a single peer
        // showing up.
        val seenAddresses = java.util.Collections.newSetFromMap(
            ConcurrentHashMap<String, Boolean>(),
        )
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // MATCH_LOST is the only callback type that means
                // "previously seen, now gone." We don't act on it but
                // we do want to forget the address so a re-arrival
                // emits a fresh discovery.
                if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                    val addr = result.device?.address
                    if (addr != null) seenAddresses.remove(addr)
                    return
                }
                val device = result.device ?: return
                val address = device.address ?: return
                if (!seenAddresses.add(address)) return  // already emitted
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
        // Close all live BleLinks in a NonCancellable block so the
        // dying scope can't tear down GATT references mid-shutdown.
        // Previously the close() launches were children of the
        // ioScope and got cancelled before they actually ran the
        // suspending close() — leaving BluetoothGatt objects behind
        // and surfacing as "stale GATT" failures on the next start().
        withContext(NonCancellable) {
            current.acceptedLinks.values.forEach { link ->
                runCatching { link.close() }
            }
        }
        current.acceptedLinks.clear()
        // Cancel the scope last; the suspending closes above have
        // already finished by now.
        current.ioScope.coroutineContext[Job]?.cancel()
    }

    override fun discovered(): Flow<PeerEndpoint> =
        session?.discovered?.asSharedFlow() ?: emptyFlow()

    override fun acceptedLinks(): Flow<Link> =
        session?.accepted?.receiveAsFlow() ?: emptyFlow()

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
        // disappears. We pair each notify with one `onNotificationSent`
        // callback using a counting Semaphore: the drainer acquires
        // before each send (waiting if MAX_OUTSTANDING are in flight),
        // the callback releases. A counter is robust to back-to-back
        // callbacks arriving faster than the drainer can dispatch — a
        // bounded Channel<Unit> with DROP_OLDEST lost the second of
        // two fast acks, silently stalling the next chunk by the full
        // ack timeout.
        val notifyAcks = ConcurrentHashMap<String, Semaphore>()
        // Per-device negotiated MTU. Updated from `onMtuChanged` on the
        // server callback (the server has its own dispatch path — the
        // peripheral negotiates the same MTU but Android won't tell us
        // about it unless we listen). Until the callback lands, fall
        // back to the BLE 4.0 floor so we never overshoot a peer's
        // capacity. Pre-MIGRATION the chunker used DEFAULT_MTU_PAYLOAD
        // (244) regardless — peers stuck at min MTU silently truncated
        // every chunk past byte 20.
        val negotiatedMtuPayload = ConcurrentHashMap<String, Int>()

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
                    notifyAcks.remove(device.address)
                    negotiatedMtuPayload.remove(device.address)
                })
                outboundSinks[device.address] = sink
                // Counting semaphore — starts with 0 permits available
                // (the drainer can't acquire until the stack first
                // releases one via `onNotificationSent`). MAX_OUTSTANDING
                // bounds the work-in-flight; we go with 1 to match the
                // narrowest Android BLE peripheral queue depth, which is
                // the cause of the original silent drops.
                val notifyAck = Semaphore(permits = MAX_OUTSTANDING_WRITES, acquiredPermits = MAX_OUTSTANDING_WRITES)
                notifyAcks[device.address] = notifyAck
                // The drainer feeds raw BLE chunks through the
                // assembler in arrival order on a single coroutine
                // — see BleLink kdoc. Must be started BEFORE any
                // ingestInbound call or the first chunk queues with
                // no consumer.
                newLink.startInboundDrainer(ioScope)
                // Capture the GATT server eagerly at link creation
                // time. If stop() nulls session later, the drainer
                // still holds a valid reference and won't silently
                // drop frames.
                val capturedServer = this@BleTransport.session?.gattServer
                ioScope.launch {
                    for (frame in sink) {
                        // Late-binding chunker: read the current
                        // negotiated MTU on every outbound frame so an
                        // MTU upgrade mid-session expands the chunk
                        // size, and so a peer that never negotiated up
                        // stays at the floor instead of silently
                        // truncating at byte 20.
                        val mtuPayload = negotiatedMtuPayload[device.address]
                            ?: DEFAULT_MTU_PAYLOAD
                        val chunker = BleOutboundChunker(mtuPayload = mtuPayload)
                        for (chunk in chunker.chunk(frame)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching {
                                    capturedServer?.notifyCharacteristicChanged(device, char, false, chunk)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                char.value = chunk
                                @Suppress("DEPRECATION")
                                runCatching {
                                    capturedServer?.notifyCharacteristicChanged(device, char, false)
                                }
                            }
                            // Wait for onNotificationSent — Android's BLE
                            // stack only queues a few notifies at a time;
                            // firing the next before the previous is
                            // confirmed silently drops it. Semaphore
                            // counts pending writes so back-to-back fast
                            // callbacks aren't lost the way they were
                            // with a 1-slot DROP_OLDEST Channel. Timeout
                            // is a backstop against a stack that drops
                            // the callback (rare but observed on Samsung
                            // firmware).
                            val gotAck = withTimeoutOrNull(NOTIFY_ACK_TIMEOUT_MS) {
                                notifyAck.acquire()
                                true
                            } ?: false
                            if (!gotAck) {
                                Log.w(TAG, "notifyAck timeout after ${NOTIFY_ACK_TIMEOUT_MS}ms; continuing")
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
                runCatching { notifyAcks[device.address]?.release() }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                val payload = (mtu - 3)
                    .coerceAtLeast(MIN_MTU_PAYLOAD)
                    .coerceAtMost(MAX_MTU_PAYLOAD)
                negotiatedMtuPayload[device.address] = payload
                Log.d(TAG, "server.onMtuChanged: dev=${device.address} mtu=$mtu payload=$payload")
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
        // Guard against double-`close()` on `BluetoothGatt`. Some Samsung
        // builds throw IllegalStateException on the second close; the
        // disconnect-callback path and the explicit link.close() path
        // can both race here.
        val gattClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun closeGattOnce() {
            if (gattClosed.compareAndSet(false, true)) {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
        val outboundSink = kotlinx.coroutines.channels.Channel<ByteArray>(
            capacity = 32,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        // The drainer Job is held on the BleLink so its lifetime tracks
        // the Link, not the transport session. Cancelling stop() mid-
        // round used to interrupt a write because the drainer was
        // launched as a child of session.ioScope.
        val drainerJobRef = java.util.concurrent.atomic.AtomicReference<Job?>(null)
        val link = BleLink(
            endpoint = endpoint,
            outboundSink = outboundSink,
            onClose = {
                runCatching { drainerJobRef.get()?.cancel() }
                closeGattOnce()
            },
        )
        // Tracks whether the continuation has already been resolved.
        // We must resume exactly once across the disconnect-vs-success
        // race; AtomicBoolean.compareAndSet is the gate.
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        // Volatile-equivalent via AtomicInteger — @Volatile only applies to
        // class fields, not coroutine-captured locals.
        val clientMtu = java.util.concurrent.atomic.AtomicInteger(23)
        // Counting semaphore for write flow control — see openGattServer
        // for the same pattern. Pre-MIGRATION used a 1-slot Channel with
        // DROP_OLDEST which silently lost the second of two fast acks.
        val writeAck = Semaphore(permits = MAX_OUTSTANDING_WRITES, acquiredPermits = MAX_OUTSTANDING_WRITES)

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
                    closeGattOnce()
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
                val drainerJob = ioScope.launch {
                    for (frame in outboundSink) {
                        // Read the current MTU per-frame so a late MTU
                        // change (rare but possible) is picked up instead
                        // of using a stale snapshot from link creation time.
                        val chunker = BleOutboundChunker(
                            mtuPayload = (clientMtu.get() - 3)
                                .coerceAtLeast(MIN_MTU_PAYLOAD)
                                .coerceAtMost(MAX_MTU_PAYLOAD),
                        )
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
                            // See `writeAck` kdoc — wait for the local
                            // stack's onCharacteristicWrite callback so
                            // we don't exceed MAX_OUTSTANDING_WRITES per
                            // peer. Counting semaphore is robust to
                            // back-to-back fast callbacks the way the
                            // old 1-slot DROP_OLDEST Channel was not.
                            val gotAck = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                                writeAck.acquire()
                                true
                            } ?: false
                            if (!gotAck) {
                                Log.w(TAG, "writeAck timeout after ${WRITE_ACK_TIMEOUT_MS}ms; continuing")
                            }
                        }
                    }
                }
                drainerJobRef.set(drainerJob)
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
                runCatching { writeAck.release() }
            }

            @Deprecated("kept for pre-T platforms that haven't upgraded to the value-overload")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                onCharacteristicChanged(g, ch, ch.value ?: ByteArray(0))
            }
        }

        // TRANSPORT_LE forces the BLE radio path. TRANSPORT_AUTO (the
        // default) will try BR/EDR first on dual-mode devices, which
        // typically adds 1-3 s of latency and occasionally fails the
        // connect entirely.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, /* autoConnect = */ false, callback)
        }
        cont.invokeOnCancellation { closeGattOnce() }
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
        // Cap on in-flight BLE writes/notifies per peer. Android's BLE
        // stack queues 1-3 internally before silently dropping; 1 is
        // the narrowest and safest setting and matches what the
        // previous 1-slot DROP_OLDEST Channel effectively allowed.
        private const val MAX_OUTSTANDING_WRITES: Int = 1
    }
}
