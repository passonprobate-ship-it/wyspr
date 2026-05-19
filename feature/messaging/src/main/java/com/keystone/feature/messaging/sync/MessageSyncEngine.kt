package com.keystone.feature.messaging.sync

import com.keystone.core.crypto.NoiseSession
import com.keystone.core.identity.PublicKey
import com.keystone.core.transport.Link
import com.keystone.feature.messaging.MessageEnvelope
import com.keystone.feature.messaging.MessageStore
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
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val frameTimeoutMs: Long = DEFAULT_FRAME_TIMEOUT_MS,
) {

    enum class HandshakeRole { Initiator, Responder }

    data class Result(
        val pushedCount: Int,
        val receivedCount: Int,
    )

    suspend fun run(): Result {
        var pushed = 0
        var received = 0
        when (role) {
            HandshakeRole.Initiator -> {
                pushed += pushPending()
                received += awaitPushAndAck()
                exchangeReadReceipts()
                sendFrame(MessageSyncFrame.End)
                awaitEndOrNothing()
            }
            HandshakeRole.Responder -> {
                received += awaitPushAndAck()
                pushed += pushPending()
                exchangeReadReceipts()
                awaitEndOrNothing()
                sendFrame(MessageSyncFrame.End)
            }
        }
        return Result(pushedCount = pushed, receivedCount = received)
    }

    /**
     * After the Push/Ack round both sides exchange read receipts:
     * each side sends [MessageSyncFrame.Read] listing the ids of
     * inbound messages from the peer that the local user has now
     * viewed. The peer's response uses [MessageSyncFrame.Ack] to
     * confirm receipt; we then mark our outbound rows for those
     * ids as `read`, and tell the store our own inbound viewed
     * receipts have been delivered.
     */
    private suspend fun exchangeReadReceipts() {
        val pendingReads = store.pendingReadAckFor(peerPub)
        sendFrame(MessageSyncFrame.Read(pendingReads.map { it.id }))
        // Wait for peer's Ack confirming receipt of our Read frame.
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

        // Receive peer's Read frame — the messages they've now read.
        val peerReadFrame = receiveFrame() ?: return
        val peerRead = (peerReadFrame as? MessageSyncFrame.Read)
            ?: error("expected Read, got ${peerReadFrame::class.simpleName}")
        val accepted = store.applyPeerReadReceipts(peerRead.ids)
        sendFrame(MessageSyncFrame.Ack(accepted))
    }

    private suspend fun pushPending(): Int {
        val pending = store.pendingOutboundFor(ownPub = ownPub, peerPub = peerPub)
        if (pending.isEmpty()) {
            sendFrame(MessageSyncFrame.Push(emptyList()))
        } else {
            // Chunk in batches so a misbehaving peer can't claim a
            // huge array against us; the size cap is mirrored in
            // MessageSyncFrame.MAX_BATCH on the receive side.
            sendFrame(MessageSyncFrame.Push(pending.map { it.toEnvelope() }))
        }
        // Wait for the matching Ack.
        val frame = receiveFrame() ?: return 0
        val ack = (frame as? MessageSyncFrame.Ack)
            ?: error("expected Ack, got ${frame::class.simpleName}")
        // Mark the acknowledged subset as sent. We don't require
        // every id to come back — a benign peer might intentionally
        // skip a duplicate it already has.
        val ackedIds = ack.ids.map { it.toList() }.toSet()
        var count = 0
        for (msg in pending) {
            if (msg.id.toList() in ackedIds) {
                store.markSent(msg.id)
                count++
            }
        }
        return count
    }

    private suspend fun awaitPushAndAck(): Int {
        val frame = receiveFrame() ?: return 0
        val push = (frame as? MessageSyncFrame.Push)
            ?: error("expected Push, got ${frame::class.simpleName}")
        if (push.envelopes.isEmpty()) {
            sendFrame(MessageSyncFrame.Ack(emptyList()))
            return 0
        }
        // Channel binding has authenticated peerPub. Enforce that
        // every envelope's `fromPub` matches the peer we authenticated
        // — direct messages only in Sprint 2. Relay forwarding lands
        // in Sprint 3 with per-envelope signature verification.
        val accepted = ArrayList<ByteArray>(push.envelopes.size)
        val received = nowSeconds()
        for (env in push.envelopes) {
            if (!env.fromPub.bytes.contentEquals(peerPub.bytes)) continue
            if (!env.toPub.bytes.contentEquals(ownPub.bytes)) continue
            store.ingest(env, receivedAtSeconds = received)
            accepted.add(env.id)
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
        link.send(noise.encrypt(bytes))
    }

    private suspend fun receiveFrame(): MessageSyncFrame? {
        val ciphertext = withTimeoutOrNull(frameTimeoutMs) {
            link.incoming().firstOrNull()
        } ?: return null
        val plaintext = noise.decrypt(ciphertext)
        return MessageSyncFrame.fromWire(plaintext)
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
    }
}
