package com.wyspr.feature.messaging.sync

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.crypto.NoiseSession
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.MailboxPullCursorEntity
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.transport.Link
import com.wyspr.feature.messaging.MessageEnvelope
import com.wyspr.feature.messaging.MessageStore
import com.wyspr.feature.messaging.verify
import com.wyspr.feature.messaging.groups.GroupMessageEnvelope
import com.wyspr.feature.messaging.groups.GroupStore
import com.wyspr.feature.messaging.groups.verify as verifyGroup
import com.wyspr.feature.messaging.mailbox.MailboxBinding
import com.wyspr.feature.messaging.mailbox.MailboxBindingService
import com.wyspr.feature.messaging.mailbox.MailboxEnvelope
import com.wyspr.feature.messaging.mailbox.MailboxFrame
import com.wyspr.feature.messaging.mailbox.MailboxHost
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the [MessageSyncFrame] exchange over a Noise transport
 * session that has already been established and authenticated
 * against [peerPub] via channel binding. Pure protocol logic —
 * no BLE awareness, no DI, no UI threading. The caller
 * ([MessageSyncService]) wires this up after the link is ready.
 *
 * Both directions push their pending outbound for the peer and
 * ack the inbound batch. The initiator goes first; the responder
 * follows the same shape one step later. Either side can decide
 * to bail at any frame boundary by returning without sending End
 * — the link will then be closed by the caller's finally block.
 *
 * Frame timeouts default to [DEFAULT_FRAME_TIMEOUT_MS] per receive
 * to bound the runtime against a misbehaving peer.
 */
internal class MessageSyncEngine(
    private val role: HandshakeRole,
    private val noise: NoiseSession,
    private val link: Link,
    private val peerPub: PublicKey,
    private val ownPub: PublicKey,
    private val store: MessageStore,
    private val groupStore: GroupStore,
    private val sodium: LazySodiumAndroid,
    private val keystore: KeystoreManager,
    private val bindingService: MailboxBindingService,
    private val mailboxHost: MailboxHost,
    private val database: WysprDatabase,
    private val ownPaymentAddressProvider: com.wyspr.core.transport.OwnPaymentAddressProvider,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val frameTimeoutMs: Long = DEFAULT_FRAME_TIMEOUT_MS,
) {

    enum class HandshakeRole { Initiator, Responder }

    data class Result(
        val pushedCount: Int,
        val receivedCount: Int,
    )

    suspend fun run(): Result {
        Log.d(TAG, "engine.run: start (role=$role)")
        var pushed = 0
        var received = 0
        try {
            when (role) {
                HandshakeRole.Initiator -> {
                    Log.d(TAG, "engine.run(Initiator): pushPending")
                    pushed += pushPending()
                    Log.d(TAG, "engine.run(Initiator): awaitPushAndAck")
                    received += awaitPushAndAck()
                    Log.d(TAG, "engine.run(Initiator): exchangeReadReceipts")
                    exchangeReadReceipts()
                    Log.d(TAG, "engine.run(Initiator): mailboxPullPhase")
                    received += mailboxPullPhase()
                    Log.d(TAG, "engine.run(Initiator): sending End")
                    sendFrame(MessageSyncFrame.End)
                    Log.d(TAG, "engine.run(Initiator): awaitEndOrNothing")
                    awaitEndOrNothing()
                }
                HandshakeRole.Responder -> {
                    Log.d(TAG, "engine.run(Responder): awaitPushAndAck")
                    received += awaitPushAndAck()
                    Log.d(TAG, "engine.run(Responder): pushPending")
                    pushed += pushPending()
                    Log.d(TAG, "engine.run(Responder): exchangeReadReceipts")
                    exchangeReadReceipts()
                    Log.d(TAG, "engine.run(Responder): mailboxPullPhase")
                    received += mailboxPullPhase()
                    Log.d(TAG, "engine.run(Responder): awaitEndOrNothing")
                    awaitEndOrNothing()
                    Log.d(TAG, "engine.run(Responder): sending End")
                    sendFrame(MessageSyncFrame.End)
                }
            }
            Log.d(TAG, "engine.run: done (pushed=$pushed received=$received)")
        } catch (t: Throwable) {
            Log.w(TAG, "engine.run: failed (${t.javaClass.simpleName}: ${t.message})")
            throw t
        }
        return Result(pushedCount = pushed, receivedCount = received)
    }

    /**
     * After the Push/Ack round both sides exchange read receipts.
     * The Initiator sends first / receives second; the Responder
     * receives first / sends second — same shape as
     * pushPending/awaitPushAndAck. A symmetric send-first design
     * deadlocked here (both sides sent Read, both then expected
     * Ack but got the peer's Read frame, surfaced as
     * "expected Ack of Read, got Read" in run 21).
     */
    private suspend fun exchangeReadReceipts() {
        when (role) {
            HandshakeRole.Initiator -> {
                sendOwnReadFrame()
                awaitPeerReadFrame()
            }
            HandshakeRole.Responder -> {
                awaitPeerReadFrame()
                sendOwnReadFrame()
            }
        }
    }

    /** Send our Read frame, then await peer's Ack of it. */
    private suspend fun sendOwnReadFrame() {
        val pendingReads = store.pendingReadAckFor(peerPub)
        sendFrame(MessageSyncFrame.Read(pendingReads.map { it.id }))
        val ackFrame = receiveFrame() ?: return
        val ack = (ackFrame as? MessageSyncFrame.Ack)
            ?: error("expected Ack of Read, got ${ackFrame::class.simpleName}")
        val ackedIdSet = ack.ids.map { it.toList() }.toSet()
        val confirmedReadAcks = pendingReads
            .filter { it.id.toList() in ackedIdSet }
            .map { it.id }
        if (confirmedReadAcks.isNotEmpty()) {
            store.markReadAcked(confirmedReadAcks)
        }
    }

    /** Await peer's Read frame, apply it, then send our Ack. */
    private suspend fun awaitPeerReadFrame() {
        val peerReadFrame = receiveFrame() ?: return
        val peerRead = (peerReadFrame as? MessageSyncFrame.Read)
            ?: error("expected Read, got ${peerReadFrame::class.simpleName}")
        val accepted = store.applyPeerReadReceipts(peerRead.ids)
        sendFrame(MessageSyncFrame.Ack(accepted))
    }

    private suspend fun pushPending(): Int {
        val pending = store.pendingOutboundFor(ownPub = ownPub, peerPub = peerPub)
        // Group messages we owe this peer (status=pending, peer is an
        // active member of the group). See GroupStore for the v1
        // semantics — best-effort, no per-recipient tracking yet.
        val pendingGroups = with(groupStore) {
            groupStore.pendingGroupMessagesForPeer(ownPub, peerPub)
        }
        val groupEnvelopes = with(groupStore) { pendingGroups.map { it.toEnvelope() } }
        // Bundle every membership cert the peer might need to verify
        // the group envelopes above. v1 sends all of them every round;
        // dedupe happens on the receive side (ingestMembership is
        // idempotent on (groupId, memberPub)).
        val membershipCerts = groupStore.membershipCertsForPeer(peerPub)

        // Mailbox binding propagation. Broadcast EVERY mailbox the
        // local user has delegated to (multi-host, Sprint 2 of
        // TOR-ACROSS-WEB) so peers learn about all of them — that's
        // what gives the recipient redundancy when one host is down.
        // Empty list when the user hasn't configured any mailbox.
        val mailboxBindings = bindingService.myBindings(ownPub)

        // Mailbox push lane (Phase 3b). For every pending direct
        // outbound where the recipient has a known binding pointing
        // at THIS peer, seal a copy and stage it. The peer either
        // stores it (if running as a mailbox) or silently drops it
        // (the unacked envelope means we keep retrying next round).
        //
        // Only status=pending rows are candidates (sourced from
        // store.pendingOutboundFor). Status=sent/delivered/read rows
        // are excluded — they've already been pushed to at least one
        // peer directly.
        val mailboxEnvelopes = sealForMailboxPeer(pending)

        // Sprint W3/W4: advertise our payment addresses to this peer.
        // Sourced via the per-peer form so the Monero provider mints
        // a relationship-scoped subaddress for THIS peer (W4) — the
        // peer therefore sees a different XMR address from every
        // other paired peer, which prevents on-chain linkage via
        // address reuse.
        val paymentAddresses = ownPaymentAddressProvider.ownAddressesFor(peerPub.bytes)
            .map { MessageSyncFrame.PaymentAddressEntry(it.chain, it.address) }

        // Even when all lists are empty we still send a Push so the
        // peer's awaitPushAndAck sees something — the protocol shape
        // expects exactly one Push from each side per round.
        sendFrame(
            MessageSyncFrame.Push(
                envelopes = pending.map { it.toEnvelope() },
                groupEnvelopes = groupEnvelopes,
                membershipCerts = membershipCerts,
                mailboxBindings = mailboxBindings,
                mailboxEnvelopes = mailboxEnvelopes,
                paymentAddresses = paymentAddresses,
            ),
        )
        val frame = receiveFrame() ?: return 0
        val ack = (frame as? MessageSyncFrame.Ack)
            ?: error("expected Ack, got ${frame::class.simpleName}")
        // Mark the acknowledged subset as sent. Same liberal semantics
        // as 1:1 — peer may legitimately skip a duplicate id.
        val ackedIds = ack.ids.map { it.toList() }.toSet()
        var count = 0
        for (msg in pending) {
            if (msg.id.toList() in ackedIds) {
                store.markSent(msg.id)
                count++
            }
        }
        for (msg in pendingGroups) {
            if (msg.id.toList() in ackedIds) {
                groupStore.markGroupDeliveredTo(msg.id, peerPub, nowSeconds())
                count++
            }
        }
        // Mailbox envelopes have their own 16-byte ids (distinct from
        // the wrapped MessageEnvelope ids), and the host's ack for
        // them piggybacks on the same Ack frame. We don't currently
        // persist "this envelope reached its mailbox" state — the
        // mailbox dedup (INSERT IGNORE on envelope_id) makes
        // re-pushing idempotent, so we'll keep including the same
        // sealed copy every round until the direct delivery flips the
        // underlying message to status=delivered. That's slightly
        // wasteful but safe and self-healing.
        return count
    }

    /**
     * For each pending outbound, if the recipient has a known
     * MailboxBinding pointing at [peerPub], produce a sealed
     * [MailboxEnvelope] addressed to that recipient. Empty list
     * unless the current peer is a mailbox host for one of our
     * recipients.
     */
    private suspend fun sealForMailboxPeer(
        pending: List<com.wyspr.core.database.entities.MessageEntity>,
    ): List<MailboxEnvelope> {
        if (pending.isEmpty()) return emptyList()
        val now = nowSeconds()
        val sealed = ArrayList<MailboxEnvelope>(pending.size)
        for (msg in pending) {
            val recipient = PublicKey(msg.toPub)
            // Multi-host: look at every binding the recipient has
            // published. Seal an envelope only if THIS peer is one of
            // their hosts. When a recipient has bindings to multiple
            // hosts we'll seal a copy each round for whichever host we
            // happen to be talking to; the host's dedup (INSERT IGNORE
            // on envelope_id) keeps the storage cost bounded.
            val recipientBindings = bindingService.forOwner(recipient)
            val isPeerAHostForRecipient = recipientBindings.any { b ->
                b.mailboxPub.bytes.contentEquals(peerPub.bytes)
            }
            if (!isPeerAHostForRecipient) continue
            try {
                sealed.add(
                    MailboxEnvelope.seal(
                        keystore = keystore,
                        sodium = sodium,
                        inner = msg.toEnvelope(),
                        recipientPub = recipient,
                        now = now,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "sealForMailboxPeer: seal failed for ${recipient.bytes.take(4)}…: ${t::class.simpleName}")
                // Skip this message — direct delivery + future
                // rounds will keep retrying.
            }
        }
        return sealed
    }

    private suspend fun awaitPushAndAck(): Int {
        val frame = receiveFrame() ?: return 0
        val push = (frame as? MessageSyncFrame.Push)
            ?: error("expected Push, got ${frame::class.simpleName}")
        val totallyEmpty = push.envelopes.isEmpty() &&
            push.groupEnvelopes.isEmpty() &&
            push.membershipCerts.isEmpty() &&
            push.mailboxBindings.isEmpty() &&
            push.mailboxEnvelopes.isEmpty() &&
            push.paymentAddresses.isEmpty()
        if (totallyEmpty) {
            sendFrame(MessageSyncFrame.Ack(emptyList()))
            return 0
        }
        val accepted = ArrayList<ByteArray>(push.envelopes.size + push.groupEnvelopes.size)
        val received = nowSeconds()

        // Ingest membership certs FIRST so the membership-check on the
        // group envelopes below sees the freshly-arrived members.
        // ingestMembership verifies the signature + groupId
        // consistency; certs from the wrong signer or with a forged
        // id are rejected.
        for (cert in push.membershipCerts) {
            groupStore.ingestMembership(cert, sodium)
        }

        // Mailbox bindings — only accept ones the Noise-authenticated
        // peer is themselves the owner of. This prevents the
        // single-edge-in attacker from broadcasting forged bindings
        // for third-party owners and redirecting future pushes.
        for (binding in push.mailboxBindings) {
            bindingService.ingest(binding, peerPub, sodium, received)
        }

        // Sprint W3: ingest the peer's payment addresses into
        // `peer_payment_address`. Channel binding has authenticated
        // the sender, so we attribute these to peerPub (the same
        // peer can't forge an address-for-someone-else here — only
        // their own). Same-address re-pushes are no-ops via the
        // REPLACE upsert on composite (peer_pub, chain, address).
        for (entry in push.paymentAddresses) {
            try {
                database.peerPaymentAddressDao.upsert(
                    com.wyspr.core.database.entities.PeerPaymentAddressEntity(
                        peerPub = peerPub.bytes,
                        chain = entry.chain,
                        address = entry.address,
                        createdAt = received,
                        revokedAt = null,
                        notes = null,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "payment-address ingest failed for chain=${entry.chain}: " +
                        "${t::class.simpleName}",
                )
                // Continue processing the rest of the Push frame —
                // one bad entry doesn't poison the round.
            }
        }

        // 1:1 envelopes. Channel binding has authenticated peerPub, so
        // every envelope.fromPub MUST equal peerPub and toPub MUST be
        // ownPub. The Ed25519 signature is also re-verified — channel
        // binding proves the transport peer, the signature proves the
        // payload wasn't tampered with (defence against a regression of
        // KeystoreManager.sign — see 2026-05-20 padding bug).
        for (env in push.envelopes) {
            if (!env.fromPub.bytes.contentEquals(peerPub.bytes)) continue
            if (!env.toPub.bytes.contentEquals(ownPub.bytes)) continue
            if (!env.verify(sodium)) {
                Log.w(TAG, "envelope ${env.id.take(4)}…: signature verify failed; dropping")
                continue
            }
            store.ingest(env, receivedAtSeconds = received)
            accepted.add(env.id)
        }
        // Group envelopes. Same channel-binding rule (sender must be
        // the authenticated peer) plus signature verify plus membership
        // check inside ingestGroup. The GroupStore dedupe handles repeated
        // deliveries from multiple members.
        for (env in push.groupEnvelopes) {
            if (!env.fromPub.bytes.contentEquals(peerPub.bytes)) continue
            if (!env.verifyGroup(sodium)) {
                Log.w(TAG, "group envelope ${env.id.take(4)}…: signature verify failed; dropping")
                continue
            }
            val ok = groupStore.ingestGroup(env, receivedAtSeconds = received)
            if (ok) accepted.add(env.id)
        }
        // Mailbox push lane — only acted on when we're running as a
        // mailbox. handlePush returns NotHosting silently if the
        // toggle is off; the envelope id is not acked, so the sender
        // keeps retrying with other paths.
        // Track mailbox-stored IDs separately so the returned count
        // only reflects real messages (1:1 + group). Mailbox-stored
        // envelopes are acked on the wire but should not inflate
        // receivedCount — they don't trigger notifications.
        val mailboxAccepted = ArrayList<ByteArray>()
        for (mxEnv in push.mailboxEnvelopes) {
            val outcome = mailboxHost.handlePush(
                envelope = mxEnv,
                fromPub = peerPub,
                now = received,
            )
            if (outcome is MailboxHost.PushOutcome.Stored) {
                mailboxAccepted.add(outcome.envelopeId)
            }
        }
        sendFrame(MessageSyncFrame.Ack(accepted + mailboxAccepted))
        return accepted.size
    }

    private suspend fun awaitEndOrNothing() {
        // Best-effort wait for the peer's End. A peer that closed
        // the link early is acceptable — the sync is already done
        // from our side.
        runCatching { receiveFrame() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /**
     * Mailbox pull/serve phase. Runs after read receipts, before End.
     *
     * Both sides exchange Pull frames symmetrically. If the local
     * user has a binding pointing at THIS peer, we pull. If we're
     * NOT pulling we still send a Pull with `EMPTY_CURSOR` and a
     * recipient pubkey of our own — the host returns an empty
     * PullResponse for a recipient with nothing stored. This
     * eliminates the "should I send Pull or Skip?" branch and lets
     * the protocol have a fixed shape regardless of role.
     *
     * Serving side checks [MailboxHost.handlePull] which is itself a
     * no-op when the local user isn't hosting. So a sync round between
     * two devices where neither is the other's mailbox just shuffles
     * empty Pulls and empty PullResponses — a few CBOR bytes per
     * round.
     *
     * Returns the number of mailbox envelopes ingested into the
     * local MessageStore.
     */
    private suspend fun mailboxPullPhase(): Int {
        // Multi-host: pull whenever THIS peer is one of MY mailbox
        // hosts. We may have several; this round's sync partner is
        // whichever one we happen to be connected to. Other hosts get
        // their own rounds.
        val myBindings = bindingService.myBindings(ownPub)
        val iPull = myBindings.any { it.mailboxPub.bytes.contentEquals(peerPub.bytes) }
        return when (role) {
            HandshakeRole.Initiator -> {
                val ingested = sendPullAndIngest(iPull)
                handlePeerPull()
                ingested
            }
            HandshakeRole.Responder -> {
                handlePeerPull()
                val ingested = sendPullAndIngest(iPull)
                ingested
            }
        }
    }

    /**
     * Send our Pull frame, await PullResponse, ingest the contents,
     * and Ack what we accepted. If we're not actually pulling
     * ([iPull] == false) we send an empty-cursor Pull and ignore the
     * response. The host returns nothing because we (the recipient
     * pub binding to the Noise channel) have no stored mail.
     */
    private suspend fun sendPullAndIngest(iPull: Boolean): Int {
        // Send the real high-water mark so the host can't replay
        // already-delivered envelopes. When we're not pulling we
        // still send an empty Pull (Long.MAX_VALUE cursor) — the
        // protocol shape stays fixed regardless of role. The host
        // returns nothing because there's nothing newer.
        val cursor = if (iPull) {
            database.mailboxPullCursorDao.getCursor(peerPub.bytes) ?: 0L
        } else {
            Long.MAX_VALUE
        }
        sendMailboxFrame(MailboxFrame.Pull(MailboxFrame.encodeCursor(cursor)))
        val resp = receiveMailboxFrame() as? MailboxFrame.PullResponse
            ?: run {
                Log.w(TAG, "mailbox: expected PullResponse, got something else; bailing")
                return 0
            }
        if (resp.envelopes.isEmpty()) {
            sendMailboxFrame(MailboxFrame.Ack(emptyList()))
            return 0
        }
        if (!iPull) {
            // Defence: we didn't ask to pull but the host sent us
            // envelopes anyway. Don't ingest something we didn't
            // expect; drop and don't ack so the host knows we
            // refused.
            Log.w(TAG, "mailbox: received envelopes despite not pulling; dropping")
            sendMailboxFrame(MailboxFrame.Ack(emptyList()))
            return 0
        }
        val ingestedIds = ingestPulledEnvelopes(resp.envelopes)
        sendMailboxFrame(MailboxFrame.Ack(ingestedIds))
        // Advance the cursor to the max `createdAt` actually accepted
        // so the next round won't refetch them. We only advance from
        // ingested envelopes — a host that serves bogus rows we drop
        // doesn't get to bump the cursor past good rows we still need.
        val newHighWater = resp.envelopes.asSequence()
            .filter { env -> ingestedIds.any { it.contentEquals(env.id) } }
            .maxOfOrNull { it.createdAt }
        if (newHighWater != null && newHighWater > cursor) {
            database.mailboxPullCursorDao.upsert(
                MailboxPullCursorEntity(
                    mailboxPub = peerPub.bytes,
                    sinceCursor = newHighWater,
                ),
            )
        }
        return ingestedIds.size
    }

    /**
     * Receive the peer's Pull frame, serve them via
     * [MailboxHost.handlePull] (or empty if we're not hosting / they
     * have nothing stored), then await their Ack.
     */
    private suspend fun handlePeerPull() {
        val peerPull = receiveMailboxFrame() as? MailboxFrame.Pull ?: run {
            Log.w(TAG, "mailbox: expected Pull, got something else; bailing serve")
            return
        }
        val sinceCursor = if (peerPull.sinceCursor.isEmpty()) 0L
        else MailboxFrame.decodeCursor(peerPull.sinceCursor)
        val envs = mailboxHost.handlePull(peerPub, sinceCursor)
        val maxCursorVal = envs.maxOfOrNull { it.createdAt } ?: sinceCursor
        sendMailboxFrame(MailboxFrame.PullResponse(envs, MailboxFrame.encodeCursor(maxCursorVal)))
        val ackFrame = receiveMailboxFrame() as? MailboxFrame.Ack ?: return
        if (ackFrame.ids.isNotEmpty()) {
            mailboxHost.handleAck(ackFrame.ids, peerPub)
        }
    }

    /**
     * Unseal + verify each envelope and ingest the inner
     * [MessageEnvelope] into [MessageStore]. Returns the envelope ids
     * (mailbox ids, NOT inner message ids) that were successfully
     * processed and should be acked — those rows will be deleted
     * from the mailbox.
     */
    private suspend fun ingestPulledEnvelopes(
        envelopes: List<MailboxEnvelope>,
    ): List<ByteArray> {
        if (envelopes.isEmpty()) return emptyList()
        val (xPub, xSec) = keystore.deriveStaticX25519()
        try {
            val received = nowSeconds()
            val acceptedIds = ArrayList<ByteArray>(envelopes.size)
            for (env in envelopes) {
                // Envelope's outer toPub should be ours — the host
                // indexed by it. Drop anything else.
                if (!env.toPub.bytes.contentEquals(ownPub.bytes)) continue
                val inner = try {
                    MailboxEnvelope.open(sodium, env, xPub, xSec)
                } catch (t: Throwable) {
                    Log.w(TAG, "mailbox: unseal failed for id=${env.id.take(4)}…: ${t::class.simpleName}")
                    continue
                }
                // Inner MessageEnvelope's signature must verify against
                // inner.fromPub. The host can't forge this — they
                // don't have the secret key.
                val signed = inner.signedBytes()
                if (!sodium.cryptoSignVerifyDetached(
                        inner.signature, signed, signed.size, inner.fromPub.bytes,
                    )
                ) {
                    Log.w(TAG, "mailbox: inner signature verify failed for ${env.id.take(4)}…")
                    continue
                }
                if (!inner.toPub.bytes.contentEquals(ownPub.bytes)) continue
                store.ingest(inner, receivedAtSeconds = received)
                acceptedIds.add(env.id)
            }
            return acceptedIds
        } finally {
            xSec.fill(0)
        }
    }

    private suspend fun sendMailboxFrame(frame: MailboxFrame) {
        val bytes = frame.wireBytes()
        val ct = noise.encrypt(bytes)
        Log.d(TAG, "sendMailboxFrame: ${frame::class.simpleName} plaintext=${bytes.size} ct=${ct.size}")
        link.send(ct)
    }

    private suspend fun receiveMailboxFrame(): MailboxFrame? {
        val ciphertext = withTimeoutOrNull(frameTimeoutMs) {
            link.incoming().firstOrNull()
        } ?: run {
            Log.w(TAG, "receiveMailboxFrame: timeout / channel closed")
            return null
        }
        val plaintext = noise.decrypt(ciphertext)
        val frame = MailboxFrame.fromWire(plaintext)
        Log.d(TAG, "receiveMailboxFrame: decoded ${frame::class.simpleName}")
        return frame
    }

    private suspend fun sendFrame(frame: MessageSyncFrame) {
        val bytes = frame.wireBytes()
        val ct = noise.encrypt(bytes)
        Log.d(TAG, "sendFrame: ${frame::class.simpleName} plaintext=${bytes.size} ct=${ct.size}")
        link.send(ct)
        Log.d(TAG, "sendFrame: ${frame::class.simpleName} link.send returned")
    }

    private suspend fun receiveFrame(): MessageSyncFrame? {
        Log.d(TAG, "receiveFrame: awaiting (timeout=${frameTimeoutMs}ms)")
        val ciphertext = withTimeoutOrNull(frameTimeoutMs) {
            link.incoming().firstOrNull()
        } ?: run {
            Log.w(TAG, "receiveFrame: timeout / channel closed")
            return null
        }
        Log.d(TAG, "receiveFrame: got ciphertext (${ciphertext.size} bytes), decrypting")
        val plaintext = noise.decrypt(ciphertext)
        val frame = MessageSyncFrame.fromWire(plaintext)
        Log.d(TAG, "receiveFrame: decoded ${frame::class.simpleName}")
        return frame
    }

    private fun com.wyspr.core.database.entities.MessageEntity.toEnvelope(): MessageEnvelope =
        MessageEnvelope(
            id = id,
            fromPub = PublicKey(fromPub),
            toPub = PublicKey(toPub),
            createdAt = createdAt,
            body = body,
            signature = signature,
        )

    companion object {
        /** 30s/frame budget — generous for slow BLE, tight against hostile stall. */
        const val DEFAULT_FRAME_TIMEOUT_MS: Long = 30_000L
        private const val TAG = "SyncEngine"
    }
}
