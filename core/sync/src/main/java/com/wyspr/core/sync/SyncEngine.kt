package com.wyspr.core.sync

import com.wyspr.core.transport.Link
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * Anti-entropy engine. One [runSession] call drives one round of
 * HaveSet/Want/Push between two peers over a single [Link].
 *
 * Protocol (PROTOCOLS.md §4):
 *
 *     A          B
 *     |── HaveSet ──►|
 *     |◄── HaveSet ──|
 *     |── Want ─────►|     (= peer.have − mine)
 *     |◄── Want ─────|
 *     |── Push ─────►|     (= rows the peer asked for)
 *     |◄── Push ─────|
 *     ↓              ↓
 *     ingest each received row
 *
 * Each step is one [Link.send] / [Link.incoming] frame. The engine
 * runs symmetrically: both peers execute the same code path; the link
 * delivers each side's send to the other side's receive.
 *
 * Failure modes — all surface as exceptions:
 *   - Link closes mid-flow → coroutine cancellation
 *   - Peer sends a non-CBOR frame → [IllegalArgumentException]
 *   - Peer sends the wrong message type → [IllegalStateException]
 *   - Frame exceeds [SyncMessage.MAX_FRAME_BYTES] → [IllegalArgumentException]
 *
 * Callers (transport layer) wrap with try/catch and emit telemetry; for
 * an unauthenticated peer or one that misbehaves repeatedly, the trust-
 * graph layer should quarantine.
 */
class SyncEngine(
    private val repository: SyncRepository,
) {

    data class Result(
        val sent: Int,
        val received: Int,
        val rejected: Int,
    )

    /** Asymmetric coordination role — peers agree out-of-band who
     *  goes first. Existing tests run two engines with opposite roles. */
    enum class Role { Initiator, Responder }

    /**
     * One sync round. [role] determines who sends first at each step.
     * Symmetric send-first (the v0 behaviour) deadlocks on transports
     * that apply back-pressure to writes — Tor TCP under congestion or
     * a future Reticulum/LoRa link will block on send(). BLE happens
     * to be non-blocking with the current per-Link outboundSink
     * Channel, but relying on that is the same kind of bug the
     * messaging `exchangeReadReceipts` symmetric-send shape just
     * surfaced. Role-asymmetric pairs are robust to either transport.
     */
    suspend fun runSession(
        link: Link,
        community: ByteArray,
        role: Role = Role.Initiator,
    ): Result {
        val myHave = repository.haveSet(community)
        val peerHave: SyncMessage.HaveSet
        if (role == Role.Initiator) {
            link.sendMessage(SyncMessage.HaveSet(community, myHave))
            peerHave = link.receiveMessage<SyncMessage.HaveSet>(community)
        } else {
            peerHave = link.receiveMessage<SyncMessage.HaveSet>(community)
            link.sendMessage(SyncMessage.HaveSet(community, myHave))
        }

        val mineSet = myHave.toHashSet()
        val want = peerHave.keys.filter { it !in mineSet }
        val peerWant: SyncMessage.Want
        if (role == Role.Initiator) {
            link.sendMessage(SyncMessage.Want(community, want))
            peerWant = link.receiveMessage<SyncMessage.Want>(community)
        } else {
            peerWant = link.receiveMessage<SyncMessage.Want>(community)
            link.sendMessage(SyncMessage.Want(community, want))
        }

        val push = repository.envelopesByKeys(community, peerWant.keys)
        val peerPush: SyncMessage.Push
        if (role == Role.Initiator) {
            link.sendMessage(SyncMessage.Push(push))
            peerPush = link.receiveMessage<SyncMessage.Push>(community = null)
        } else {
            peerPush = link.receiveMessage<SyncMessage.Push>(community = null)
            link.sendMessage(SyncMessage.Push(push))
        }
        var rejected = 0
        for (row in peerPush.rows) {
            // Trust the engine's contract: only rows for `community`. If
            // a peer sneaks a row tagged for another community in here,
            // drop it on the floor.
            if (!row.community.contentEquals(community)) {
                rejected++
                continue
            }
            if (!repository.ingest(row)) rejected++
        }
        return Result(
            sent = push.size,
            received = peerPush.rows.size,
            rejected = rejected,
        )
    }

    private suspend fun Link.sendMessage(msg: SyncMessage) {
        val bytes = SyncMessage.encode(msg)
        require(bytes.size <= SyncMessage.MAX_FRAME_BYTES) {
            "Sync message too large for a single frame: ${bytes.size} > " +
                "${SyncMessage.MAX_FRAME_BYTES}. v0 sends single-frame messages " +
                "only; pagination is a future sprint."
        }
        send(bytes)
    }

    /**
     * Generic-erased receive. The expected message type is checked at
     * call time; mismatches throw. [community] is checked when present
     * (Push doesn't carry community at the message level — each row does).
     */
    private suspend inline fun <reified T : SyncMessage> Link.receiveMessage(
        community: ByteArray?,
    ): T {
        val frame = incoming().firstOrNull()
            ?: error("Peer closed the link before sending the expected message")
        val decoded = SyncMessage.decode(frame)
        require(decoded is T) {
            "Expected ${T::class.simpleName}, got ${decoded::class.simpleName}"
        }
        if (community != null) {
            val msgCommunity = when (decoded) {
                is SyncMessage.HaveSet -> decoded.community
                is SyncMessage.Want -> decoded.community
                is SyncMessage.Push -> null
            }
            require(msgCommunity == null || msgCommunity.contentEquals(community)) {
                "Peer ${T::class.simpleName} is for a different community"
            }
        }
        return decoded
    }
}
