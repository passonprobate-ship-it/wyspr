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
import com.keystone.core.database.dao.HandshakeQuarantineDao
import com.keystone.core.database.dao.MailboxBindingDao
import com.keystone.core.database.dao.MailboxPullCursorDao
import com.keystone.core.database.dao.MailboxStoredDao
import com.keystone.core.database.dao.MessageDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.SeenCertNonceDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.dao.UserProfileDao
import com.keystone.core.database.entities.AccountEntity
import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.database.entities.ContactEntity
import com.keystone.core.database.entities.CurrencyEnvelopeEntity
import com.keystone.core.database.entities.GroupEntity
import com.keystone.core.database.entities.GroupMemberEntity
import com.keystone.core.database.entities.GroupMessageEntity
import com.keystone.core.database.entities.HandshakeQuarantineEntity
import com.keystone.core.database.entities.MailboxBindingEntity
import com.keystone.core.database.entities.MailboxPullCursorEntity
import com.keystone.core.database.entities.MailboxStoredEntity
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.database.entities.RevocationEntity
import com.keystone.core.database.entities.SeenCertNonceEntity
import com.keystone.core.database.entities.TrustEdgeEntity
import com.keystone.core.database.entities.UserProfileEntity

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
 *   v8 — adds user_profile (singleton) — backing store for the
 *        user's personal web page served at `http://<onion>:80/`.
 *   v9 — adds mailbox_binding, mailbox_stored — the user's chosen
 *        mailbox(es) for async delivery + the sealed envelopes a
 *        device acting as a mailbox is holding for peers. See
 *        docs/MAILBOX.md.
 *   v10 — adds `signature` column on `mailbox_stored`. Phase 1 stored
 *        only ciphertext + addressing fields; Phase 4's pull lane
 *        needs the outer Ed25519 signature so the host can re-serve
 *        the byte-identical envelope to the recipient. Additive.
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
        UserProfileEntity::class,
        MailboxBindingEntity::class,
        MailboxStoredEntity::class,
        MailboxPullCursorEntity::class,
        HandshakeQuarantineEntity::class,
        SeenCertNonceEntity::class,
    ],
    version = 12,
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
    abstract fun userProfileDao(): UserProfileDao
    abstract fun mailboxBindingDao(): MailboxBindingDao
    abstract fun mailboxStoredDao(): MailboxStoredDao
    abstract fun mailboxPullCursorDao(): MailboxPullCursorDao
    abstract fun handshakeQuarantineDao(): HandshakeQuarantineDao
    abstract fun seenCertNonceDao(): SeenCertNonceDao
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

/**
 * v7 → v8: add the `user_profile` singleton table. Backing store for
 * the user's `http://<onion>:80/` page. Additive — nothing else
 * changes.
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile` (" +
                "`id` INTEGER NOT NULL, " +
                "`display_name` TEXT, " +
                "`bio` TEXT, " +
                "`avatar_emoji` TEXT, " +
                "`links` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v8 → v9: mailbox support. Two new tables, additive only. See
 * docs/MAILBOX.md.
 *
 * - `mailbox_binding` — one row per "peer → their chosen mailbox"
 *   mapping. The local user's row (owner_pub = own pub) describes
 *   THEIR mailbox; peers' rows describe MAILBOXES owned by peers.
 * - `mailbox_stored` — sealed envelopes the local device is holding
 *   as a mailbox host. Only ever populated when "Be a mailbox" is
 *   on; empty otherwise.
 */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailbox_binding` (" +
                "`owner_pub` BLOB NOT NULL, " +
                "`mailbox_pub` BLOB NOT NULL, " +
                "`mailbox_onion` TEXT, " +
                "`cert_bytes` BLOB NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`expires_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`owner_pub`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailbox_stored` (" +
                "`envelope_id` BLOB NOT NULL, " +
                "`to_pub` BLOB NOT NULL, " +
                "`from_pub` BLOB NOT NULL, " +
                "`ciphertext` BLOB NOT NULL, " +
                "`size_bytes` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`expires_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`envelope_id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mailbox_stored_to_pub_created_at` " +
                "ON `mailbox_stored`(`to_pub`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mailbox_stored_expires_at` " +
                "ON `mailbox_stored`(`expires_at`)"
        )
    }
}

/**
 * v9 → v10: add `signature` column to `mailbox_stored`. Any rows
 * from a v9 dev install would survive the ALTER with `x''` defaults,
 * but `MailboxEnvelope.init` requires SIG_LENGTH bytes — so reading
 * those rows back would crash the sync coroutine. The Phase-1 host
 * wasn't on the wire yet, so any rows present are stale dev data
 * we can safely drop in lieu of fabricating signatures.
 */
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `mailbox_stored`")
        db.execSQL(
            "ALTER TABLE `mailbox_stored` ADD COLUMN `signature` BLOB NOT NULL DEFAULT x''"
        )
    }
}

/**
 * v10 → v11: add `mailbox_pull_cursor`. Tracks the largest
 * `created_at` (in seconds) we've successfully pulled from a given
 * mailbox host. Without this, the recipient sends `since_cursor=0`
 * every round and a malicious host can replay the entire backlog
 * forever. Additive — no data changes.
 */
internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailbox_pull_cursor` (" +
                "`mailbox_pub` BLOB NOT NULL, " +
                "`since_cursor` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`mailbox_pub`))"
        )
    }
}

/**
 * v11 → v12: revocation gets a `communityId` column AND new tables
 * for handshake quarantine and single-use cert nonces. Previously
 * the revocation's community was inferred from the local install at
 * rebuild time; under that scheme a cert signed against community
 * X stored on a device claiming community Y would re-broadcast as
 * invalid bytes. We now persist the originally-signed community
 * verbatim. Legacy rows are defaulted to a zero-length blob — the
 * sync ingest path filters those out at startup.
 *
 * `handshake_quarantine` and `seen_cert_nonce` are new tables.
 * Additive, no data changes for them.
 */
internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `revocation` ADD COLUMN `communityId` BLOB NOT NULL DEFAULT x''"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `handshake_quarantine` (" +
                "`peer_pub` BLOB NOT NULL, " +
                "`quarantined_until` INTEGER NOT NULL, " +
                "PRIMARY KEY(`peer_pub`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `seen_cert_nonce` (" +
                "`nonce` BLOB NOT NULL, " +
                "`observed_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`nonce`))"
        )
    }
}
