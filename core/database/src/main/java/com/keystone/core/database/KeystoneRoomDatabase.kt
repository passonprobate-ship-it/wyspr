package com.keystone.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.keystone.core.database.dao.AccountDao
import com.keystone.core.database.dao.CommunityMembershipDao
import com.keystone.core.database.dao.ContactDao
import com.keystone.core.database.dao.CurrencyEnvelopeDao
import com.keystone.core.database.dao.MessageDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.entities.AccountEntity
import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.database.entities.ContactEntity
import com.keystone.core.database.entities.CurrencyEnvelopeEntity
import com.keystone.core.database.entities.MessageEntity
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
 *   v4 — adds message (peer-to-peer encrypted messages)
 *   v5 — adds trust_edge.peerOnion (TEXT, nullable) — captures the
 *        peer's HSv3 .onion at handshake time so Sprint 4's
 *        TorHiddenServiceTransport can dial them later.
 *   v6 — adds contact (peerPub BLOB PK, displayName TEXT NULL) — user-
 *        supplied friendly names for paired peers. Non-destructive
 *        migration: existing trust edges are preserved so a freshly-
 *        paired user doesn't lose their counterpart on upgrade.
 *
 * From v6 onward we ship real migrations rather than
 * fallbackToDestructiveMigration(). The first real-world pair landed
 * on 2026-05-20 and we don't want to nuke it on every schema bump.
 */
@Database(
    entities = [
        TrustEdgeEntity::class,
        RevocationEntity::class,
        AccountEntity::class,
        CurrencyEnvelopeEntity::class,
        CommunityMembershipEntity::class,
        MessageEntity::class,
        ContactEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class KeystoneRoomDatabase : RoomDatabase() {
    abstract fun trustEdgeDao(): TrustEdgeDao
    abstract fun revocationDao(): RevocationDao
    abstract fun accountDao(): AccountDao
    abstract fun currencyEnvelopeDao(): CurrencyEnvelopeDao
    abstract fun communityMembershipDao(): CommunityMembershipDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
}

/**
 * v5 → v6: add the `contact` table for user-supplied friendly names.
 * Trust edges and message history are untouched.
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact` (" +
                "`peerPub` BLOB NOT NULL, " +
                "`displayName` TEXT, " +
                "PRIMARY KEY(`peerPub`))"
        )
    }
}
