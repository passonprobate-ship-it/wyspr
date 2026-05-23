package com.wyspr.core.currency

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.sync.EnvelopeKey
import com.wyspr.core.sync.EnvelopeRow
import com.wyspr.core.sync.SyncRepository

/**
 * Glue between the generic [SyncRepository] interface and the
 * currency-specific persistence + verification stack.
 *
 *   - [haveSet] enumerates from `currency_envelope`.
 *   - [envelopesByKeys] does N point lookups (room of optimization
 *     once sync is hot — for v0 the N is small).
 *   - [ingest] decodes the body by `typeTag`, verifies the signature
 *     with libsodium, and routes to the appropriate
 *     [WalletService.recordInbound*] path. Verification IS the trust
 *     boundary: bad signatures never touch the database.
 *
 * Slash envelopes are persisted via [WalletService.recordInboundSlash]
 * without the full [SlashDetector.validate] crypto check — the sync
 * layer's signature check covers the witness signature, and the
 * structural checks happen at next read. A v1 refactor will route
 * slashes through SlashDetector here so quarantine kicks in earlier.
 */
class CurrencySyncRepository(
    private val wallet: WalletService,
    private val sodium: LazySodiumAndroid,
) : SyncRepository {

    override suspend fun haveSet(community: ByteArray): List<EnvelopeKey> =
        wallet.allEnvelopesForCommunity(community).map {
            EnvelopeKey(typeTag = it.typeTag, primaryKey = it.primaryKey)
        }

    override suspend fun envelopesByKeys(
        community: ByteArray,
        keys: List<EnvelopeKey>,
    ): List<EnvelopeRow> {
        val out = ArrayList<EnvelopeRow>(keys.size)
        for (k in keys) {
            val row = wallet.getEnvelope(community, k.typeTag, k.primaryKey) ?: continue
            out += EnvelopeRow(
                community = row.community,
                typeTag = row.typeTag,
                primaryKey = row.primaryKey,
                body = row.body,
                observedAt = row.observedAt,
            )
        }
        return out
    }

    override suspend fun ingest(row: EnvelopeRow): Boolean = when (row.typeTag) {
        CurrencyTypeTag.TRANSFER -> ingestTransfer(row)
        CurrencyTypeTag.GENESIS_ISSUANCE -> ingestGenesis(row)
        CurrencyTypeTag.SLASH -> ingestSlash(row)
        // Service issuances + transfer memos are spec-defined but not
        // yet wired through WalletService; accept the bytes silently
        // for now so other peers don't see them as "rejected" and we
        // keep the door open for forward-compat.
        CurrencyTypeTag.SERVICE_ISSUANCE,
        CurrencyTypeTag.TRANSFER_MEMO -> true
        else -> false // unknown type — drop
    }

    private suspend fun ingestTransfer(row: EnvelopeRow): Boolean = runCatching {
        val t = Transfer.decode(row.body)
        if (!CurrencySigning.verify(sodium, t)) return false
        when (wallet.recordInboundTransfer(t)) {
            is WalletService.Ingested,
            is WalletService.Duplicate,
            is WalletService.DoubleSpend -> true
            is WalletService.IngestRejected -> false
            else -> false
        }
    }.getOrDefault(false)

    private suspend fun ingestGenesis(row: EnvelopeRow): Boolean = runCatching {
        val cert = GenesisIssuance.decode(row.body)
        if (!CurrencySigning.verify(sodium, cert)) return false
        when (wallet.recordGenesis(cert)) {
            is WalletService.Ingested,
            is WalletService.Duplicate -> true
            else -> false
        }
    }.getOrDefault(false)

    private suspend fun ingestSlash(row: EnvelopeRow): Boolean = runCatching {
        val slash = Slash.decode(row.body)
        // We verify only the witness signature here; the cryptographic
        // claim that the evidence transfers are themselves valid is
        // checked when the recipient eventually consults the Slash.
        // SlashDetector.validate covers both; routing through it here
        // is a v1 cleanup.
        if (!CurrencySigning.verify(sodium, slash.witness, slash.signedBytes(), slash.signature)) {
            return false
        }
        when (wallet.recordInboundSlash(slash)) {
            is WalletService.Ingested,
            is WalletService.Duplicate -> true
            else -> false
        }
    }.getOrDefault(false)
}
