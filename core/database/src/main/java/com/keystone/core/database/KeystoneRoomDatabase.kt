package com.keystone.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.keystone.core.database.dao.AccountDao
import com.keystone.core.database.dao.CommunityMembershipDao
import com.keystone.core.database.dao.ContactDao
import com.keystone.core.database.dao.CurrencyEnvelopeDao
import com.keystone.core.database.dao.GroupDao
import com.keystone.core.database.dao.GroupMemberDao
import com.keystone.core.database.dao.GroupMessageDao
import com.keystone.core.database.dao.MessageDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.entities.AccountEntity
import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.database.entities.ContactEntity
import com.keystone.core.database.entities.CurrencyEnvelopeEntity
import com.keystone.core.database.entities.GroupEntity
import com.keystone.core.database.entities.GroupMemberEntity
import com.keystone.core.database.entities.GroupMessageEntity
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
 *   v7 — adds group_entity, group_member, group_message — private
 *        groups support (docs/GROUPS.md). Additive only; 1:1
 *        messaging tables untouched.
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
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
    ],
    version = 7,
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
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun groupMessageDao(): GroupMessageDao
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

/**
 * v6 → v7: private groups. Three new tables, all additive. Existing
 * data (trust edges, 1:1 messages, contacts) is untouched.
 *
 * Schema mirrors `GroupEntity`, `GroupMemberEntity`, `GroupMessageEntity`
 * — keep them aligned when bumping again.
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_entity` (" +
                "`group_id` BLOB NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`creator_pub` BLOB NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`local_nickname` TEXT, " +
                "PRIMARY KEY(`group_id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_member` (" +
                "`group_id` BLOB NOT NULL, " +
                "`member_pub` BLOB NOT NULL, " +
                "`cert_bytes` BLOB NOT NULL, " +
                "`added_at` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "PRIMARY KEY(`group_id`, `member_pub`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_member_member_pub` " +
                "ON `group_member`(`member_pub`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_message` (" +
                "`id` BLOB NOT NULL, " +
                "`group_id` BLOB NOT NULL, " +
                "`from_pub` BLOB NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`received_at` INTEGER, " +
                "`status` TEXT NOT NULL, " +
                "`signature` BLOB NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`group_id`) REFERENCES `group_entity`(`group_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_message_group_id_created_at` " +
                "ON `group_message`(`group_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_message_status` " +
                "ON `group_message`(`status`)"
        )
    }
}
