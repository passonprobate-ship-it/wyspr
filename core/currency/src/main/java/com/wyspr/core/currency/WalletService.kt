package com.wyspr.core.currency

import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.AccountEntity
import com.wyspr.core.database.entities.CurrencyEnvelopeEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * The user-facing Gem wallet. CURRENCY.md §13.4.
 *
 * Sits between the UI / sync layer and the persisted ledger:
 *
 *   - On send: signs a new [Transfer] with the hardware-backed identity
 *     key, persists it as a verified envelope, and updates the local
 *     account cache.
 *   - On ingest: persists incoming envelopes the caller has already
 *     verified, detecting double-spends via the (sender, seq) primary
 *     key collision path. Updates the local account cache.
 *   - On read: returns cached balances and next-expected-seq.
 *   - On audit: recomputes the cache from raw envelopes via
 *     [LedgerReducer].
 *
 * Outbound sends are mutex-serialized — two concurrent sends from the
 * same device could otherwise both read seq=N and both sign with seq=N+1,
 * producing an accidental double-spend the slash detector would treat
 * as malicious.
 *
 * The account cache is convenience, not truth. The truth lives in
 * [currency_envelope]. If the cache drifts (crash mid-update, schema
 * upgrade, manual edit) call [recomputeBalances] to rebuild it.
 */
class WalletService(
    private val keystore: KeystoreManager,
    private val database: WysprDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {

    private val sendMutex = Mutex()

    // --------------------------------------------------------------------
    // Read surface
    // --------------------------------------------------------------------

    /** Cached balance for [account]. Returns 0 if the account is unknown. */
    suspend fun balance(account: AccountId): Long {
        ensureOpen()
        return database.accountDao.get(account.bytes)?.balanceCached ?: 0L
    }

    /**
     * Snapshot of the local identity: its account id plus its current
     * cached balance + next seq to use for an outbound transfer.
     */
    suspend fun me(): MyAccount {
        ensureOpen()
        val handle = keystore.loadOrCreateIdentityKey()
        val account = AccountId(handle.publicKey)
        val cached = database.accountDao.get(account.bytes)
        return MyAccount(
            account = account,
            balance = cached?.balanceCached ?: 0L,
            nextSeq = (cached?.seq ?: 0L) + 1,
            slashed = cached?.slashed ?: false,
        )
    }

    data class MyAccount(
        val account: AccountId,
        val balance: Long,
        val nextSeq: Long,
        val slashed: Boolean,
    )

    // --------------------------------------------------------------------
    // Send (outbound)
    // --------------------------------------------------------------------

    sealed interface SendOutcome
    data class Sent(val transfer: Transfer) : SendOutcome
    data class InsufficientFunds(val balance: Long, val requested: Long) : SendOutcome
    object SenderSlashed : SendOutcome
    object SelfTransfer : SendOutcome
    data class SendRejected(val reason: String) : SendOutcome

    /**
     * Sign and persist a new outbound transfer. Returns the signed
     * envelope on success; on failure, an outcome describing the cause.
     * Sender slashing and overspend are checked against the local cache;
     * if the cache is stale, the eventual recipient may also reject.
     */
    suspend fun send(
        community: ByteArray,
        recipient: AccountId,
        amount: Long,
        memoHash: ByteArray? = null,
    ): SendOutcome = sendMutex.withLock {
        require(amount >= 0) { "amount must be non-negative" }
        ensureOpen()

        val handle = keystore.loadOrCreateIdentityKey()
        val senderBytes = handle.publicKey
        val sender = AccountId(senderBytes)
        if (sender == recipient) return@withLock SelfTransfer

        val me = database.accountDao.get(senderBytes)
        if (me?.slashed == true) return@withLock SenderSlashed
        val balance = me?.balanceCached ?: 0L
        if (amount > balance) return@withLock InsufficientFunds(balance, amount)

        val seq = (me?.seq ?: 0L) + 1
        val nonce = ByteArray(Transfer.NONCE_LENGTH).also { random.nextBytes(it) }
        val issuedAt = clock()

        val signedBytes = Transfer.signedBytesOf(
            community = community,
            sender = senderBytes,
            recipient = recipient.bytes,
            amount = amount,
            seq = seq,
            memoHash = memoHash,
            issuedAt = issuedAt,
            nonce = nonce,
        )
        val signature = CurrencySigning.sign(keystore, signedBytes)

        val transfer = Transfer(
            version = Transfer.VERSION,
            community = community,
            sender = senderBytes,
            recipient = recipient.bytes,
            amount = amount,
            seq = seq,
            memoHash = memoHash,
            issuedAt = issuedAt,
            nonce = nonce,
            signature = signature,
        )

        persistVerifiedTransfer(transfer)
        return@withLock Sent(transfer)
    }

    // --------------------------------------------------------------------
    // Ingest (inbound)
    // --------------------------------------------------------------------

    sealed interface IngestOutcome
    data class Ingested(val envelopeRow: CurrencyEnvelopeEntity) : IngestOutcome
    object Duplicate : IngestOutcome
    data class DoubleSpend(
        val existing: Transfer,
        val incoming: Transfer,
    ) : IngestOutcome
    data class IngestRejected(val reason: String) : IngestOutcome

    /**
     * Persist an inbound transfer. Caller MUST have already verified the
     * signature via [CurrencySigning.verify] — this method assumes it.
     *
     * On (sender, seq) collision the existing row is fetched and its
     * body compared byte-for-byte. Identical → [Duplicate]. Different →
     * [DoubleSpend] — caller routes this to the slash detector.
     *
     * On successful insert the local account cache is incrementally
     * updated. If the incremental update would create a negative
     * balance (the sender claimed more than we know they have, e.g.
     * because we're behind on their earlier envelopes), the envelope
     * still persists but the account update is skipped — [recomputeBalances]
     * will reconcile once the rest of the chain arrives.
     */
    suspend fun recordInboundTransfer(transfer: Transfer): IngestOutcome {
        ensureOpen()
        val entity = transferToEntity(transfer)
        try {
            database.currencyEnvelopeDao.insert(entity)
        } catch (t: Throwable) {
            val existing = database.currencyEnvelopeDao.get(
                community = transfer.community,
                typeTag = CurrencyTypeTag.TRANSFER,
                primaryKey = CurrencyPrimaryKey.of(transfer),
            ) ?: return IngestRejected("insert failed and no conflicting row: ${t.message}")
            if (existing.body.contentEquals(entity.body)) return Duplicate
            val existingTransfer = Transfer.decode(existing.body)
            return DoubleSpend(existingTransfer, transfer)
        }
        applyInboundTransferToCache(transfer)
        return Ingested(entity)
    }

    /**
     * Persist an inbound slash. Caller MUST have validated it via
     * [SlashDetector.validate] first — this method does not re-verify
     * signatures.
     *
     *   - PK collision (community, SLASH, target) with identical body →
     *     [Duplicate]. First slash from another witness already arrived.
     *   - Different body → [Duplicate] as well: any honest witness's
     *     slash for the same target carries equivalent meaning, and we
     *     don't want to mark twice. Future audit logs may want to
     *     track all witnesses; deferred.
     *   - On a fresh insert: the target is also marked slashed in the
     *     account cache.
     */
    suspend fun recordInboundSlash(slash: Slash): IngestOutcome {
        ensureOpen()
        val entity = slashToEntity(slash)
        try {
            database.currencyEnvelopeDao.insert(entity)
        } catch (t: Throwable) {
            // Existing slash for this target — first one wins.
            return Duplicate
        }
        markAccountSlashed(AccountId(slash.target))
        return Ingested(entity)
    }

    /** Persist a genesis certificate and seed recipient balances. */
    suspend fun recordGenesis(cert: GenesisIssuance): IngestOutcome {
        ensureOpen()
        val entity = genesisToEntity(cert)
        try {
            database.currencyEnvelopeDao.insert(entity)
        } catch (t: Throwable) {
            val existing = database.currencyEnvelopeDao.get(
                community = cert.community,
                typeTag = CurrencyTypeTag.GENESIS_ISSUANCE,
                primaryKey = CurrencyPrimaryKey.of(cert),
            ) ?: return IngestRejected("insert failed and no conflicting row: ${t.message}")
            return if (existing.body.contentEquals(entity.body)) Duplicate
            else IngestRejected("Genesis already exists for this community with different content")
        }
        for (r in cert.recipients) {
            val id = AccountId(r.account)
            val current = database.accountDao.get(id.bytes)
            database.accountDao.upsert(
                AccountEntity(
                    pub = id.bytes,
                    seq = current?.seq ?: 0L,
                    balanceCached = (current?.balanceCached ?: 0L) + r.amount,
                    slashed = current?.slashed ?: false,
                )
            )
        }
        return Ingested(entity)
    }

    // --------------------------------------------------------------------
    // Bulk enumeration — for the sync engine
    // --------------------------------------------------------------------

    /**
     * Every envelope this device has for [community], as raw rows. The
     * sync engine reads from this to compute the HaveSet exchange. The
     * order is unspecified; callers should treat it as a set.
     */
    suspend fun allEnvelopesForCommunity(community: ByteArray): List<CurrencyEnvelopeEntity> {
        ensureOpen()
        return database.currencyEnvelopeDao.forCommunity(community)
    }

    /**
     * Single envelope lookup by composite key. Used by the sync engine
     * after a Want exchange to gather rows for a Push.
     */
    suspend fun getEnvelope(
        community: ByteArray,
        typeTag: Int,
        primaryKey: ByteArray,
    ): CurrencyEnvelopeEntity? {
        ensureOpen()
        return database.currencyEnvelopeDao.get(community, typeTag, primaryKey)
    }

    // --------------------------------------------------------------------
    // History / query
    // --------------------------------------------------------------------

    /** All transfers in [community], decoded and ordered newest-first by observedAt. */
    suspend fun recentTransfers(community: ByteArray, limit: Int = 50): List<Transfer> {
        ensureOpen()
        return database.currencyEnvelopeDao
            .byTypeOrdered(community, CurrencyTypeTag.TRANSFER)
            .asReversed()
            .take(limit)
            .map { Transfer.decode(it.body) }
    }

    /** Returns the genesis cert for [community] if one has been ingested. */
    suspend fun genesisOrNull(community: ByteArray): GenesisIssuance? {
        ensureOpen()
        return database.currencyEnvelopeDao
            .byType(community, CurrencyTypeTag.GENESIS_ISSUANCE)
            .firstOrNull()
            ?.let { GenesisIssuance.decode(it.body) }
    }

    /**
     * DEBUG ONLY: mint a single-recipient genesis crediting the local
     * identity with [amount] Gem. Lets the wallet be exercised
     * end-to-end on a single device before the community-founding flow
     * exists. A real release MUST hide this — only the founder of a
     * real community is allowed to issue genesis.
     */
    suspend fun mintDebugGenesis(community: ByteArray, amount: Long): SendOutcome {
        require(amount >= 0) { "amount must be non-negative" }
        ensureOpen()
        val handle = keystore.loadOrCreateIdentityKey()
        val issuer = handle.publicKey
        val nonce = ByteArray(GenesisIssuance.NONCE_LENGTH).also { random.nextBytes(it) }
        val issuedAt = clock()
        val signedBytes = GenesisIssuance.signedBytesOf(
            community = community,
            issuer = issuer,
            recipients = listOf(GenesisIssuance.Recipient(issuer, amount)),
            totalSupply = amount,
            issuedAt = issuedAt,
            nonce = nonce,
        )
        val signature = CurrencySigning.sign(keystore, signedBytes)
        val cert = GenesisIssuance(
            version = GenesisIssuance.VERSION,
            community = community,
            issuer = issuer,
            recipients = listOf(GenesisIssuance.Recipient(issuer, amount)),
            totalSupply = amount,
            issuedAt = issuedAt,
            nonce = nonce,
            signature = signature,
        )
        return when (recordGenesis(cert)) {
            is Ingested -> Sent(
                // Synthesize a transfer-shaped outcome from the genesis so
                // the UI can use one type. Not a real transfer — sender
                // and recipient are both the issuer.
                transfer = Transfer(
                    version = Transfer.VERSION,
                    community = community,
                    sender = issuer,
                    recipient = issuer,
                    amount = amount,
                    seq = 0L,
                    memoHash = null,
                    issuedAt = issuedAt,
                    nonce = nonce,
                    signature = signature,
                )
            )
            is Duplicate -> SendRejected("a genesis already exists for this community")
            is IngestRejected -> SendRejected("genesis rejected")
            else -> SendRejected("unexpected ingest outcome")
        }
    }

    // --------------------------------------------------------------------
    // Maintenance
    // --------------------------------------------------------------------

    /**
     * Rebuild the entire account cache for [community] by folding all
     * persisted envelopes through [LedgerReducer]. Use after a crash,
     * a schema migration, or when the Audit screen requests verification.
     */
    suspend fun recomputeBalances(community: ByteArray): LedgerReducer.State {
        ensureOpen()
        val envelopes = database.currencyEnvelopeDao.forCommunity(community)
        // Order: genesis first, then transfers by (sender, seq). Slashes
        // applied as they appear; the reducer treats duplicates as no-ops.
        val events = envelopesToEvents(envelopes)
        val state = LedgerReducer.fold(events)

        // Write the resulting balances back to the account cache.
        for ((id, balance) in state.balances) {
            val isSlashed = state.isSlashed(id)
            val seq = state.seqOf(id)
            database.accountDao.upsert(
                AccountEntity(
                    pub = id.bytes,
                    seq = seq,
                    balanceCached = if (isSlashed) 0L else balance,
                    slashed = isSlashed,
                )
            )
        }
        return state
    }

    /**
     * Wipe the local identity, wallet, and ledger. After this returns:
     *
     *   - The AndroidKeystore wrapping key is deleted.
     *   - The seed file is deleted.
     *   - The SQLCipher database file is deleted (its passphrase is
     *     keystore-derived; without the keystore key the file is
     *     unreadable garbage).
     *
     * The next call to a wallet method will lazily create a fresh
     * identity and a fresh database. The keystore reads its options
     * lazily, so a user who toggled "bind to biometric" between reset
     * and the next onboarding gets the new mode.
     *
     * This is destructive and unrecoverable — the spec says so
     * (SECURITY-MODEL.md §2 "Identity keys do not rotate"). Callers
     * MUST gate this behind an explicit confirmation prompt.
     */
    suspend fun resetIdentity() {
        // Order matters: close + delete the DB first while we still have
        // the keystore key (deleteDatabase doesn't need it, but if the
        // DB is open we want a clean close before the key disappears).
        // Wiping the DB drops every table — including community_membership
        // — so no separate community-service call is needed here.
        database.wipe()
        // Now nuke the identity.
        (keystore as? com.wyspr.core.crypto.AndroidKeystoreManager)?.reset()
            ?: error("KeystoreManager does not support reset; only AndroidKeystoreManager does")
    }

    /**
     * Apply a slash. Idempotent. The account row is updated even if no
     * envelope exists for the target yet, so future inbound transfers
     * naming them as sender or recipient are rejected immediately.
     */
    suspend fun markAccountSlashed(target: AccountId) {
        ensureOpen()
        val current = database.accountDao.get(target.bytes)
        if (current == null) {
            database.accountDao.upsert(
                AccountEntity(
                    pub = target.bytes,
                    seq = 0L,
                    balanceCached = 0L,
                    slashed = true,
                )
            )
        } else if (!current.slashed) {
            database.accountDao.markSlashed(target.bytes)
        }
    }

    // --------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }

    private suspend fun persistVerifiedTransfer(transfer: Transfer) {
        database.currencyEnvelopeDao.insert(transferToEntity(transfer))
        applyOutboundTransferToCache(transfer)
    }

    private suspend fun applyOutboundTransferToCache(transfer: Transfer) {
        val sender = AccountId(transfer.sender)
        val recipient = AccountId(transfer.recipient)
        val senderRow = database.accountDao.get(sender.bytes)
        val recipientRow = database.accountDao.get(recipient.bytes)
        database.accountDao.upsert(
            AccountEntity(
                pub = sender.bytes,
                seq = transfer.seq,
                balanceCached = ((senderRow?.balanceCached ?: 0L) - transfer.amount).coerceAtLeast(0L),
                slashed = senderRow?.slashed ?: false,
            )
        )
        database.accountDao.upsert(
            AccountEntity(
                pub = recipient.bytes,
                seq = recipientRow?.seq ?: 0L,
                balanceCached = (recipientRow?.balanceCached ?: 0L) + transfer.amount,
                slashed = recipientRow?.slashed ?: false,
            )
        )
    }

    private suspend fun applyInboundTransferToCache(transfer: Transfer) {
        val sender = AccountId(transfer.sender)
        val recipient = AccountId(transfer.recipient)
        val senderRow = database.accountDao.get(sender.bytes)
        val recipientRow = database.accountDao.get(recipient.bytes)
        val senderBalance = senderRow?.balanceCached ?: 0L
        // Only debit if the cache says they had it; otherwise we're behind
        // on their chain and recomputeBalances will reconcile later.
        if (senderRow != null && senderBalance >= transfer.amount) {
            database.accountDao.upsert(
                AccountEntity(
                    pub = sender.bytes,
                    seq = maxOf(senderRow.seq, transfer.seq),
                    balanceCached = senderBalance - transfer.amount,
                    slashed = senderRow.slashed,
                )
            )
        } else if (senderRow != null) {
            // Advance seq but don't go negative.
            database.accountDao.upsert(
                senderRow.copy(seq = maxOf(senderRow.seq, transfer.seq))
            )
        }
        database.accountDao.upsert(
            AccountEntity(
                pub = recipient.bytes,
                seq = recipientRow?.seq ?: 0L,
                balanceCached = (recipientRow?.balanceCached ?: 0L) + transfer.amount,
                slashed = recipientRow?.slashed ?: false,
            )
        )
    }

    private fun transferToEntity(transfer: Transfer): CurrencyEnvelopeEntity =
        CurrencyEnvelopeEntity(
            community = transfer.community.copyOf(),
            typeTag = CurrencyTypeTag.TRANSFER,
            primaryKey = CurrencyPrimaryKey.of(transfer),
            body = transfer.encode(),
            observedAt = clock(),
        )

    private fun genesisToEntity(cert: GenesisIssuance): CurrencyEnvelopeEntity =
        CurrencyEnvelopeEntity(
            community = cert.community.copyOf(),
            typeTag = CurrencyTypeTag.GENESIS_ISSUANCE,
            primaryKey = CurrencyPrimaryKey.of(cert),
            body = cert.encode(),
            observedAt = clock(),
        )

    private fun slashToEntity(slash: Slash): CurrencyEnvelopeEntity =
        CurrencyEnvelopeEntity(
            community = slash.community.copyOf(),
            typeTag = CurrencyTypeTag.SLASH,
            primaryKey = CurrencyPrimaryKey.forSlash(slash.target),
            body = slash.encode(),
            observedAt = clock(),
        )

    /** Decode and order envelopes into reducer-ready events. */
    private fun envelopesToEvents(rows: List<CurrencyEnvelopeEntity>): List<LedgerReducer.Event> {
        val events = mutableListOf<LedgerReducer.Event>()
        // Genesis first.
        rows.filter { it.typeTag == CurrencyTypeTag.GENESIS_ISSUANCE }
            .forEach { events += LedgerReducer.GenesisEvent(GenesisIssuance.decode(it.body)) }
        // Transfers, ordered by (sender bytes, seq) so the reducer's
        // monotonic-seq check sees them in the correct per-account order.
        rows.filter { it.typeTag == CurrencyTypeTag.TRANSFER }
            .map { Transfer.decode(it.body) }
            .sortedWith(compareBy({ it.sender.toHex() }, { it.seq }))
            .forEach { events += LedgerReducer.TransferEvent(it) }
        return events
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
