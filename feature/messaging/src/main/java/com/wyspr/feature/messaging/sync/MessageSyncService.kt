package com.wyspr.feature.messaging.sync

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.crypto.NoiseSession
import com.wyspr.core.crypto.NoiseSessionImpl
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.MessagingNotifier
import com.wyspr.core.transport.SyncTransportFacade
import com.wyspr.core.transport.Transport
import com.wyspr.core.transport.TransportLifecycle
import com.wyspr.core.trust.TrustGraphService
import com.wyspr.core.trust.runKeyRotationSyncRound
import com.wyspr.core.trust.runRevocationSyncRound
import com.wyspr.feature.messaging.MessageStore
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
    private val database: WysprDatabase,
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val transports: SyncTransportFacade,
    private val store: MessageStore,
    private val groupStore: com.wyspr.feature.messaging.groups.GroupStore,
    private val transportLifecycle: TransportLifecycle,
    private val notifier: MessagingNotifier,
    private val trustGraphService: TrustGraphService,
    private val mailboxBindingService: com.wyspr.feature.messaging.mailbox.MailboxBindingService,
    private val mailboxHost: com.wyspr.feature.messaging.mailbox.MailboxHost,
    private val ownPaymentAddressProvider: com.wyspr.core.transport.OwnPaymentAddressProvider,
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
    @Volatile private var consecutiveFailures = 0

    // === Sprint 1: cached Noise sessions over Tor ==========================
    //
    // Keep the (Link, NoiseSession) pair alive across rounds when the
    // underlying transport is a Tor circuit. Subsequent rounds skip the
    // entire Tor descriptor + SOCKS5 + Noise XX cost (~30s cold start) and
    // just send/receive frames on the cached transport-state session
    // (sub-second on a warm circuit).
    //
    // Why Tor-only: BLE re-discovery + connect is only ~5s and its failure
    // modes are messier (radio off, peer out of range, GATT teardown).
    // Caching it isn't worth the divergent-state risk.
    //
    // Access is serialised by the same `lock` Mutex that serialises rounds,
    // so the cache is implicitly thread-safe — every read and every mutation
    // happens inside `runOnce`'s critical section.
    //
    // Eviction: on-failure only in v1. No TTL, no LRU. A stale entry costs
    // one failed cached round before falling through to fresh dial; the
    // 8-second auto-sync cycle covers any latency this introduces.
    //
    // Symmetry: when both peers cache, both reuse — no protocol-level
    // negotiation required because both peers complete openSessionAndSync
    // on the same end-of-engine boundary. When only one side has cache
    // (e.g. peer restarted), the cached side's send/receive on a half-dead
    // link fails (peer closed the socket, or the peer rejects our
    // mid-stream ciphertext as a malformed Noise XX m1). We catch the
    // failure, evict, and the next round both sides go fresh.
    private data class CachedSession(
        val link: Link,
        val noise: NoiseSession,
        val role: MessageSyncEngine.HandshakeRole,
        val peerPub: PublicKey,
        val acquiredAtMs: Long = System.currentTimeMillis(),
    )

    /** Guarded by [lock]. Keyed by peer Ed25519 pubkey. */
    private val cachedSessions: MutableMap<com.wyspr.core.identity.PeerKey, CachedSession> = HashMap()

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
     *
     * NOTE: the round mutex is currently held through the post-round
     * transport teardown (1-3s on BLE). Lifting teardown outside the
     * mutex is a worthwhile MEDIUM but the refactor is non-trivial
     * (multiple early-return paths plus the transport lifecycle ref
     * count must stay consistent). Deferred to a separate change.
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
            // === Sprint 1 fast path: cached Tor session =====================
            //
            // If any paired peer has a live cached session (must be Tor —
            // BLE links aren't cached), run the engine directly on it and
            // skip the entire transport-startup + Noise XX dance.
            // tryCachedRound returns null on any failure and evicts the
            // stale cache, so we fall through to the fresh path.
            val cached = peerLookup.values.firstNotNullOfOrNull { pp ->
                cachedSessions[com.wyspr.core.identity.PeerKey(pp.bytes)]
            }
            if (cached != null) {
                Log.d(TAG_SYNC, "runOnce: cache hit for peer=${shortHex(cached.peerPub.bytes)}")
                val cachedResult = tryCachedRound(cached, communityId, ownPub)
                if (cachedResult != null) {
                    Log.d(TAG_SYNC, "runOnce: cached round succeeded")
                    return cachedResult
                }
                Log.d(TAG_SYNC, "runOnce: cached round failed/evicted, falling through to fresh dial")
            }

            // === Fresh path: race for a link, run Noise XX, run engine ======
            transports.startAll(communityId)
            try {
                Log.d(TAG_SYNC, "runOnce: transports started, waiting for first link (timeout ${timeoutMs}ms)")
                val outcome = withTimeoutOrNull(timeoutMs) { firstAvailableLink(ownPub.bytes, preferInitiator) }
                    ?: run {
                        Log.w(TAG_SYNC, "runOnce: timed out waiting for a link")
                        consecutiveFailures++
                        return Result(0, 0, 0, errorReason = "No peer in range")
                    }
                val link = outcome.link
                val role = outcome.role
                Log.d(TAG_SYNC, "runOnce: got link via $role")
                // Whether this round staged its (link, noise) into the
                // cache. When true, both the link and the noise are
                // owned by the cache; the finally blocks below MUST
                // NOT close them.
                var keepLinkAlive = false
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
                            consecutiveFailures++
                            return Result(
                                attemptedPeers = 1,
                                pushedMessages = 0,
                                receivedMessages = 0,
                                errorReason = "Peer didn't respond in time",
                            )
                        }
                    }
                    Log.d(TAG_SYNC, "runOnce: session opened, pushed=${sessionResult.engineResult.pushedCount} received=${sessionResult.engineResult.receivedCount}")
                    // From here on the caller owns sessionResult.noise.
                    // Either we cache it together with the link, or we
                    // close it. A cancellation during the work below
                    // would otherwise leak the noise object's keystore-
                    // derived secret.
                    var noiseRetained = false
                    try {
                        if (sessionResult.engineResult.receivedCount > 0) {
                            postInboundNotification(sessionResult.peerPub)
                        }
                        // Piggyback a revocation anti-entropy round on
                        // the same Noise transport. Both peers are
                        // mutually authenticated; revocations they hold
                        // get exchanged before the link is cached or
                        // closed.
                        //
                        // Failures here are non-fatal — the messaging
                        // exchange already succeeded and the user has
                        // already seen their messages. We just don't
                        // carry the revocation count forward.
                        // CancellationException is rethrown so the
                        // parent scope's cancellation contract is
                        // preserved.
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
                        val rotationsReceived = try {
                            val graph = trustGraphService.snapshot()
                            runKeyRotationSyncRound(
                                link = link,
                                database = database,
                                communityId = communityId.bytes,
                                trustGraph = graph,
                                sodium = sodium,
                            )
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Throwable) { 0 }
                        // Sprint 1: stage (link, noise) for next round
                        // when the link is Tor. BLE returns false and
                        // we close both as before.
                        val cachedThisRound = maybeCacheTorSession(
                            link = link,
                            noise = sessionResult.noise,
                            role = role,
                            peerPub = sessionResult.peerPub,
                        )
                        if (cachedThisRound) {
                            keepLinkAlive = true
                            noiseRetained = true
                        }
                        consecutiveFailures = 0
                        return Result(
                            attemptedPeers = 1,
                            pushedMessages = sessionResult.engineResult.pushedCount,
                            receivedMessages = sessionResult.engineResult.receivedCount,
                            revocationsReceived = revocationsReceived,
                        )
                    } finally {
                        if (!noiseRetained) runCatching { sessionResult.noise.close() }
                    }
                } finally {
                    if (!keepLinkAlive) runCatching { link.close() }
                }
            } finally {
                runCatching { transports.stopAll() }
            }
        } finally {
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
        val outcome: LinkOutcome = run {
            Log.d(TAG_SYNC, "firstAvailableLink: racing dial+accept")
            raceBoth(ownPubBytes)
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

    private suspend fun raceBoth(ownPubBytes: ByteArray): LinkOutcome {
        transports.drainStaleAccepted()
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
        val acceptDeferred = raceScope.async {
            val link = transports.acceptedLinks().first()
            LinkOutcome(link, MessageSyncEngine.HandshakeRole.Responder)
        }
        val deferreds = listOf(bleConnectDeferred, torDialDeferred, acceptDeferred)
        try {
            val outcome = kotlinx.coroutines.selects.select<LinkOutcome> {
                bleConnectDeferred.onAwait { it }
                torDialDeferred.onAwait { it }
                acceptDeferred.onAwait { it }
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
     * Successful fresh-handshake session. [noise] is in transport state
     * with cipher counters at whatever post-handshake position the
     * engine's frames left them. Caller owns it: cache for next round
     * (Tor links) or close (non-Tor / failure).
     */
    private data class SessionResult(
        val peerPub: PublicKey,
        val noise: NoiseSession,
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
        peerLookup: Map<com.wyspr.core.identity.PeerKey, PublicKey>,
    ): SessionResult? {
        val noiseRole = when (role) {
            MessageSyncEngine.HandshakeRole.Initiator -> NoiseSession.Role.Initiator
            MessageSyncEngine.HandshakeRole.Responder -> NoiseSession.Role.Responder
        }
        val prologue = buildSyncPrologue(communityId)
        val noise = NoiseSessionImpl(noiseRole)
        // Caller owns the noise object only on the successful-return path.
        // Every other exit (channel-binding null, throw, cancellation)
        // closes it in finally so the keystore-derived X25519 secret is
        // wiped promptly. The successful-return path hands ownership to
        // the caller for either caching or closing.
        var callerOwnsNoise = false
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
            val peerPub = peerLookup[com.wyspr.core.identity.PeerKey(peerX25519)]
            if (peerPub == null) {
                Log.w(TAG_SYNC, "openSessionAndSync: unknown peer X25519, running rotation sync before rejecting")
                runCatching {
                    val graph = trustGraphService.snapshot()
                    runKeyRotationSyncRound(
                        link = link,
                        database = database,
                        communityId = communityId.bytes,
                        trustGraph = graph,
                        sodium = sodium,
                    )
                }
                return null
            }

            val engine = buildEngine(
                role = role,
                noise = noise,
                link = link,
                peerPub = peerPub,
                ownPub = ownPub,
            )
            val result = SessionResult(peerPub = peerPub, noise = noise, engineResult = engine.run())
            callerOwnsNoise = true
            return result
        } finally {
            if (!callerOwnsNoise) noise.close()
        }
    }

    /**
     * Build the per-round [MessageSyncEngine]. Pulled out as a helper so
     * the cached-session fast path can instantiate one with the same DI
     * graph that [openSessionAndSync] uses.
     */
    private fun buildEngine(
        role: MessageSyncEngine.HandshakeRole,
        noise: NoiseSession,
        link: Link,
        peerPub: PublicKey,
        ownPub: PublicKey,
    ): MessageSyncEngine = MessageSyncEngine(
        role = role,
        noise = noise,
        link = link,
        peerPub = peerPub,
        ownPub = ownPub,
        store = store,
        groupStore = groupStore,
        sodium = sodium,
        keystore = keystore,
        bindingService = mailboxBindingService,
        mailboxHost = mailboxHost,
        database = database,
        ownPaymentAddressProvider = ownPaymentAddressProvider,
    )

    /**
     * Cache (link, noise) for [peerPub] when the link is a Tor circuit.
     * Returns true if cached. Non-Tor links are not cached — see the
     * comment on [cachedSessions].
     *
     * Closes and replaces any pre-existing entry for the same peer; the
     * old session is assumed stale because the caller only reaches here
     * after completing a fresh handshake.
     */
    private suspend fun maybeCacheTorSession(
        link: Link,
        noise: NoiseSession,
        role: MessageSyncEngine.HandshakeRole,
        peerPub: PublicKey,
    ): Boolean {
        if (link.endpoint.kind != Transport.Kind.TorHiddenService) return false
        val key = com.wyspr.core.identity.PeerKey(peerPub.bytes)
        cachedSessions.remove(key)?.let { stale ->
            closeCachedSession(stale)
        }
        cachedSessions[key] = CachedSession(link = link, noise = noise, role = role, peerPub = peerPub)
        Log.d(TAG_SYNC, "maybeCacheTorSession: cached Tor session for peer ${shortHex(peerPub.bytes)}")
        return true
    }

    /** Evict and close a cached session, no-op if not present. */
    private suspend fun evictCachedSession(peerPub: PublicKey) {
        val key = com.wyspr.core.identity.PeerKey(peerPub.bytes)
        val removed = cachedSessions.remove(key) ?: return
        Log.d(TAG_SYNC, "evictCachedSession: evicting peer=${shortHex(peerPub.bytes)} (age=${(System.currentTimeMillis() - removed.acquiredAtMs) / 1000}s)")
        runCatching { removed.noise.close() }
        runCatching { removed.link.close() }
    }

    private suspend fun closeCachedSession(session: CachedSession) {
        runCatching { session.noise.close() }
        runCatching { session.link.close() }
    }

    /** First 4 bytes as hex for logs. */
    private fun shortHex(bytes: ByteArray): String =
        bytes.take(4).joinToString("") { "%02x".format(it) }

    /**
     * Attempt one sync round on a cached (Tor) session. Returns the
     * Result on success, or null on any failure — in which case the
     * cache has been evicted and the caller falls through to a fresh
     * dial. CancellationException propagates.
     *
     * Wraps the engine in [SESSION_TIMEOUT_MS] so a half-dead cached
     * link (peer restarted, socket TCP-alive but app-dead) can't hold
     * the round mutex forever.
     */
    private suspend fun tryCachedRound(
        cached: CachedSession,
        communityId: CommunityId,
        ownPub: PublicKey,
    ): Result? {
        Log.d(
            TAG_SYNC,
            "tryCachedRound: peer=${shortHex(cached.peerPub.bytes)} role=${cached.role} " +
                "age=${(System.currentTimeMillis() - cached.acquiredAtMs) / 1000}s",
        )
        return try {
            val engineResult = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                buildEngine(
                    role = cached.role,
                    noise = cached.noise,
                    link = cached.link,
                    peerPub = cached.peerPub,
                    ownPub = ownPub,
                ).run()
            } ?: run {
                Log.w(TAG_SYNC, "tryCachedRound: timed out — evicting cache")
                evictCachedSession(cached.peerPub)
                return null
            }
            if (engineResult.receivedCount > 0) {
                postInboundNotification(cached.peerPub)
            }
            val revocationsReceived = try {
                val graph = trustGraphService.snapshot()
                runRevocationSyncRound(
                    link = cached.link,
                    database = database,
                    communityId = communityId.bytes,
                    trustGraph = graph,
                    sodium = sodium,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG_SYNC,
                    "tryCachedRound: revocation round failed after messaging succeeded: " +
                        "${t.javaClass.simpleName}",
                )
                0
            }
            try {
                val graph = trustGraphService.snapshot()
                runKeyRotationSyncRound(
                    link = cached.link,
                    database = database,
                    communityId = communityId.bytes,
                    trustGraph = graph,
                    sodium = sodium,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) { }
            Result(
                attemptedPeers = 1,
                pushedMessages = engineResult.pushedCount,
                receivedMessages = engineResult.receivedCount,
                revocationsReceived = revocationsReceived,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(
                TAG_SYNC,
                "tryCachedRound: failed (${t.javaClass.simpleName}: ${t.message}) — evicting cache",
            )
            evictCachedSession(cached.peerPub)
            null
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
                preview = formatPreview(recent.body),
            )
        }
    }

    /** Substitute friendly labels for tagged-body markers so a lock-
     *  screen preview doesn't show raw `wyspr:loc:...` / `wyspr:img:...`. */
    private fun formatPreview(body: String): String {
        if (body.startsWith("wyspr:loc:")) return "Location shared"
        if (body.startsWith("wyspr:img:")) return "Photo"
        if (body.startsWith("wyspr:audio:")) return "Voice note"
        if (body.startsWith("wyspr:file:")) return "File"
        if (body.startsWith("wyspr:disappear:")) return "Timer changed"
        if (body.startsWith("wyspr:react:")) {
            val decoded = com.wyspr.feature.messaging.reactions.ReactionPayload.decode(body)
            return if (decoded != null && !decoded.isRetract) "Reacted ${decoded.emoji}" else "Removed a reaction"
        }
        return body.take(NOTIFICATION_PREVIEW_CHARS)
    }

    /**
     * Map the X25519 form of every paired peer's identity to the
     * Ed25519 form. Built once per sync attempt; the local Ed25519
     * pub is skipped so we don't accidentally accept ourselves.
     */
    private suspend fun buildPeerLookup(ownPub: PublicKey): Map<com.wyspr.core.identity.PeerKey, PublicKey> {
        val out = HashMap<com.wyspr.core.identity.PeerKey, PublicKey>()
        val edges = database.trustEdgeDao.all()
        val seen = HashSet<com.wyspr.core.identity.PeerKey>()
        for (edge in edges) {
            val candidates = listOf(edge.fromPub, edge.toPub)
            for (pubBytes in candidates) {
                if (pubBytes.contentEquals(ownPub.bytes)) continue
                if (!seen.add(com.wyspr.core.identity.PeerKey(pubBytes))) continue
                val x25519 = ed25519ToX25519(pubBytes) ?: continue
                out[com.wyspr.core.identity.PeerKey(x25519)] = PublicKey(pubBytes)
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
         *
         * Must be generous enough for a large Push frame (139KB+ over BLE
         * chunks at ~500B/write with ack round-trips) plus Noise XX (3
         * messages) plus read receipts plus mailbox phases plus End.
         */
        const val SESSION_TIMEOUT_MS: Long = 45_000L
        const val NOTIFICATION_PREVIEW_CHARS = 120
        private val PROLOGUE_PREFIX = "WYSPR/v1/sync".encodeToByteArray()
        private const val TAG_SYNC = "MessageSync"
        // Upper bound for the randomised dial-jitter delay. 1500ms is
        // enough to give a peer that started ~simultaneously time to
        // see our advert and dial first, without slowing the typical
        // one-peer-passive case noticeably.
        private const val DIAL_JITTER_MS: Long = 1500
    }
}
