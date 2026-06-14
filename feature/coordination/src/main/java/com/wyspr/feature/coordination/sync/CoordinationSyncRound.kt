package com.wyspr.feature.coordination.sync

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.CoordinationNotifier
import com.wyspr.core.transport.Link
import kotlinx.coroutines.flow.firstOrNull

private const val TAG = "CoordSync"

private fun ByteArray.hex8(): String =
    take(4).joinToString("") { "%02x".format(it) }

suspend fun runCoordinationSyncRound(
    link: Link,
    database: WysprDatabase,
    communityId: ByteArray,
    sodium: LazySodiumAndroid,
    notifier: CoordinationNotifier = CoordinationNotifier.NoOp,
): Int {
    val repository = CoordinationSyncRepository(communityId)
    var accepted = 0

    Log.d(TAG, "round start: community=${communityId.hex8()}")

    // ── Event sub-round ─────────────────────────────────────────────
    val myEventHave = repository.eventHaveSet(database)
    Log.d(TAG, "event: myHaveSet=${myEventHave.size} ${myEventHave.map { it.first.hex8() }}")
    link.sendCoord(CoordinationSyncMessage.EventHaveSet(myEventHave))

    val peerEventHave = link.receiveCoord<CoordinationSyncMessage.EventHaveSet>()
    Log.d(TAG, "event: peerHaveSet=${peerEventHave.pairs.size} ${peerEventHave.pairs.map { it.first.hex8() }}")

    val eventWant = repository.eventWant(database, peerEventHave.pairs)
    Log.d(TAG, "event: iWant=${eventWant.size} ${eventWant.map { it.first.hex8() }}")
    link.sendCoord(CoordinationSyncMessage.EventWant(eventWant))

    val peerEventWant = link.receiveCoord<CoordinationSyncMessage.EventWant>()
    Log.d(TAG, "event: peerWants=${peerEventWant.pairs.size} ${peerEventWant.pairs.map { it.first.hex8() }}")

    val eventPush = repository.eventFetch(database, peerEventWant.pairs)
    Log.d(TAG, "event: iPush=${eventPush.size} envelopes")
    link.sendCoord(CoordinationSyncMessage.EventPush(eventPush))

    val peerEventPush = link.receiveCoord<CoordinationSyncMessage.EventPush>()
    Log.d(TAG, "event: peerPushed=${peerEventPush.envelopes.size} envelopes, ingesting…")
    for (wireEvent in peerEventPush.envelopes) {
        when (val r = repository.eventIngest(database, wireEvent, sodium)) {
            is EventIngestResult.Accepted -> {
                accepted++
                Log.d(TAG, "event: ingested ${r.id.hex8()} \"${r.title}\" (new=${r.isNew})")
                if (r.isNew) runCatching { notifier.notifyReceivedEvent(r.id, r.title) }
            }
            is EventIngestResult.Rejected ->
                Log.d(TAG, "event: REJECTED — ${r.reason}")
        }
    }

    // ── RSVP sub-round ──────────────────────────────────────────────
    val myRsvpHave = repository.rsvpHaveSet(database)
    Log.d(TAG, "rsvp: myHaveSet=${myRsvpHave.size}")
    link.sendCoord(CoordinationSyncMessage.RsvpHaveSet(myRsvpHave))

    val peerRsvpHave = link.receiveCoord<CoordinationSyncMessage.RsvpHaveSet>()

    val rsvpWant = repository.rsvpWant(database, peerRsvpHave.pairs)
    link.sendCoord(CoordinationSyncMessage.RsvpWant(rsvpWant))

    val peerRsvpWant = link.receiveCoord<CoordinationSyncMessage.RsvpWant>()

    val rsvpPush = repository.rsvpFetch(database, peerRsvpWant.pairs)
    Log.d(TAG, "rsvp: iPush=${rsvpPush.size} envelopes")
    link.sendCoord(CoordinationSyncMessage.RsvpPush(rsvpPush))

    val peerRsvpPush = link.receiveCoord<CoordinationSyncMessage.RsvpPush>()
    Log.d(TAG, "rsvp: peerPushed=${peerRsvpPush.envelopes.size} envelopes, ingesting…")
    for (wireRsvp in peerRsvpPush.envelopes) {
        if (repository.rsvpIngest(database, wireRsvp, sodium)) accepted++
    }

    Log.d(TAG, "round done: accepted=$accepted")
    return accepted
}

private suspend fun Link.sendCoord(msg: CoordinationSyncMessage) {
    val bytes = CoordinationSyncMessage.encode(msg)
    require(bytes.size <= CoordinationSyncMessage.MAX_FRAME_BYTES) {
        "Coordination sync message too large: ${bytes.size} > ${CoordinationSyncMessage.MAX_FRAME_BYTES}"
    }
    send(bytes)
}

@Suppress("UNCHECKED_CAST")
private suspend inline fun <reified T : CoordinationSyncMessage> Link.receiveCoord(): T {
    val frame = incoming().firstOrNull()
        ?: error("Peer closed the link before sending the expected coordination message")
    val decoded = CoordinationSyncMessage.decode(frame)
    require(decoded is T) {
        "Expected ${T::class.simpleName}, got ${decoded::class.simpleName}"
    }
    return decoded as T
}
