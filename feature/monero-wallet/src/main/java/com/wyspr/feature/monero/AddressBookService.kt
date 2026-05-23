package com.wyspr.feature.monero

import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.AddressBookEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Local-only address book for non-paired recipients. Stores
 * (chain, label, address) so the user can resend without re-pasting
 * — exchange withdrawal targets, merchant addresses, friends not on
 * Wyspr.
 *
 * Never synced. The auto-exchange channel covers paired peers; this
 * is the manual escape hatch for everyone else.
 */
@Singleton
class AddressBookService @Inject constructor(
    private val database: WysprDatabase,
) {

    suspend fun upsert(chain: String, label: String, address: String) {
        withContext(Dispatchers.IO) {
            ensureOpen()
            val now = System.currentTimeMillis() / 1000
            database.addressBookDao.upsert(
                AddressBookEntity(
                    chain = chain,
                    label = label.trim(),
                    address = address.trim(),
                    createdAt = now,
                    lastUsedAt = now,
                ),
            )
        }
    }

    /** Bump `last_used_at` so the entry sorts to the top. */
    suspend fun touch(chain: String, label: String) {
        withContext(Dispatchers.IO) {
            ensureOpen()
            database.addressBookDao.touch(
                chain = chain,
                label = label,
                nowSeconds = System.currentTimeMillis() / 1000,
            )
        }
    }

    suspend fun delete(chain: String, label: String) {
        withContext(Dispatchers.IO) {
            ensureOpen()
            database.addressBookDao.delete(chain = chain, label = label)
        }
    }

    fun forChainFlow(chain: String): Flow<List<AddressBookEntity>> =
        database.addressBookDao.forChainFlow(chain).flowOn(Dispatchers.IO)

    private suspend fun ensureOpen() {
        if (!database.isOpen) database.open()
    }
}
