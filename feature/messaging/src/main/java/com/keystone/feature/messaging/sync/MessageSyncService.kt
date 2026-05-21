package com.keystone.feature.messaging.sync

import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

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
    private val groupStore: com.keystone.feature.messaging.groups.GroupStore,
    private val transportLifecycle: TransportLifecycle,
    private val notifier: MessagingNotifier,
    private val trustGraphService: TrustGraphService,
) {

    private val lock = Mutex()

    /**
     * Class-level scope for the race deferreds in [firstAvailableLink].
     *
     * Critical that this is NOT a child of the caller's job. Earlier
     * iterations created the SupervisorJob inside `firstAvailableLink`
     * with `currentCoroutineContext()[Job]` as parent, which made it
     * a child of the surrounding `withTimeoutOrNull` body. When the
     * body returned its [LinkOutcome], structured-concurrency rules
     * still kept `withTimeoutOrNull` blocked waiting on the
     * SupervisorJob to complete — which it couldn't, because two
     * losing deferreds were stuck in non-interruptible I/O (the Tor
     * SOCKS5 dial in particular). The 12s timeout would then fire
     * EVEN THOUGH THE OUTCOME WAS ALREADY ASSIGNED, and the round
     * reported "timed out waiting for a link" despite the select
     * having succeeded ~10s earlier.
     *
     * Detaching the scope from the caller lets `firstAvailableLink`
     * return immediately after the select; losing deferreds drain in
     * the background on the class scope. We cancel them via
     * `Deferred.cancel()` in a `finally` so they exit ASAP, but
     * their actual exit isn't on the round's critical path.
     */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        Log.d(TAG_SYNC, "runOnce: start (timeoutMs=$timeoutMs)")
        if (!database.isOpen) database.open()
        val ownIdentity = runCatching { keystore.loadOrCreateIdentityKey() }.getOrNull()
            ?: run {
                Log.w(TAG_SYNC, "runOnce: could not load local identity")
                return Result(0, 0, 0, errorReason = "Could not load local identity")
            }
        val ownPub = PublicKey(ownIdentity.publicKey)

        val membership = database.communityMembershipDao.firstOrNull()
            ?: run {
                Log.w(TAG_SYNC, "runOnce: no community membership")
                return Result(0, 0, 0, errorReason = "No active community on this device")
            }
        val communityId = CommunityId(membership.communityId)

        // Build the X25519 lookup table once — the channel-binding
        // step compares the peer's noise static against these.
        val peerLookup = buildPeerLookup(ownPub)
        if (peerLookup.isEmpty()) {
            Log.w(TAG_SYNC, "runOnce: no paired peers")
            return Result(0, 0, 0, errorReason = "No paired peers — complete a handshake first")
        }
        Log.d(TAG_SYNC, "runOnce: ${peerLookup.size} paired peer(s), community=${communityId.bytes.take(4).joinToString("") { "%02x".format(it) }}…, starting transports")

        // Pick a role bias from the lexicographic compare of own pub
        // vs. the first paired peer's pub. Both phones reach the same
        // ordering (compare is total), so they reach opposite biases.
        // This breaks the symmetric race: smaller-pub side dials,
        // larger-pub side accepts. With multiple peers we still pick
        // a single bias for the round — the race only matters when
        // both sides happen to fire simultaneously, which is the
        // first-paired-peer case in practice. Fallbacks still cover
        // asymmetric multi-peer rounds.
        val anyPeerPub = peerLookup.values.first().bytes
        val preferInitiator = compareLex(ownPub.bytes, anyPeerPub) < 0
        Log.d(TAG_SYNC, "runOnce: preferInitiator=$preferInitiator")

        transportLifecycle.acquireForSharing()
        try {
            transports.startAll(communityId)
            Log.d(TAG_SYNC, "runOnce: transports started, waiting for first link (timeout ${timeoutMs}ms)")
            val outcome = withTimeoutOrNull(timeoutMs) { firstAvailableLink(ownPub.bytes, preferInitiator) }
                ?: run {
                    Log.w(TAG_SYNC, "runOnce: timed out waiting for a link")
                    return Result(0, 0, 0, errorReason = "No peer in range")
                }
            val link = outcome.link
            val role = outcome.role
            Log.d(TAG_SYNC, "runOnce: got link via $role")
            try {
                // Wrap the Noise XX handshake + message exchange in
                // a timeout. Without this, a half-dead Link (e.g. a
                // Tor circuit that completed on the responder side
                // but failed on the initiator side, leaving the
                // responder reading m1 from an initiator that won't
                // send) would hang here forever — holding the round
                // mutex and blocking every subsequent runOnce call.
                // The auto-sync loop then goes silent. The timeout
                // releases the lock and lets the next round retry.
                //
                // Outer null = timed out; inner null = channel
                // binding rejected the peer. Distinguish them so the
                // user-facing error is accurate.
                val sessionOutcome: SessionOutcome = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                    val r = openSessionAndSync(
                        role = role,
                        link = link,
                        communityId = communityId,
                        ownPub = ownPub,
                        peerLookup = peerLookup,
                    )
                    if (r == null) SessionOutcome.NotRecognised else SessionOutcome.Ok(r)
                } ?: SessionOutcome.TimedOut
                val sessionResult = when (sessionOutcome) {
                    is SessionOutcome.Ok -> sessionOutcome.result
                    SessionOutcome.NotRecognised -> {
                        Log.w(TAG_SYNC, "runOnce: peer not recognised (channel-binding failed)")
                        return Result(
                            attemptedPeers = 1,
                            pushedMessages = 0,
                            receivedMessages = 0,
                            errorReason = "Peer not recognised (channel-binding failed)",
                        )
                    }
                    SessionOutcome.TimedOut -> {
                        Log.w(TAG_SYNC, "runOnce: session timed out (${SESSION_TIMEOUT_MS}ms — peer didn't complete Noise XX)")
                        return Result(
                            attemptedPeers = 1,
                            pushedMessages = 0,
                            receivedMessages = 0,
                            errorReason = "Peer didn't respond in time",
                        )
                    }
                }
                Log.d(TAG_SYNC, "runOnce: session opened, pushed=${sessionResult.engineResult.pushedCount} received=${sessionResult.engineResult.receivedCount}")
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
    private suspend fun firstAvailableLink(
        ownPubBytes: ByteArray,
        preferInitiator: Boolean,
    ): LinkOutcome {
        // HARD role bias derived from pubkey compare. We tried a soft
        // bias (delay the disfavoured branch) but that didn't work
        // against the realities of BLE + Tor:
        //
        //   - The accept channel can have stale buffered links from
        //     prior rounds (Tor listener stays alive across rounds).
        //     A soft delay just shifts WHEN the accept consumes the
        //     stale link, not whether it does.
        //   - BLE scan + discover + dial takes 5-10s before the dial
        //     branch can win. Any 2-3s accept delay loses to even
        //     a moderately stale inbound.
        //
        // Hard bias: smaller-pub side runs DIAL ONLY (no accept
        // branch). Larger-pub side runs ACCEPT ONLY (no dial). Both
        // sides agree on roles because compareLex is total, and
        // neither side can accidentally take the wrong role from a
        // stale buffered link.
        //
        // Cost: no automatic fallback if the favoured branch fails.
        // But that's actually a feature — a failed round just times
        // out cleanly and the auto-sync loop retries 8s later. No
        // silent role flip.
        val outcome: LinkOutcome = if (preferInitiator) {
            Log.d(TAG_SYNC, "firstAvailableLink: dial-only mode")
            dialOnly(ownPubBytes)
        } else {
            Log.d(TAG_SYNC, "firstAvailableLink: accept-only mode")
            acceptOnly()
        }
        Log.d(TAG_SYNC, "firstAvailableLink: returning (role=${outcome.role})")
        return outcome
    }

    /**
     * Initiator side: race BLE discover+connect and Tor dial. First
     * to a Link wins; the loser is cancelled. No accept branch — if
     * neither dial succeeds, the round times out cleanly and the
     * auto-sync loop tries again.
     */
    private suspend fun dialOnly(ownPubBytes: ByteArray): LinkOutcome {
        val bleConnectDeferred = raceScope.async {
            delay(Random.nextLong(0, DIAL_JITTER_MS))
            val link = transports.bleDiscoverAndConnect()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Initiator)
        }
        val torDialDeferred = raceScope.async {
            delay(Random.nextLong(0, DIAL_JITTER_MS))
            val link = transports.dialFirstKnownOnion(ownPubBytes)
                ?: awaitCancellation()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Initiator)
        }
        val deferreds = listOf(bleConnectDeferred, torDialDeferred)
        try {
            val outcome = kotlinx.coroutines.selects.select<LinkOutcome> {
                bleConnectDeferred.onAwait { it }
                torDialDeferred.onAwait { it }
            }
            for (d in deferreds) {
                if (d.isCompleted) {
                    val loser = runCatching { d.getCompleted() }.getOrNull() ?: continue
                    if (loser.link !== outcome.link) {
                        raceScope.launch { runCatching { loser.link.close() } }
                    }
                }
            }
            return outcome
        } finally {
            for (d in deferreds) if (!d.isCompleted) d.cancel()
        }
    }

    /**
     * Responder side: subscribe to the accept flow and take the
     * first inbound Link. The peer dialed us — we just answered.
     *
     * Drain stale items from the accept channels BEFORE subscribing.
     * Otherwise we'd pick up Links that completed before this round
     * (Tor inbounds queued by the local Tor daemon while no
     * collector was reading, dial completions that landed past
     * their round's timeout). A stale Link's peer is gone, so the
     * Noise XX read hangs forever.
     */
    private suspend fun acceptOnly(): LinkOutcome {
        transports.drainStaleAccepted()
        Log.d(TAG_SYNC, "accept deferred: subscribing to acceptedLinks")
        val link = transports.acceptedLinks().first()
        Log.d(TAG_SYNC, "accept deferred: first() returned a link")
        return LinkOutcome(link, MessageSyncEngine.HandshakeRole.Responder)
    }

    private data class SessionResult(
        val peerPub: PublicKey,
        val engineResult: MessageSyncEngine.Result,
    )

    /**
     * Tri-state result of the wrapped session call so the caller
     * can tell timeout (whole withTimeoutOrNull body) from channel-
     * binding rejection (inner null) without overloading null.
     */
    private sealed interface SessionOutcome {
        data class Ok(val result: SessionResult) : SessionOutcome
        data object NotRecognised : SessionOutcome
        data object TimedOut : SessionOutcome
    }

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
                    val m1 = noise.writeHandshakeMessage()
                    Log.d(TAG_SYNC, "noise(Initiator): sending m1 (${m1.size} bytes)")
                    link.send(m1)
                    Log.d(TAG_SYNC, "noise(Initiator): m1 sent, waiting for m2")
                    val m2 = link.incoming().first()
                    Log.d(TAG_SYNC, "noise(Initiator): m2 received (${m2.size} bytes)")
                    noise.readHandshakeMessage(m2)
                    val m3 = noise.writeHandshakeMessage()
                    Log.d(TAG_SYNC, "noise(Initiator): sending m3 (${m3.size} bytes)")
                    link.send(m3)
                    Log.d(TAG_SYNC, "noise(Initiator): m3 sent, handshake complete")
                }
                NoiseSession.Role.Responder -> {
                    Log.d(TAG_SYNC, "noise(Responder): waiting for m1")
                    val m1 = link.incoming().first()
                    Log.d(TAG_SYNC, "noise(Responder): m1 received (${m1.size} bytes)")
                    noise.readHandshakeMessage(m1)
                    val m2 = noise.writeHandshakeMessage()
                    Log.d(TAG_SYNC, "noise(Responder): sending m2 (${m2.size} bytes)")
                    link.send(m2)
                    Log.d(TAG_SYNC, "noise(Responder): m2 sent, waiting for m3")
                    val m3 = link.incoming().first()
                    Log.d(TAG_SYNC, "noise(Responder): m3 received (${m3.size} bytes)")
                    noise.readHandshakeMessage(m3)
                    Log.d(TAG_SYNC, "noise(Responder): handshake complete")
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
                groupStore = groupStore,
                sodium = sodium,
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

    /** Unsigned-byte lexicographic compare. */
    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
        /**
         * Budget for the Noise XX handshake + message exchange + revocation
         * round AFTER a Link has been acquired. Caps the time a stuck Link
         * (peer didn't actually respond) can hold the round mutex. The
         * caller's `withTimeoutOrNull(timeoutMs)` only covers Link acquisition.
         */
        const val SESSION_TIMEOUT_MS: Long = 20_000L
        const val NOTIFICATION_PREVIEW_CHARS = 120
        private val PROLOGUE_PREFIX = "KEYSTONE/v1/sync".encodeToByteArray()
        private const val TAG_SYNC = "MessageSync"
        // Upper bound for the randomised dial-jitter delay. 1500ms is
        // enough to give a peer that started ~simultaneously time to
        // see our advert and dial first, without slowing the typical
        // one-peer-passive case noticeably.
        private const val DIAL_JITTER_MS: Long = 1500
    }
}
