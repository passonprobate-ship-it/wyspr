package com.keystone.feature.messaging.mailbox

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.MailboxStoredEntity
import com.keystone.core.identity.PublicKey
import com.keystone.core.trust.TrustGraphService
import com.keystone.core.ui.settings.MailboxSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-side handlers for the mailbox protocol.
 *
 * Phase 2: handlers are isolated (verify + store, query, delete,
 * sweep). Phase 3 will wire them into [com.keystone.feature.messaging.sync.MessageSyncService]
 * so a Noise session carries both messaging AND mailbox traffic.
 *
 * The handlers assume the caller has already established a Noise
 * session and knows the authenticated peer's pubkey — that's the
 * `fromPub` parameter on each method. The host trusts the Noise
 * binding for peer identity; it does NOT re-verify "are you really
 * Alice" beyond what the Noise XX handshake already proved.
 *
 * Pull authorisation: the host returns envelopes addressed to the
 * Noise peer's pub, never an attacker-supplied target. Push
 * authorisation: signature on the envelope must match `fromPub`
 * AND `fromPub` must be in the host's trust graph (community-only
 * policy, baked default).
 *
 * Reject reasons for [handlePush] are surfaced via the [PushOutcome]
 * sealed type so the wire layer can choose how to communicate
 * "stored vs not stored" to the sender. Phase 3 maps these to a
 * concrete reply frame.
 */
@Singleton
class MailboxHost @Inject constructor(
    private val database: KeystoneDatabase,
    private val sodium: LazySodiumAndroid,
    private val trustGraphService: TrustGraphService,
    private val settings: MailboxSettings,
) {

    /** Single source of truth on whether the host is enabled. UI subscribes. */
    val hostEnabled: StateFlow<Boolean> = settings.hostEnabled

    /**
     * Verify the push, check community membership, enforce the storage
     * cap (evict-oldest if needed), and persist.
     *
     * Returns the outcome; the wire layer turns this into the appropriate
     * response frame.
     */
    suspend fun handlePush(
        envelope: MailboxEnvelope,
        fromPub: PublicKey,
        now: Long,
        retentionSeconds: Long = MailboxEnvelope.DEFAULT_RETENTION_SECONDS,
    ): PushOutcome = withContext(Dispatchers.IO) {
        if (!settings.hostEnabled.value) {
            return@withContext PushOutcome.NotHosting
        }
        // Outer signature must match the claimed sender (== the Noise
        // peer). This catches both "I forged Alice's signature" (bad
        // sig) and "Alice signed this but Bob is pushing it" (sender
        // mismatch).
        if (!envelope.fromPub.bytes.contentEquals(fromPub.bytes)) {
            Log.w(TAG, "push: envelope.fromPub does not match Noise peer")
            return@withContext PushOutcome.Unauthorised
        }
        if (!envelope.verify(sodium)) {
            Log.w(TAG, "push: envelope signature verification failed")
            return@withContext PushOutcome.BadSignature
        }
        if (!isInCommunity(fromPub)) {
            Log.w(TAG, "push: sender ${fromPub.shortHex()} not in trust graph")
            return@withContext PushOutcome.Unauthorised
        }
        // CRITICAL — the recipient must also be in our trust graph.
        // Without this, any community member can use us as free
        // storage for arbitrary out-of-community pubs, and a
        // malicious host that gets bound by a peer can hoover up
        // who→who/when/size traffic-analysis data for the whole
        // network. Hosting policy is: we only hold mail for people
        // we know.
        if (!isInCommunity(envelope.toPub)) {
            Log.w(TAG, "push: recipient ${envelope.toPub.shortHex()} not in trust graph")
            return@withContext PushOutcome.Unauthorised
        }

        ensureOpen()
        val cap = settings.storageCapBytes.value
        val incomingSize = envelope.ciphertext.size
        if (incomingSize > cap) {
            // A single envelope larger than the entire cap is bogus —
            // don't even try to evict. Should be impossible given the
            // MessageEnvelope body cap upstream, but defence-in-depth.
            Log.w(TAG, "push: envelope size $incomingSize > storage cap $cap; rejecting")
            return@withContext PushOutcome.Rejected
        }
        val evictIds = computeEvictionList(needed = incomingSize.toLong(), cap = cap)
        if (evictIds.isNotEmpty()) {
            Log.i(TAG, "evict: dropping ${evictIds.size} oldest rows to make room")
        }

        val entity = MailboxStoredEntity(
            envelopeId = envelope.id,
            toPub = envelope.toPub.bytes,
            fromPub = envelope.fromPub.bytes,
            ciphertext = envelope.ciphertext,
            signature = envelope.signature,
            sizeBytes = incomingSize,
            createdAt = envelope.createdAt,
            expiresAt = now + retentionSeconds,
        )
        // Single Room transaction so two concurrent pushes can't both
        // observe "cap fits, no eviction" and then both insert past
        // the cap. IGNORE on conflict at the insert level handles the
        // duplicate-envelope case.
        database.mailboxStoredDao.evictAndInsert(evictIds, entity)
        PushOutcome.Stored(envelopeId = envelope.id)
    }

    /**
     * Return every envelope held for [forPub] with `created_at >
     * sinceCursor`. Bounded to [MailboxFrame.MAX_BATCH] rows — the
     * recipient re-pulls until empty.
     *
     * Rows are reconstructed into byte-identical [MailboxEnvelope]s
     * using the stored signature. The recipient verifies the inner
     * `MessageEnvelope` signature on unseal — the outer signature is
     * defence-in-depth.
     */
    suspend fun handlePull(
        forPub: PublicKey,
        sinceCursor: Long,
    ): List<MailboxEnvelope> = withContext(Dispatchers.IO) {
        if (!settings.hostEnabled.value) return@withContext emptyList()
        ensureOpen()
        database.mailboxStoredDao
            .forRecipientSince(forPub.bytes, sinceCursor, MailboxFrame.MAX_BATCH)
            .map { row -> row.toEnvelope() }
    }

    private fun MailboxStoredEntity.toEnvelope(): MailboxEnvelope = MailboxEnvelope(
        id = envelopeId,
        toPub = PublicKey(toPub),
        fromPub = PublicKey(fromPub),
        createdAt = createdAt,
        ciphertext = ciphertext,
        signature = signature,
    )

    /**
     * Delete the rows the recipient just acked. Only rows addressed
     * to [forPub] are eligible — this prevents a malicious peer from
     * deleting another recipient's mail.
     */
    suspend fun handleAck(
        ids: List<ByteArray>,
        forPub: PublicKey,
    ): Int = withContext(Dispatchers.IO) {
        if (!settings.hostEnabled.value) return@withContext 0
        if (ids.isEmpty()) return@withContext 0
        ensureOpen()
        // Enforce the to_pub == forPub guard so a peer can only delete
        // their own mail. Projection-only query avoids loading ciphertext.
        val heldIds = database.mailboxStoredDao.envelopeIdsForRecipient(forPub.bytes)
            .asSequence()
            .map { it.toList() }
            .toHashSet()
        val deletable = ids.filter { it.toList() in heldIds }
        if (deletable.isEmpty()) return@withContext 0
        database.mailboxStoredDao.deleteIds(deletable)
        deletable.size
    }

    /**
     * Drop expired rows. Called hourly by the host scheduler and once
     * on host start. Idempotent. Runs even if hosting is off — a
     * recently-disabled host should still purge old material rather
     * than retain it indefinitely.
     */
    suspend fun sweepExpired(nowSeconds: Long): Int = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxStoredDao.expireBefore(nowSeconds)
    }

    /**
     * Wipe everything we're holding. Called from the "Stop / Purge"
     * action on the mailbox screen.
     */
    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxStoredDao.purgeAll()
    }

    // ---- internals ----

    /** Returns the envelope ids that must be deleted so [needed] bytes
     *  fit under [cap]. Uses the projection query so we don't load
     *  ciphertext BLOBs just to make an eviction decision. */
    private suspend fun computeEvictionList(needed: Long, cap: Long): List<ByteArray> {
        val all = database.mailboxStoredDao.allOldestFirstForEviction()
        var currentTotal = all.sumOf { it.sizeBytes.toLong() }
        if (currentTotal + needed <= cap) return emptyList()

        val evictIds = ArrayList<ByteArray>()
        for (row in all) {
            if (currentTotal + needed <= cap) break
            evictIds.add(row.envelopeId)
            currentTotal -= row.sizeBytes
        }
        return evictIds
    }

    private suspend fun isInCommunity(peerPub: PublicKey): Boolean {
        val graph = trustGraphService.snapshot() ?: return false
        return graph.snapshot().edges.any { edge ->
            edge.from.bytes.contentEquals(peerPub.bytes) ||
                edge.to.bytes.contentEquals(peerPub.bytes)
        }
    }

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }

    /** Result of a push attempt. The wire layer maps these to reply frames. */
    sealed interface PushOutcome {
        /** Envelope is now in the mailbox; sender may forget about it. */
        data class Stored(val envelopeId: ByteArray) : PushOutcome
        /** Sender isn't in the host's trust graph or sig didn't match. */
        data object Unauthorised : PushOutcome
        /** Outer signature failed verification. */
        data object BadSignature : PushOutcome
        /** Envelope larger than cap, or some other policy refusal. */
        data object Rejected : PushOutcome
        /** Host has the toggle off. Sender should pick a different mailbox. */
        data object NotHosting : PushOutcome
    }

    private companion object {
        const val TAG = "MailboxHost"
        fun PublicKey.shortHex(): String =
            bytes.take(4).joinToString("") { "%02x".format(it) } + "…"
    }
}
