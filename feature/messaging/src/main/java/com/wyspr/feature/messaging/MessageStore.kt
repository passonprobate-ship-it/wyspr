package com.wyspr.feature.messaging

import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.MessageEntity
import com.wyspr.core.database.entities.ContactEntity
import com.wyspr.core.database.entities.ReactionEntity
import com.wyspr.core.identity.PublicKey
import com.wyspr.feature.messaging.disappear.DisappearPayload
import com.wyspr.feature.messaging.reactions.ReactionPayload
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
    private val database: WysprDatabase,
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
        val disappearAfter = database.contactDao.byPub(toPub.bytes)?.disappearAfter
        val expiresAt = if (disappearAfter != null && disappearAfter > 0) {
            clockSeconds + disappearAfter
        } else null

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
            expiresAt = expiresAt,
        )
        database.messageDao.upsert(entity)

        val reactionDecoded = ReactionPayload.decode(body)
        if (reactionDecoded != null) {
            applyReaction(ownPub.bytes, reactionDecoded, clockSeconds)
        }

        return entity
    }

    /**
     * Pending outbound messages from the local identity addressed to
     * a specific [peerPub]. Used by the sync engine to build a Push
     * frame on session establishment.
     */
    suspend fun pendingOutboundFor(ownPub: PublicKey, peerPub: PublicKey): List<MessageEntity> {
        ensureOpen()
        return database.messageDao.pendingOutboundFromTo(ownPub.bytes, peerPub.bytes)
    }

    suspend fun latestFromPeer(peerPub: PublicKey): MessageEntity? {
        ensureOpen()
        return database.messageDao.latestInThread(peerPub.bytes)
    }

    suspend fun unreadInboundFrom(peerPub: PublicKey): Int {
        ensureOpen()
        return database.messageDao.unreadInboundCount(peerPub.bytes)
    }

    /**
     * Inbound messages from [peerPub] the user has now viewed but
     * for which we haven't yet sent a read receipt to the sender.
     * Sprint 4 read-receipt path picks these up at sync time.
     */
    suspend fun pendingReadAckFor(peerPub: PublicKey): List<MessageEntity> {
        ensureOpen()
        return database.messageDao.pendingReadAckFor(peerPub.bytes)
    }

    /**
     * Flip every inbound from [peerPub] currently in "received" to
     * "received_viewed". Called from the ConversationViewModel when
     * the user opens the chat — they've now seen these messages.
     */
    suspend fun markInboundViewed(peerPub: PublicKey) {
        ensureOpen()
        val unreadIds = database.messageDao.unreadIdsFor(peerPub.bytes)
        if (unreadIds.isEmpty()) return
        database.messageDao.bulkTransitionStatus(
            ids = unreadIds,
            fromStatus = STATUS_RECEIVED,
            newStatus = STATUS_RECEIVED_VIEWED,
        )
    }

    /**
     * Mark the read receipt for [ids] as having been delivered to
     * the sender (transitioning to the final inbound terminal state).
     */
    suspend fun markReadAcked(ids: List<ByteArray>) {
        ensureOpen()
        if (ids.isEmpty()) return
        // One UPDATE for the whole list. The previous per-id loop ran
        // inside the sync round's critical section.
        database.messageDao.bulkTransitionStatus(
            ids = ids,
            fromStatus = STATUS_RECEIVED_VIEWED,
            newStatus = STATUS_RECEIVED_ACKED,
        )
    }

    /**
     * The peer told us they've read the outbound messages with
     * these ids — flip OUR records from "sent" to "read". Idempotent.
     */
    suspend fun applyPeerReadReceipts(ids: List<ByteArray>): List<ByteArray> {
        ensureOpen()
        if (ids.isEmpty()) return emptyList()
        // Bulk UPDATE that only flips rows currently in sent OR
        // delivered → read. Refusing pending/received protects against
        // a misbehaving peer trying to flip statuses on messages we
        // haven't sent yet or our own inbound. One round-trip instead
        // of N round-trips inside the sync engine's critical section.
        database.messageDao.bulkTransitionStatus2(
            ids = ids,
            fromStatusA = STATUS_SENT,
            fromStatusB = STATUS_DELIVERED,
            newStatus = STATUS_READ,
        )
        return database.messageDao.idsWithStatus(ids, STATUS_READ)
    }

    /** Sprint 2 — push a [MessageEntity] over a Noise link.
     *  Only transitions from 'pending' to 'sent' — avoids regressing
     *  from 'delivered' or 'read' if multiple sync rounds ack the
     *  same message. */
    suspend fun markSent(id: ByteArray) {
        ensureOpen()
        database.messageDao.bulkTransitionStatus(
            ids = listOf(id),
            fromStatus = STATUS_PENDING,
            newStatus = STATUS_SENT,
        )
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

        val reactionDecoded = ReactionPayload.decode(envelope.body)
        if (reactionDecoded != null) {
            applyReaction(envelope.fromPub.bytes, reactionDecoded, envelope.createdAt)
        }

        // Inbound disappear-setting messages are ignored — only the
        // local user controls their own disappearing timer via the UI.
        // A remote peer sending wyspr:disappear:N could otherwise
        // reduce the timer to weaken message retention unilaterally.
        val disappearDecoded = DisappearPayload.decode(envelope.body)
        if (disappearDecoded != null) {
            android.util.Log.w("MessageStore",
                "Ignoring inbound disappear-setting from peer " +
                    "(value=${disappearDecoded}s) — only local user controls timer")
        }

        val disappearAfter = database.contactDao.byPub(envelope.fromPub.bytes)?.disappearAfter
        val expiresAt = if (disappearAfter != null && disappearAfter > 0) {
            receivedAtSeconds + disappearAfter
        } else null

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
            expiresAt = expiresAt,
        )
        database.messageDao.upsert(entity)
    }

    private suspend fun applyDisappearSetting(peerPub: ByteArray, seconds: Long) {
        val existing = database.contactDao.byPub(peerPub)
        val timer = if (seconds <= 0) null else seconds
        database.contactDao.upsert(
            ContactEntity(
                peerPub = peerPub,
                displayName = existing?.displayName,
                notes = existing?.notes,
                disappearAfter = timer,
            ),
        )
    }

    suspend fun deleteExpiredMessages(): Int {
        ensureOpen()
        val now = System.currentTimeMillis() / 1000
        val count = database.messageDao.deleteExpired(now)
        if (count > 0) {
            database.walCheckpointTruncate()
        }
        return count
    }

    /**
     * Send a reaction to a specific message. The reaction is encoded
     * as a normal message with a `wyspr:react:` body. To retract,
     * pass an empty emoji string.
     */
    suspend fun sendReaction(
        toPub: PublicKey,
        targetMsgId: ByteArray,
        emoji: String,
        clockSeconds: Long = System.currentTimeMillis() / 1000,
    ): MessageEntity {
        val body = if (emoji.isEmpty()) {
            ReactionPayload.encodeRetract(targetMsgId)
        } else {
            ReactionPayload.encode(targetMsgId, emoji)
        }
        return send(toPub, body, clockSeconds)
    }

    /**
     * Persist a reaction decoded from an inbound (or outbound) message
     * body. Called after [ingest] when the body matches `wyspr:react:`.
     */
    suspend fun applyReaction(fromPub: ByteArray, decoded: ReactionPayload.Decoded, createdAt: Long) {
        ensureOpen()
        if (decoded.isRetract) {
            database.reactionDao.delete(decoded.targetMsgId, fromPub)
        } else {
            database.reactionDao.upsert(
                ReactionEntity(
                    msgId = decoded.targetMsgId,
                    fromPub = fromPub,
                    emoji = decoded.emoji,
                    createdAt = createdAt,
                ),
            )
        }
    }

    suspend fun search(query: String): List<MessageEntity> {
        ensureOpen()
        if (query.isBlank()) return emptyList()
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return database.messageDao.search(escaped)
    }

    fun reactionsForMessages(msgIds: List<ByteArray>): Flow<List<ReactionEntity>> {
        ensureOpen()
        // Room generates one SQL bind variable per id. SQLite's limit
        // is 999 variables; cap the input to stay safely under that.
        // In practice this is called per-screen with visible messages
        // only (typically < 100).
        val capped = if (msgIds.size > 900) msgIds.take(900) else msgIds
        return database.reactionDao.forMessagesFlow(capped)
    }

    private fun ensureOpen() {
        // The Compose layer waits for openOnce() at app startup; if
        // someone reaches here before that completes, fail loudly
        // rather than silently miss messages.
        check(database.isOpen) { "WysprDatabase not open" }
    }

    companion object {
        // Outbound terminal sequence: pending → sent → (delivered) → read
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
        // Inbound terminal sequence: received → received_viewed → received_acked
        const val STATUS_RECEIVED = "received"
        const val STATUS_RECEIVED_VIEWED = "received_viewed"
        const val STATUS_RECEIVED_ACKED = "received_acked"
    }
}
