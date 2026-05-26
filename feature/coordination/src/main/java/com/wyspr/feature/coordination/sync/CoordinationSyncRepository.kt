package com.wyspr.feature.coordination.sync

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.CoordinationEventEntity
import com.wyspr.core.database.entities.CoordinationRsvpEntity
import com.wyspr.feature.coordination.EventEnvelope
import com.wyspr.feature.coordination.RsvpEnvelope
import com.wyspr.feature.coordination.verify

class CoordinationSyncRepository(private val communityId: ByteArray) {

    // ── Events ──────────────────────────────────────────────────────────

    suspend fun eventHaveSet(database: WysprDatabase): List<Pair<ByteArray, Long>> =
        database.coordinationEventDao.allForCommunitySnapshot(communityId)
            .map { it.id to it.createdAt }

    suspend fun eventWant(
        database: WysprDatabase,
        theirSet: List<Pair<ByteArray, Long>>,
    ): List<Pair<ByteArray, Long>> {
        val mine = database.coordinationEventDao.allForCommunitySnapshot(communityId)
            .associateBy({ it.id.toList() }, { it.createdAt })
        return theirSet.filter { (id, theirCreatedAt) ->
            val myCreatedAt = mine[id.toList()]
            myCreatedAt == null || myCreatedAt < theirCreatedAt
        }
    }

    suspend fun eventFetch(
        database: WysprDatabase,
        keys: List<Pair<ByteArray, Long>>,
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>(keys.size)
        for ((id, _) in keys) {
            val entity = database.coordinationEventDao.byId(id) ?: continue
            out += entityToEventWire(entity)
        }
        return out
    }

    suspend fun eventIngest(
        database: WysprDatabase,
        wireEvent: ByteArray,
        sodium: LazySodiumAndroid,
    ): Boolean {
        val env = runCatching { EventEnvelope.fromWire(wireEvent) }.getOrNull() ?: return false
        if (!env.communityId.contentEquals(communityId)) return false
        if (!env.verify(sodium)) return false

        val existing = database.coordinationEventDao.byId(env.id)
        if (existing != null) {
            if (!existing.creatorPub.contentEquals(env.creatorPub)) return false
            if (existing.createdAt >= env.createdAt) return false
            val updated = database.coordinationEventDao.updateIfNewer(
                id = env.id,
                title = env.titleString,
                description = env.descriptionString,
                location = env.locationString,
                startsAt = env.startsAt,
                endsAt = env.endsAt,
                createdAt = env.createdAt,
                status = env.status,
                signature = env.signature,
            )
            return updated > 0
        }

        database.coordinationEventDao.insert(
            CoordinationEventEntity(
                id = env.id,
                creatorPub = env.creatorPub,
                communityId = env.communityId,
                title = env.titleString,
                description = env.descriptionString,
                location = env.locationString,
                startsAt = env.startsAt,
                endsAt = env.endsAt,
                createdAt = env.createdAt,
                status = env.status,
                signature = env.signature,
            )
        )
        return true
    }

    // ── RSVPs ───────────────────────────────────────────────────────────

    suspend fun rsvpHaveSet(database: WysprDatabase): List<Pair<ByteArray, ByteArray>> =
        database.coordinationRsvpDao.allForCommunity(communityId)
            .map { it.eventId to it.responderPub }

    suspend fun rsvpWant(
        database: WysprDatabase,
        theirSet: List<Pair<ByteArray, ByteArray>>,
    ): List<Pair<ByteArray, ByteArray>> {
        val mine = database.coordinationRsvpDao.allForCommunity(communityId)
            .map { Pair(it.eventId.toList(), it.responderPub.toList()) }
            .toSet()
        return theirSet.filter { (eventId, responderPub) ->
            Pair(eventId.toList(), responderPub.toList()) !in mine
        }
    }

    suspend fun rsvpFetch(
        database: WysprDatabase,
        keys: List<Pair<ByteArray, ByteArray>>,
    ): List<ByteArray> {
        val all = database.coordinationRsvpDao.allForCommunity(communityId)
        val lookup = all.associateBy {
            Pair(it.eventId.toList(), it.responderPub.toList())
        }
        val out = ArrayList<ByteArray>(keys.size)
        for ((eventId, responderPub) in keys) {
            val entity = lookup[Pair(eventId.toList(), responderPub.toList())] ?: continue
            out += entityToRsvpWire(entity)
        }
        return out
    }

    suspend fun rsvpIngest(
        database: WysprDatabase,
        wireRsvp: ByteArray,
        sodium: LazySodiumAndroid,
    ): Boolean {
        val env = runCatching { RsvpEnvelope.fromWire(wireRsvp) }.getOrNull() ?: return false
        if (!env.communityId.contentEquals(communityId)) return false
        if (!env.verify(sodium)) return false

        database.coordinationRsvpDao.insert(
            CoordinationRsvpEntity(
                eventId = env.eventId,
                responderPub = env.responderPub,
                communityId = env.communityId,
                status = env.status,
                createdAt = env.createdAt,
                signature = env.signature,
            )
        )
        database.coordinationRsvpDao.updateIfNewer(
            eventId = env.eventId,
            responderPub = env.responderPub,
            status = env.status,
            createdAt = env.createdAt,
            signature = env.signature,
        )
        return true
    }

    private fun entityToEventWire(e: CoordinationEventEntity): ByteArray {
        val env = EventEnvelope(
            version = EventEnvelope.VERSION,
            id = e.id,
            creatorPub = e.creatorPub,
            communityId = e.communityId,
            title = e.title.encodeToByteArray(),
            description = e.description?.encodeToByteArray(),
            location = e.location?.encodeToByteArray(),
            startsAt = e.startsAt,
            endsAt = e.endsAt,
            createdAt = e.createdAt,
            status = e.status,
            signature = e.signature,
        )
        return env.wireBytes()
    }

    private fun entityToRsvpWire(r: CoordinationRsvpEntity): ByteArray {
        val env = RsvpEnvelope(
            version = RsvpEnvelope.VERSION,
            eventId = r.eventId,
            responderPub = r.responderPub,
            communityId = r.communityId,
            status = r.status,
            createdAt = r.createdAt,
            signature = r.signature,
        )
        return env.wireBytes()
    }
}
