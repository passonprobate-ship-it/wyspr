package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.Link
import kotlinx.coroutines.flow.firstOrNull

/**
 * Run one revocation anti-entropy round over [link].
 *
 * Protocol (same structure as [com.wyspr.core.sync.SyncEngine]):
 *
 *     A                    B
 *     |── HaveSet(0x31) ──►|
 *     |◄── HaveSet(0x31) ──|
 *     |── Want(0x32) ─────►|   (= peer's set − ours)
 *     |◄── Want(0x32) ─────|
 *     |── Push(0x33) ─────►|   (= full wire certs peer wants)
 *     |◄── Push(0x33) ─────|
 *     ↓                    ↓
 *     ingest each received cert
 *
 * The round is symmetric: both peers send and receive the same sequence.
 * Failure modes all surface as exceptions (link loss, malformed CBOR,
 * unexpected message type) — the caller is responsible for quarantine
 * decisions after repeated failures.
 *
 * @return Number of revocation certificates received and accepted.
 */
suspend fun runRevocationSyncRound(
    link: Link,
    database: WysprDatabase,
    communityId: ByteArray,
    trustGraph: TrustGraph?,
    sodium: LazySodiumAndroid,
): Int {
    val repository = RevocationSyncRepository(communityId)

    // Step 1 — send our HaveSet
    val myHave = repository.haveSet(database)
    link.sendMessage(RevocationSyncMessage.HaveSet(myHave))

    // Step 2 — receive peer's HaveSet
    val peerHave = link.receiveMessage<RevocationSyncMessage.HaveSet>()

    // Step 3 — send Want (peer's pairs we don't have)
    val want = repository.want(database, peerHave.pairs)
    link.sendMessage(RevocationSyncMessage.Want(want))

    // Step 4 — receive peer's Want
    val peerWant = link.receiveMessage<RevocationSyncMessage.Want>()

    // Step 5 — send Push with the full wire certs peer wants
    val push = repository.fetch(database, peerWant.pairs)
    link.sendMessage(RevocationSyncMessage.Push(push))

    // Step 6 — receive peer's Push, ingest each cert
    val peerPush = link.receiveMessage<RevocationSyncMessage.Push>()
    var accepted = 0
    for (wireCert in peerPush.certs) {
        if (repository.ingest(database, wireCert, trustGraph, sodium)) {
            accepted++
        }
    }
    return accepted
}

// ── private Link helpers (same pattern as SyncEngine) ─────────────────

private suspend fun Link.sendMessage(msg: RevocationSyncMessage) {
    val bytes = RevocationSyncMessage.encode(msg)
    require(bytes.size <= RevocationSyncMessage.MAX_FRAME_BYTES) {
        "Revocation sync message too large: ${bytes.size} > " +
            "${RevocationSyncMessage.MAX_FRAME_BYTES}"
    }
    send(bytes)
}

@Suppress("UNCHECKED_CAST")
private suspend inline fun <reified T : RevocationSyncMessage> Link.receiveMessage(): T {
    val frame = incoming().firstOrNull()
        ?: error("Peer closed the link before sending the expected revocation message")
    val decoded = RevocationSyncMessage.decode(frame)
    require(decoded is T) {
        "Expected ${T::class.simpleName}, got ${decoded::class.simpleName}"
    }
    return decoded as T
}
