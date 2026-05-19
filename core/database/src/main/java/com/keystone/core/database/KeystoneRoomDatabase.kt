package com.keystone.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.keystone.core.database.dao.AccountDao
import com.keystone.core.database.dao.CommunityMembershipDao
import com.keystone.core.database.dao.CurrencyEnvelopeDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.entities.AccountEntity
import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.database.entities.CurrencyEnvelopeEntity
import com.keystone.core.database.entities.RevocationEntity
import com.keystone.core.database.entities.TrustEdgeEntity

/**
 * Room subclass. Use [KeystoneDatabaseImpl] to construct and open — it
 * wires SQLCipher's SupportOpenHelper.Factory around this with the key
 * derived from the hardware keystore.
 *
 * Schema versions:
 *   v1 — trust_edge, revocation
 *   v2 — adds account, currency_envelope (Gem currency, CURRENCY.md §8)
 *   v3 — adds community_membership (one row per community joined)
 *
 * For now [KeystoneDatabaseImpl] is built with
 * fallbackToDestructiveMigration() — pre-release, no users on v1 yet.
 * When the first real install ships, swap to explicit migrations.
 */
@Database(
    entities = [
        TrustEdgeEntity::class,
        RevocationEntity::class,
        AccountEntity::class,
        CurrencyEnvelopeEntity::class,
        CommunityMembershipEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KeystoneRoomDatabase : RoomDatabase() {
    abstract fun trustEdgeDao(): TrustEdgeDao
    abstract fun revocationDao(): RevocationDao
    abstract fun accountDao(): AccountDao
    abstract fun currencyEnvelopeDao(): CurrencyEnvelopeDao
    abstract fun communityMembershipDao(): CommunityMembershipDao
}
