package com.wyspr.feature.messaging.mailbox

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.MailboxBindingEntity
import com.wyspr.core.identity.PublicKey
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
    private val database: WysprDatabase,
) {

    /**
     * Persist (or refresh) the local user's binding for a single host.
     * Composite-PK upsert: adding a NEW host doesn't evict existing
     * bindings for the same owner — pass [clearOwn] first to replace
     * the whole set. Caller is responsible for having signed the cert.
     */
    suspend fun addOwn(binding: MailboxBinding) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.upsert(binding.toEntity())
    }

    /**
     * @deprecated Multi-host (schema v14): name was misleading; use
     * [addOwn]. Kept as a thin alias because every call site used to
     * mean "register a mailbox," not "replace all my mailboxes."
     */
    @Deprecated("Use addOwn — semantics in multi-host world.", ReplaceWith("addOwn(binding)"))
    suspend fun setOwn(binding: MailboxBinding) = addOwn(binding)

    /**
     * Remove every mailbox the local user has configured. UI exposes
     * this as "stop using mailboxes."
     */
    suspend fun clearOwn(ownPub: PublicKey) = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.deleteForOwner(ownPub.bytes)
    }

    /** Remove exactly one mailbox host from the local user's set. */
    suspend fun removeOwnHost(ownPub: PublicKey, mailboxPub: PublicKey) =
        withContext(Dispatchers.IO) {
            ensureOpen()
            database.mailboxBindingDao.deleteForOwnerHost(ownPub.bytes, mailboxPub.bytes)
        }

    /** The local user's bindings (one per delegated host). Empty list when none. */
    suspend fun myBindings(ownPub: PublicKey): List<MailboxBinding> = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.forOwner(ownPub.bytes).map { it.toBinding() }
    }

    /** Reactive variant — the UI subscribes to render the list of mailboxes. */
    fun myBindingsFlow(ownPub: PublicKey): Flow<List<MailboxBinding>> =
        database.mailboxBindingDao.forOwnerFlow(ownPub.bytes).map { rows -> rows.map { it.toBinding() } }

    /**
     * Look up a peer's bindings (learned via sync). Returns every
     * non-expired binding the peer has published; empty list if we
     * don't know any or they've all expired.
     *
     * Senders iterate the returned list and push to each reachable
     * host. [nowSeconds] defaults to the wall clock; tests override.
     */
    suspend fun forOwner(
        ownerPub: PublicKey,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<MailboxBinding> = withContext(Dispatchers.IO) {
        ensureOpen()
        database.mailboxBindingDao.forOwner(ownerPub.bytes)
            .filter { it.expiresAt > nowSeconds }
            .map { it.toBinding() }
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
        // Multi-host: check whether an existing (owner, host) row is
        // already newer. We don't downgrade the cert for that specific
        // host. Bindings for OTHER hosts the same owner publishes are
        // untouched — that's the whole point of multi-host.
        val existing = database.mailboxBindingDao.forOwner(binding.ownerPub.bytes)
            .firstOrNull { it.mailboxPub.contentEquals(binding.mailboxPub.bytes) }
        if (existing != null && existing.createdAt >= binding.createdAt) {
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
