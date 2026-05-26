package com.wyspr.feature.coordination.sync

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.Link
import kotlinx.coroutines.flow.firstOrNull

suspend fun runCoordinationSyncRound(
    link: Link,
    database: WysprDatabase,
    communityId: ByteArray,
    sodium: LazySodiumAndroid,
): Int {
    val repository = CoordinationSyncRepository(communityId)
    var accepted = 0

    // ── Event sub-round ─────────────────────────────────────────────
    val myEventHave = repository.eventHaveSet(database)
    link.sendCoord(CoordinationSyncMessage.EventHaveSet(myEventHave))

    val peerEventHave = link.receiveCoord<CoordinationSyncMessage.EventHaveSet>()

    val eventWant = repository.eventWant(database, peerEventHave.pairs)
    link.sendCoord(CoordinationSyncMessage.EventWant(eventWant))

    val peerEventWant = link.receiveCoord<CoordinationSyncMessage.EventWant>()

    val eventPush = repository.eventFetch(database, peerEventWant.pairs)
    link.sendCoord(CoordinationSyncMessage.EventPush(eventPush))

    val peerEventPush = link.receiveCoord<CoordinationSyncMessage.EventPush>()
    for (wireEvent in peerEventPush.envelopes) {
        if (repository.eventIngest(database, wireEvent, sodium)) accepted++
    }

    // ── RSVP sub-round ──────────────────────────────────────────────
    val myRsvpHave = repository.rsvpHaveSet(database)
    link.sendCoord(CoordinationSyncMessage.RsvpHaveSet(myRsvpHave))

    val peerRsvpHave = link.receiveCoord<CoordinationSyncMessage.RsvpHaveSet>()

    val rsvpWant = repository.rsvpWant(database, peerRsvpHave.pairs)
    link.sendCoord(CoordinationSyncMessage.RsvpWant(rsvpWant))

    val peerRsvpWant = link.receiveCoord<CoordinationSyncMessage.RsvpWant>()

    val rsvpPush = repository.rsvpFetch(database, peerRsvpWant.pairs)
    link.sendCoord(CoordinationSyncMessage.RsvpPush(rsvpPush))

    val peerRsvpPush = link.receiveCoord<CoordinationSyncMessage.RsvpPush>()
    for (wireRsvp in peerRsvpPush.envelopes) {
        if (repository.rsvpIngest(database, wireRsvp, sodium)) accepted++
    }

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
