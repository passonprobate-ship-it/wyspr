package com.keystone.feature.monero

import android.util.Log
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.PeerSubAddressMintEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mints (or recalls) a Monero subaddress per paired peer. Backed
 * by the `peer_subaddress_mint` table; the wallet's
 * [im.molly.monero.sdk.MoneroWallet.findUnusedSubAddress] is the
 * source of fresh indices.
 *
 * Sprint W4: replacing the primary-address advertisement so on-
 * chain observers can't link payments received from peer A and
 * peer B to the same wallet via address reuse. Each peer gets a
 * stable, relationship-scoped subaddress; rotating it is a future
 * polish step.
 *
 * Lookups go through a mutex so two near-simultaneous sync rounds
 * for different peers don't both mint the same subaddress index
 * before the first row hits the DB.
 */
@Singleton
class SubAddressMintService @Inject constructor(
    private val walletService: MoneroWalletService,
    private val database: KeystoneDatabase,
) {

    private val mintLock = Mutex()

    /**
     * Return the cached subaddress for [peerPub], or mint a new
     * one. Returns null when the wallet isn't ready (caller should
     * skip advertising payment addresses this round). The Monero
     * wallet's [im.molly.monero.sdk.MoneroWallet.findUnusedSubAddress]
     * is what guarantees the index hasn't been used on-chain yet —
     * the local DB just remembers WHICH unused index we handed to
     * which peer.
     */
    suspend fun mintFor(peerPub: ByteArray): String? = mintLock.withLock {
        withContext(Dispatchers.IO) {
            if (!database.isOpen) database.open()
            val dao = database.peerSubAddressMintDao
            val existing = dao.forPeer(peerPub, PaymentAddressService.CHAIN_MONERO)
            if (existing != null) return@withContext existing.address

            val wallet = walletService.wallet ?: return@withContext null
            val sub = try {
                wallet.findUnusedSubAddress(0)
            } catch (t: Throwable) {
                Log.w(TAG, "findUnusedSubAddress failed: ${t::class.simpleName}: ${t.message}")
                return@withContext null
            } ?: run {
                Log.w(TAG, "findUnusedSubAddress returned null — wallet has no unused sub")
                return@withContext null
            }
            val entity = PeerSubAddressMintEntity(
                peerPub = peerPub,
                chain = PaymentAddressService.CHAIN_MONERO,
                accountIndex = sub.accountIndex,
                subAddressIndex = sub.subAddressIndex,
                address = sub.address,
                createdAt = System.currentTimeMillis() / 1000,
            )
            dao.upsert(entity)
            entity.address
        }
    }

    private companion object {
        const val TAG = "SubAddressMintService"
    }
}
