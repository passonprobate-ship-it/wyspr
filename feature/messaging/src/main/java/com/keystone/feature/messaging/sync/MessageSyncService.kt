package com.keystone.feature.messaging.sync

import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.crypto.NoiseSession
import com.keystone.core.crypto.NoiseSessionImpl
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.Link
import com.keystone.core.transport.MessagingNotifier
import com.keystone.core.transport.SyncTransportFacade
import com.keystone.core.transport.TransportLifecycle
import com.keystone.core.trust.TrustGraphService
import com.keystone.core.trust.runRevocationSyncRound
import com.keystone.feature.messaging.MessageStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot peer message sync. User taps "Sync now" on the
 * conversation list; both devices race to reach each other on every
 * available transport, run Noise XX with channel binding against the
 * local TrustEdges, then invoke [MessageSyncEngine] to push pending +
 * ingest inbound.
 *
 * Sprint 2 MVP: manual trigger, single attempt, 30s budget. Sprint
 * 5 (Tor end-to-end) widens the race to add Tor circuits alongside
 * BLE — a peer with a known `.onion` is reachable across the
 * internet, not just in the same room.
 *
 * ## Race semantics
 *
 * Both sides call [runOnce]. Each side calls [SyncTransportFacade.startAll],
 * then races three paths:
 *   - BLE discover → connect (Initiator role)
 *   - Tor dial of any known peer `.onion` (Initiator role)
 *   - merged accept flow from BLE + Tor (Responder role)
 *
 * Whichever path produces a Link first wins; the losers are
 * cancelled. The Tor branch returns null and stays parked (via
 * [awaitCancellation]) when Tor isn't bootstrapped or no peer
 * `.onion` is known — so it doesn't block the BLE-only first-pairing
 * case.
 *
 * After Noise reaches transport, the peer's static key (X25519) is
 * checked against the X25519 derivations of every known TrustEdge
 * endpoint. A non-match closes the link unprocessed — we do not
 * negotiate with strangers, even strangers on the same community.
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
    private val transports: SyncTransportFacade,
    private val store: MessageStore,
    private val transportLifecycle: TransportLifecycle,
    private val notifier: MessagingNotifier,
    private val trustGraphService: TrustGraphService,
) {

    private val lock = Mutex()

    data class Result(
        val attemptedPeers: Int,
        val pushedMessages: Int,
        val receivedMessages: Int,
        val revocationsReceived: Int = 0,
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
            ?: return Result(0, 0, 0, errorReason = "Could not load local identity")
        val ownPub = PublicKey(ownIdentity.publicKey)

        val membership = database.communityMembershipDao.firstOrNull()
            ?: return Result(0, 0, 0, errorReason = "No active community on this device")
        val communityId = CommunityId(membership.communityId)

        // Build the X25519 lookup table once — the channel-binding
        // step compares the peer's noise static against these.
        val peerLookup = buildPeerLookup(ownPub)
        if (peerLookup.isEmpty()) {
            return Result(0, 0, 0, errorReason = "No paired peers — complete a handshake first")
        }

        transportLifecycle.acquireForSharing()
        try {
            transports.startAll(communityId)
            val outcome = withTimeoutOrNull(timeoutMs) { firstAvailableLink(ownPub.bytes) }
                ?: return Result(0, 0, 0, errorReason = "No peer in range")
            val link = outcome.link
            val role = outcome.role
            try {
                val sessionResult = openSessionAndSync(
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
                if (sessionResult.engineResult.receivedCount > 0) {
                    postInboundNotification(sessionResult.peerPub)
                }
                // Piggyback a revocation anti-entropy round on the
                // same Noise transport. Both peers are already
                // mutually authenticated; revocations they hold get
                // exchanged before the link closes.
                //
                // Failures here are non-fatal — the messaging
                // exchange already succeeded and the user has already
                // seen their messages. We just don't carry the
                // revocation count forward. CancellationException is
                // rethrown so the parent scope's cancellation
                // contract is preserved.
                val revocationsReceived = try {
                    val graph = trustGraphService.snapshot()
                    runRevocationSyncRound(
                        link = link,
                        database = database,
                        communityId = communityId.bytes,
                        trustGraph = graph,
                        sodium = sodium,
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "MessageSyncService",
                        "Revocation round failed after messaging succeeded: " +
                            "${t.javaClass.simpleName}",
                    )
                    0
                }
                return Result(
                    attemptedPeers = 1,
                    pushedMessages = sessionResult.engineResult.pushedCount,
                    receivedMessages = sessionResult.engineResult.receivedCount,
                    revocationsReceived = revocationsReceived,
                )
            } finally {
                runCatching { link.close() }
            }
        } finally {
            runCatching { transports.stopAll() }
            transportLifecycle.release()
        }
    }

    private data class LinkOutcome(val link: Link, val role: MessageSyncEngine.HandshakeRole)

    /**
     * Race three paths to a Link: BLE discover+connect, Tor dial of
     * any known peer `.onion`, and the merged accept flow from both
     * transports. The first to produce a Link wins; losers are
     * cancelled. The Tor dial path returns null and parks on
     * [awaitCancellation] when Tor isn't viable, so the BLE-only
     * first-pairing case isn't slowed down.
     */
    private suspend fun firstAvailableLink(ownPubBytes: ByteArray): LinkOutcome = coroutineScope {
        val bleConnectDeferred = async {
            val link = transports.bleDiscoverAndConnect()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Initiator)
        }
        val torDialDeferred = async {
            val link = transports.dialFirstKnownOnion(ownPubBytes)
                ?: awaitCancellation()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Initiator)
        }
        val acceptDeferred = async {
            val link = transports.acceptedLinks().first()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Responder)
        }
        val outcome = kotlinx.coroutines.selects.select<LinkOutcome> {
            bleConnectDeferred.onAwait { it }
            torDialDeferred.onAwait { it }
            acceptDeferred.onAwait { it }
        }
        // Three branches, one winner. Each loser is in one of two
        // states: still suspended (cancel it) or already completed
        // in the same tick as the winner (close its dangling Link
        // — cancel() after completion doesn't reclaim the resource).
        for (d in listOf(bleConnectDeferred, torDialDeferred, acceptDeferred)) {
            if (d.isCompleted) {
                val loser = runCatching { d.getCompleted() }.getOrNull() ?: continue
                if (loser.link !== outcome.link) {
                    launch { runCatching { loser.link.close() } }
                }
            } else {
                d.cancel()
            }
        }
        outcome
    }

    private data class SessionResult(
        val peerPub: PublicKey,
        val engineResult: MessageSyncEngine.Result,
    )

    private suspend fun openSessionAndSync(
        role: MessageSyncEngine.HandshakeRole,
        link: Link,
        communityId: CommunityId,
        ownPub: PublicKey,
        peerLookup: Map<List<Byte>, PublicKey>,
    ): SessionResult? {
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
            return SessionResult(peerPub = peerPub, engineResult = engine.run())
        } finally {
            noise.close()
        }
    }

    /**
     * Fire a notification announcing freshly-ingested inbound from
     * [peerPub]. Queries the message store for the latest message
     * to populate the preview. Best-effort — a DB hiccup here
     * shouldn't fail the sync round.
     */
    private suspend fun postInboundNotification(peerPub: PublicKey) {
        runCatching {
            val recent = store.latestFromPeer(peerPub) ?: return@runCatching
            val unread = store.unreadInboundFrom(peerPub)
            notifier.notifyInbound(
                peerPub = peerPub.bytes,
                senderFingerprint = peerPub.fingerprint.toString(),
                count = unread.coerceAtLeast(1),
                preview = recent.body.take(NOTIFICATION_PREVIEW_CHARS),
            )
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
        const val NOTIFICATION_PREVIEW_CHARS = 120
        private val PROLOGUE_PREFIX = "KEYSTONE/v1/sync".encodeToByteArray()
    }
}
