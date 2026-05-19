package com.keystone.core.sync

import com.keystone.core.transport.Link
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

    suspend fun runSession(link: Link, community: ByteArray): Result {
        // Step 1: send our HaveSet
        val myHave = repository.haveSet(community)
        link.sendMessage(SyncMessage.HaveSet(community, myHave))

        // Step 2: receive peer's HaveSet
        val peerHave = link.receiveMessage<SyncMessage.HaveSet>(community)

        // Step 3: send what we want (peer.have − mine)
        val mineSet = myHave.toHashSet()
        val want = peerHave.keys.filter { it !in mineSet }
        link.sendMessage(SyncMessage.Want(community, want))

        // Step 4: receive what the peer wants
        val peerWant = link.receiveMessage<SyncMessage.Want>(community)

        // Step 5: send rows for the keys the peer wants
        val push = repository.envelopesByKeys(community, peerWant.keys)
        link.sendMessage(SyncMessage.Push(push))

        // Step 6: receive their push, ingest each row
        val peerPush = link.receiveMessage<SyncMessage.Push>(community = null)
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
