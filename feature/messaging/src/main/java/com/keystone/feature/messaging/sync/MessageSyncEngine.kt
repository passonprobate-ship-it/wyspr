package com.keystone.feature.messaging.sync

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.crypto.NoiseSession
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.Link
import com.keystone.feature.messaging.MessageEnvelope
import com.keystone.feature.messaging.MessageStore
import com.keystone.feature.messaging.groups.GroupMessageEnvelope
import com.keystone.feature.messaging.groups.GroupStore
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
        // Even when all lists are empty we still send a Push so the
        // peer's awaitPushAndAck sees something — the protocol shape
        // expects exactly one Push from each side per round.
        sendFrame(
            MessageSyncFrame.Push(
                envelopes = pending.map { it.toEnvelope() },
                groupEnvelopes = groupEnvelopes,
                membershipCerts = membershipCerts,
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
                groupStore.markGroupSent(msg.id)
                count++
            }
        }
        return count
    }

    private suspend fun awaitPushAndAck(): Int {
        val frame = receiveFrame() ?: return 0
        val push = (frame as? MessageSyncFrame.Push)
            ?: error("expected Push, got ${frame::class.simpleName}")
        if (push.envelopes.isEmpty() &&
            push.groupEnvelopes.isEmpty() &&
            push.membershipCerts.isEmpty()
        ) {
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

        // 1:1 envelopes. Channel binding has authenticated peerPub, so
        // every envelope.fromPub MUST equal peerPub and toPub MUST be
        // ownPub. Anything else is dropped silently (and unacked, so
        // the peer keeps retrying — they'll see the disagreement).
        for (env in push.envelopes) {
            if (!env.fromPub.bytes.contentEquals(peerPub.bytes)) continue
            if (!env.toPub.bytes.contentEquals(ownPub.bytes)) continue
            store.ingest(env, receivedAtSeconds = received)
            accepted.add(env.id)
        }
        // Group envelopes. Same channel-binding rule (sender must be
        // the authenticated peer) plus a membership check: peerPub must
        // be an active member of envelope.groupId. The GroupStore
        // dedupe handles repeated deliveries from multiple members.
        for (env in push.groupEnvelopes) {
            if (!env.fromPub.bytes.contentEquals(peerPub.bytes)) continue
            val ok = groupStore.ingestGroup(env, receivedAtSeconds = received)
            if (ok) accepted.add(env.id)
        }
        sendFrame(MessageSyncFrame.Ack(accepted))
        return accepted.size
    }

    private suspend fun awaitEndOrNothing() {
        // Best-effort wait for the peer's End. A peer that closed
        // the link early is acceptable — the sync is already done
        // from our side.
        runCatching { receiveFrame() }
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

    private fun com.keystone.core.database.entities.MessageEntity.toEnvelope(): MessageEnvelope =
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
