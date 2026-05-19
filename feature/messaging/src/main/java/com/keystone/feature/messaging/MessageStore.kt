package com.keystone.feature.messaging

import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.PublicKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Application-level service that wraps the [MessageDao] with the
 * domain-shaped operations the messaging feature needs: send a
 * fresh outbound message, observe the thread for a peer, observe
 * the conversation-list summary, mark inbound messages as read.
 *
 * Sprint 1 is local-only — `send` queues a message as `pending` in
 * the DB. Sprint 2 will add a sync push to a paired peer when both
 * devices are in BLE range, transitioning rows from `pending` to
 * `sent` and (on ACK) `delivered`.
 */
@Singleton
class MessageStore @Inject constructor(
    private val database: KeystoneDatabase,
    private val keystore: KeystoreManager,
) {

    /** Reactive view of a single thread (oldest first). */
    fun threadFlow(peerPub: PublicKey): Flow<List<MessageEntity>> {
        ensureOpen()
        return database.messageDao.threadFlow(peerPub.bytes)
    }

    /** Reactive view of the most recent message per thread. */
    fun latestPerThreadFlow(): Flow<List<MessageEntity>> {
        ensureOpen()
        return database.messageDao.latestPerThreadFlow()
    }

    /**
     * Compose and persist a new outbound message. The envelope is
     * signed by the local keystore now (Sprint 1) so that when
     * Sprint 2 ships networking, the bytes the peer receives are
     * the bytes the sender signed — no retroactive re-signing.
     */
    suspend fun send(
        toPub: PublicKey,
        body: String,
        clockSeconds: Long = System.currentTimeMillis() / 1000,
    ): MessageEntity {
        ensureOpen()
        val ownPub = PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
        val envelope = MessageEnvelope.issue(
            keystore = keystore,
            fromPub = ownPub,
            toPub = toPub,
            body = body,
            now = clockSeconds,
        )
        val entity = MessageEntity(
            id = envelope.id,
            threadPub = toPub.bytes,
            fromPub = envelope.fromPub.bytes,
            toPub = envelope.toPub.bytes,
            createdAt = envelope.createdAt,
            receivedAt = null,
            body = envelope.body,
            status = STATUS_PENDING,
            signature = envelope.signature,
        )
        database.messageDao.upsert(entity)
        return entity
    }

    /** Sprint 2 — push a [MessageEntity] over a Noise link. */
    suspend fun markSent(id: ByteArray) {
        ensureOpen()
        database.messageDao.updateStatus(id, STATUS_SENT)
    }

    /** Sprint 2 — peer ACKed. */
    suspend fun markDelivered(id: ByteArray) {
        ensureOpen()
        database.messageDao.updateStatus(id, STATUS_DELIVERED)
    }

    /**
     * Ingest a fully-verified inbound envelope. Stores with
     * status="received" and thread keyed on the SENDER's pubkey
     * (the local device is the recipient). Idempotent on id.
     */
    suspend fun ingest(envelope: MessageEnvelope, receivedAtSeconds: Long) {
        ensureOpen()
        val existing = database.messageDao.byId(envelope.id)
        if (existing != null) return
        val entity = MessageEntity(
            id = envelope.id,
            threadPub = envelope.fromPub.bytes,
            fromPub = envelope.fromPub.bytes,
            toPub = envelope.toPub.bytes,
            createdAt = envelope.createdAt,
            receivedAt = receivedAtSeconds,
            body = envelope.body,
            status = STATUS_RECEIVED,
            signature = envelope.signature,
        )
        database.messageDao.upsert(entity)
    }

    private fun ensureOpen() {
        // The Compose layer waits for openOnce() at app startup; if
        // someone reaches here before that completes, fail loudly
        // rather than silently miss messages.
        check(database.isOpen) { "KeystoneDatabase not open" }
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_RECEIVED = "received"
    }
}
