package com.keystone.feature.messaging.sync

import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.crypto.NoiseSession
import com.keystone.core.crypto.NoiseSessionImpl
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.TransportLifecycle
import com.keystone.core.transport.bluetooth.BleTransport
import com.keystone.feature.messaging.MessageStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot peer message sync. User taps "Sync now" on the
 * conversation list; both devices race to discover each other over
 * BLE, run Noise XX with channel binding against the local
 * TrustEdges, then invoke [MessageSyncEngine] to push pending +
 * ingest inbound.
 *
 * Sprint 2 MVP: manual trigger, single attempt, 30s budget. Sprint
 * 3 will move this into a periodic background loop driven by the
 * transport foreground service.
 *
 * ## Race semantics
 *
 * Both sides call [runOnce]. Each side starts [BleTransport]; the
 * service then races `discovered().first()` against
 * `acceptedLinks().first()`. Whichever fires first decides our
 * role:
 *   - discovered first → we initiate `connect()`, run Noise as Initiator
 *   - accepted first   → peer connected to us, run Noise as Responder
 *
 * After Noise reaches transport, the peer's static key (X25519) is
 * checked against the X25519 derivations of every known TrustEdge
 * endpoint. A non-match closes the link unprocessed — Sprint 2 does
 * not negotiate with strangers, even strangers on the same community.
 *
 * ## Lifecycle
 *
 * Acquires the foreground service via [TransportLifecycle] so the
 * radio stays alive while the sync runs even if the user
 * backgrounds the app. Releases on every exit path.
 */
@Singleton
class MessageSyncService @Inject constructor(
    private val database: KeystoneDatabase,
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val bleTransport: BleTransport,
    private val store: MessageStore,
    private val transportLifecycle: TransportLifecycle,
) {

    private val lock = Mutex()

    data class Result(
        val attemptedPeers: Int,
        val pushedMessages: Int,
        val receivedMessages: Int,
        val errorReason: String? = null,
    )

    /**
     * Attempt a single sync round. Idempotent under concurrent
     * callers — only one round runs at a time, others suspend.
     */
    suspend fun runOnce(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result = lock.withLock {
        withContext(Dispatchers.IO) { runOnceInternal(timeoutMs) }
    }

    private suspend fun runOnceInternal(timeoutMs: Long): Result {
        if (!database.isOpen) database.open()
        val ownIdentity = runCatching { keystore.loadOrCreateIdentityKey() }.getOrNull()
            ?: return Result(0, 0, 0, "Could not load local identity")
        val ownPub = PublicKey(ownIdentity.publicKey)

        val membership = database.communityMembershipDao.firstOrNull()
            ?: return Result(0, 0, 0, "No active community on this device")
        val communityId = CommunityId(membership.communityId)

        // Build the X25519 lookup table once — the channel-binding
        // step compares the peer's noise static against these.
        val peerLookup = buildPeerLookup(ownPub)
        if (peerLookup.isEmpty()) {
            return Result(0, 0, 0, "No paired peers — complete a handshake first")
        }

        transportLifecycle.acquireForSharing()
        try {
            bleTransport.start(communityId)
            val outcome = withTimeoutOrNull(timeoutMs) { firstAvailableLink() }
                ?: return Result(0, 0, 0, "No peer in range")
            val link = outcome.link
            val role = outcome.role
            try {
                val engineResult = openSessionAndSync(
                    role = role,
                    link = link,
                    communityId = communityId,
                    ownPub = ownPub,
                    peerLookup = peerLookup,
                ) ?: return Result(
                    attemptedPeers = 1,
                    pushedMessages = 0,
                    receivedMessages = 0,
                    errorReason = "Peer not recognised (channel-binding failed)",
                )
                return Result(
                    attemptedPeers = 1,
                    pushedMessages = engineResult.pushedCount,
                    receivedMessages = engineResult.receivedCount,
                )
            } finally {
                runCatching { link.close() }
            }
        } finally {
            runCatching { bleTransport.stop() }
            transportLifecycle.release()
        }
    }

    private data class LinkOutcome(val link: Link, val role: MessageSyncEngine.HandshakeRole)

    /**
     * Race the discovery flow against the accept flow. The first
     * to produce a usable Link wins; we cancel the loser.
     */
    private suspend fun firstAvailableLink(): LinkOutcome = coroutineScope {
        val discoverDeferred = async {
            val endpoint: PeerEndpoint = bleTransport.discovered().first()
            val link = bleTransport.connect(endpoint)
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Initiator)
        }
        val acceptDeferred = async {
            val link = bleTransport.acceptedLinks().first()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Responder)
        }
        // select-like race via try/catch on the loser path.
        val outcome = kotlinx.coroutines.selects.select<LinkOutcome> {
            discoverDeferred.onAwait { it }
            acceptDeferred.onAwait { it }
        }
        if (outcome.role == MessageSyncEngine.HandshakeRole.Initiator) {
            acceptDeferred.cancel()
        } else {
            discoverDeferred.cancel()
        }
        outcome
    }

    private suspend fun openSessionAndSync(
        role: MessageSyncEngine.HandshakeRole,
        link: Link,
        communityId: CommunityId,
        ownPub: PublicKey,
        peerLookup: Map<List<Byte>, PublicKey>,
    ): MessageSyncEngine.Result? {
        val noiseRole = when (role) {
            MessageSyncEngine.HandshakeRole.Initiator -> NoiseSession.Role.Initiator
            MessageSyncEngine.HandshakeRole.Responder -> NoiseSession.Role.Responder
        }
        val prologue = buildSyncPrologue(communityId)
        val noise = NoiseSessionImpl(noiseRole)
        try {
            noise.start(prologue, keystore)
            when (noiseRole) {
                NoiseSession.Role.Initiator -> {
                    link.send(noise.writeHandshakeMessage())
                    val m2 = link.incoming().first()
                    noise.readHandshakeMessage(m2)
                    link.send(noise.writeHandshakeMessage())
                }
                NoiseSession.Role.Responder -> {
                    val m1 = link.incoming().first()
                    noise.readHandshakeMessage(m1)
                    link.send(noise.writeHandshakeMessage())
                    val m3 = link.incoming().first()
                    noise.readHandshakeMessage(m3)
                }
            }
            check(noise.state == NoiseSession.State.Transport) {
                "Noise XX did not reach transport state (got ${noise.state})"
            }
            // Channel binding: who did we actually talk to?
            val peerX25519 = noise.remoteStaticPublicKey
            val peerPub = peerLookup[peerX25519.toList()] ?: return null

            val engine = MessageSyncEngine(
                role = role,
                noise = noise,
                link = link,
                peerPub = peerPub,
                ownPub = ownPub,
                store = store,
            )
            return engine.run()
        } finally {
            noise.close()
        }
    }

    /**
     * Map the X25519 form of every paired peer's identity to the
     * Ed25519 form. Built once per sync attempt; the local Ed25519
     * pub is skipped so we don't accidentally accept ourselves.
     */
    private suspend fun buildPeerLookup(ownPub: PublicKey): Map<List<Byte>, PublicKey> {
        val out = HashMap<List<Byte>, PublicKey>()
        val edges = database.trustEdgeDao.all()
        val seen = HashSet<List<Byte>>()
        for (edge in edges) {
            val candidates = listOf(edge.fromPub, edge.toPub)
            for (pubBytes in candidates) {
                if (pubBytes.contentEquals(ownPub.bytes)) continue
                if (!seen.add(pubBytes.toList())) continue
                val x25519 = ed25519ToX25519(pubBytes) ?: continue
                out[x25519.toList()] = PublicKey(pubBytes)
            }
        }
        return out
    }

    private fun ed25519ToX25519(edPub: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (sodium.convertPublicKeyEd25519ToCurve25519(out, edPub)) out else null
    }

    private fun buildSyncPrologue(communityId: CommunityId): ByteArray =
        PROLOGUE_PREFIX + communityId.bytes

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
        private val PROLOGUE_PREFIX = "KEYSTONE/v1/sync".encodeToByteArray()
    }
}
