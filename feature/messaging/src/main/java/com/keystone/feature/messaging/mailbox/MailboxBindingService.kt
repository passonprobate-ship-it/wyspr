package com.keystone.feature.messaging.mailbox

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.MailboxBindingEntity
import com.keystone.core.identity.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage facade for [MailboxBinding] certs.
 *
 * Two roles:
 *
 *  1. **Owning side** — when the user picks a mailbox, their device
 *     mints a binding cert signed by them and stores it via [setOwn].
 *     That stored cert is the source of truth for the local user's
 *     mailbox configuration. The cert is what we BROADCAST in every
 *     sync round so peers learn where to push asynchronous mail.
 *
 *  2. **Receiving side** — when a peer's binding arrives in a sync
 *     Push, [ingest] verifies the signature + expiry and persists.
 *     Senders then look up a recipient's mailbox via [forOwner] when
 *     direct delivery hasn't completed.
 *
 *  All cert bytes are stored verbatim in `mailbox_binding.cert_bytes`
 *  so re-broadcast is byte-identical to what the owner signed (a
 *  re-encode through our codec would also be byte-identical because
 *  CBOR is canonical, but storing the wire form is cheaper than
 *  recomputing it every sync round).
 */
@Singleton
class MailboxBindingService @Inject constructor(
    private val database: KeystoneDatabase,
) {

    /**
     * Persist the local user's binding. Caller is responsible for
     * having signed the cert with the local keystore — we don't
     * re-verify here (loopback verification is wasted cycles when
     * the keystore is the source of truth).
     */
    suspend fun setOwn(binding: MailboxBinding) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.upsert(binding.toEntity())
    }

    /**
     * Remove the local user's binding (user disabled their mailbox).
     */
    suspend fun clearOwn(ownPub: PublicKey) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.deleteForOwner(ownPub.bytes)
    }

    /** The local user's binding, or null if no mailbox configured. */
    suspend fun myBinding(ownPub: PublicKey): MailboxBinding? = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.forOwner(ownPub.bytes)?.toBinding()
    }

    /** Reactive variant — the UI subscribes to render "your mailbox is X." */
    fun myBindingFlow(ownPub: PublicKey): Flow<MailboxBinding?> =
        database.mailboxBindingDao.forOwnerFlow(ownPub.bytes).map { it?.toBinding() }

    /**
     * Look up a peer's binding (learned via sync). Null if not known
     * OR the cert has expired — senders shouldn't push to a 90-day-
     * expired mailbox just because the row is still on disk. [nowSeconds]
     * defaults to the wall clock; tests override.
     */
    suspend fun forOwner(
        ownerPub: PublicKey,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): MailboxBinding? = withContext(Dispatchers.IO) {
        ensureOpen()
        val row = database.mailboxBindingDao.forOwner(ownerPub.bytes) ?: return@withContext null
        if (row.expiresAt <= nowSeconds) return@withContext null
        row.toBinding()
    }

    /**
     * Verify and persist a binding received from a peer. Returns true
     * iff the cert was authenticated, within its validity window, came
     * from the owner themselves, and persisted. Returns false on any
     * failure (we never store an unverified cert).
     *
     * [peerPub] is the Noise-authenticated transport peer. Only the
     * owner of a binding can install it — without this check a
     * member with a single edge into the community could broadcast
     * forged bindings for any owner pub (with `createdAt = now`,
     * overwriting the legitimate binding via the newer-wins rule)
     * and silently redirect future pushes to attacker storage.
     *
     * Idempotent — replaying the same cert is a no-op. Newer certs
     * (later `createdAt`) overwrite older ones for the same owner
     * — that's how owners ROTATE their mailbox.
     */
    suspend fun ingest(
        binding: MailboxBinding,
        peerPub: PublicKey,
        sodium: LazySodiumAndroid,
        nowSeconds: Long,
        clockSkewSeconds: Long = DEFAULT_CLOCK_SKEW_SECONDS,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!binding.ownerPub.bytes.contentEquals(peerPub.bytes)) {
            Log.w(
                TAG,
                "ingest: peer ${peerPub.shortHex()} pushed a binding for a " +
                    "different owner ${binding.ownerPub.shortHex()}; refusing",
            )
            return@withContext false
        }
        if (!binding.verify(sodium, nowSeconds, clockSkewSeconds)) {
            Log.w(TAG, "ingest: binding for ${binding.ownerPub.shortHex()} failed verify")
            return@withContext false
        }
        ensureOpen()
        val existing = database.mailboxBindingDao.forOwner(binding.ownerPub.bytes)
        if (existing != null && existing.createdAt >= binding.createdAt) {
            // We already have this or a newer cert for the same owner.
            // Don't downgrade.
            return@withContext true
        }
        database.mailboxBindingDao.upsert(binding.toEntity())
        true
    }

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }

    private fun MailboxBinding.toEntity() = MailboxBindingEntity(
        ownerPub = ownerPub.bytes,
        mailboxPub = mailboxPub.bytes,
        mailboxOnion = mailboxOnion,
        certBytes = wireBytes(),
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    private fun MailboxBindingEntity.toBinding() = MailboxBinding.fromWire(certBytes)

    companion object {
        /**
         * Tolerance for clock skew between owner and verifier — same
         * value the trust-edge cert path uses. Mailbox certs are
         * issued by the owner's phone, ingested on the peer's phone,
         * and the two clocks drift by seconds in practice.
         */
        const val DEFAULT_CLOCK_SKEW_SECONDS = 60L * 60 // 1 hour
        private const val TAG = "MailboxBindingSvc"

        private fun PublicKey.shortHex(): String =
            bytes.take(4).joinToString("") { "%02x".format(it) } + "…"
    }
}
