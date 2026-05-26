package com.wyspr.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wyspr.core.database.dao.AccountDao
import com.wyspr.core.database.dao.AddressBookDao
import com.wyspr.core.database.dao.CommunityMembershipDao
import com.wyspr.core.database.dao.ContactDao
import com.wyspr.core.database.dao.CurrencyEnvelopeDao
import com.wyspr.core.database.dao.GroupDao
import com.wyspr.core.database.dao.GroupMemberDao
import com.wyspr.core.database.dao.GroupMessageDao
import com.wyspr.core.database.dao.HandshakeQuarantineDao
import com.wyspr.core.database.dao.MailboxBindingDao
import com.wyspr.core.database.dao.MailboxPullCursorDao
import com.wyspr.core.database.dao.MailboxStoredDao
import com.wyspr.core.database.dao.MessageDao
import com.wyspr.core.database.dao.PeerPaymentAddressDao
import com.wyspr.core.database.dao.PeerSubAddressMintDao
import com.wyspr.core.database.dao.KeyRotationDao
import com.wyspr.core.database.dao.ReactionDao
import com.wyspr.core.database.dao.RevocationDao
import com.wyspr.core.database.dao.SeenCertNonceDao
import com.wyspr.core.database.dao.TrustEdgeDao
import com.wyspr.core.database.dao.UserProfileDao
import com.wyspr.core.database.entities.AccountEntity
import com.wyspr.core.database.entities.AddressBookEntity
import com.wyspr.core.database.entities.CommunityMembershipEntity
import com.wyspr.core.database.entities.ContactEntity
import com.wyspr.core.database.entities.CurrencyEnvelopeEntity
import com.wyspr.core.database.entities.GroupEntity
import com.wyspr.core.database.entities.GroupMemberEntity
import com.wyspr.core.database.entities.GroupMessageEntity
import com.wyspr.core.database.entities.HandshakeQuarantineEntity
import com.wyspr.core.database.entities.MailboxBindingEntity
import com.wyspr.core.database.entities.MailboxPullCursorEntity
import com.wyspr.core.database.entities.MailboxStoredEntity
import com.wyspr.core.database.entities.MessageEntity
import com.wyspr.core.database.entities.PeerPaymentAddressEntity
import com.wyspr.core.database.entities.PeerSubAddressMintEntity
import com.wyspr.core.database.dao.GroupMessageDeliveryDao
import com.wyspr.core.database.entities.GroupMessageDeliveryEntity
import com.wyspr.core.database.entities.KeyRotationEntity
import com.wyspr.core.database.entities.ReactionEntity
import com.wyspr.core.database.entities.RevocationEntity
import com.wyspr.core.database.entities.SeenCertNonceEntity
import com.wyspr.core.database.entities.TrustEdgeEntity
import com.wyspr.core.database.entities.UserProfileEntity

/**
 * Room subclass. Use [WysprDatabaseImpl] to construct and open — it
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
        PeerPaymentAddressEntity::class,
        PeerSubAddressMintEntity::class,
        AddressBookEntity::class,
        ReactionEntity::class,
        GroupMessageDeliveryEntity::class,
        KeyRotationEntity::class,
    ],
    version = 23,
    exportSchema = true,
)
abstract class WysprRoomDatabase : RoomDatabase() {
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
    abstract fun peerPaymentAddressDao(): PeerPaymentAddressDao
    abstract fun peerSubAddressMintDao(): PeerSubAddressMintDao
    abstract fun addressBookDao(): AddressBookDao
    abstract fun reactionDao(): ReactionDao
    abstract fun groupMessageDeliveryDao(): GroupMessageDeliveryDao
    abstract fun keyRotationDao(): KeyRotationDao
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
 * v12 → v13: contact gets a `notes` column. User-supplied free-form
 * text, local-only (never synced over the wire). Default null so
 * existing rows survive without backfill.
 */
internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `contact` ADD COLUMN `notes` TEXT")
    }
}

/**
 * v13 → v14: relax `mailbox_binding.PRIMARY KEY` from `(owner_pub)` to
 * `(owner_pub, mailbox_pub)` so an owner can list multiple mailbox
 * hosts simultaneously (Sprint 2 of TOR-ACROSS-WEB). SQLite can't
 * change a primary key in place, so we recreate the table.
 *
 * Existing data survives: at most one row per `owner_pub` in v13 means
 * no composite-key collisions when re-inserting.
 */
internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailbox_binding_new` (" +
                "`owner_pub` BLOB NOT NULL, " +
                "`mailbox_pub` BLOB NOT NULL, " +
                "`mailbox_onion` TEXT, " +
                "`cert_bytes` BLOB NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`expires_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`owner_pub`, `mailbox_pub`))"
        )
        db.execSQL(
            "INSERT INTO `mailbox_binding_new` " +
                "(owner_pub, mailbox_pub, mailbox_onion, cert_bytes, created_at, expires_at) " +
                "SELECT owner_pub, mailbox_pub, mailbox_onion, cert_bytes, created_at, expires_at " +
                "FROM `mailbox_binding`"
        )
        db.execSQL("DROP TABLE `mailbox_binding`")
        db.execSQL("ALTER TABLE `mailbox_binding_new` RENAME TO `mailbox_binding`")
    }
}

/**
 * v14 → v15: add `peer_payment_address`. Chain-agnostic binding
 * of "this paired peer publishes this address on this chain".
 * Used by Sprint W2 of TOR-ACROSS-WEB to make Send-XMR a one-tap
 * action against the peer's friendly name, never the address
 * string.
 *
 * Composite PK on `(peer_pub, chain, address)` so a peer can rotate
 * addresses without losing history; `revoked_at` is the soft-
 * delete marker and `notes` is local-only free text.
 */
internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `peer_payment_address` (" +
                "`peer_pub` BLOB NOT NULL, " +
                "`chain` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`revoked_at` INTEGER, " +
                "`notes` TEXT, " +
                "PRIMARY KEY(`peer_pub`, `chain`, `address`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_peer_payment_address_peer_pub_chain_revoked_at` " +
                "ON `peer_payment_address`(`peer_pub`, `chain`, `revoked_at`)"
        )
    }
}

/**
 * v15 → v16: Sprint W4. Add `peer_subaddress_mint` — one row per
 * (peerPub, chain) recording the unique subaddress we minted for
 * that peer. Lets us advertise a relationship-scoped XMR address
 * instead of the primary so an on-chain observer can't link
 * payments from different paired peers.
 */
internal val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `peer_subaddress_mint` (" +
                "`peer_pub` BLOB NOT NULL, " +
                "`chain` TEXT NOT NULL, " +
                "`account_index` INTEGER NOT NULL, " +
                "`sub_address_index` INTEGER NOT NULL, " +
                "`address` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`peer_pub`, `chain`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_peer_subaddress_mint_chain_account_index_sub_address_index` " +
                "ON `peer_subaddress_mint`(`chain`, `account_index`, `sub_address_index`)"
        )
    }
}

/**
 * v16 → v17: Sprint W7. Add `address_book` — local-only saved
 * addresses for non-paired recipients (merchants, exchange
 * withdrawal targets, etc.). Composite PK on `(chain, label)` so
 * each chain's address space is independent.
 */
internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `address_book` (" +
                "`chain` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`last_used_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chain`, `label`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_address_book_chain_last_used_at` " +
                "ON `address_book`(`chain`, `last_used_at`)"
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
/**
 * v17 → v18: add `message_reaction` table. Composite PK on
 * `(msg_id, from_pub)` — one reaction per sender per message.
 * Additive, no existing data changes.
 */
/**
 * v18 → v19: disappearing messages. Add `expires_at` column to
 * `message` and `disappear_after` column to `contact`. Both nullable
 * — null means "no expiry" / "timer off". Additive, no data changes.
 */
internal val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `message` ADD COLUMN `expires_at` INTEGER")
        db.execSQL("ALTER TABLE `contact` ADD COLUMN `disappear_after` INTEGER")
    }
}

internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `message_reaction` (" +
                "`msg_id` BLOB NOT NULL, " +
                "`from_pub` BLOB NOT NULL, " +
                "`emoji` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msg_id`, `from_pub`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_reaction_msg_id` " +
                "ON `message_reaction`(`msg_id`)"
        )
    }
}

internal val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_message_delivery` (" +
                "`msg_id` BLOB NOT NULL, " +
                "`peer_pub` BLOB NOT NULL, " +
                "`delivered_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msg_id`, `peer_pub`))"
        )
    }
}

internal val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_rotation` (" +
                "`oldPub` BLOB NOT NULL, " +
                "`newPub` BLOB NOT NULL, " +
                "`communityId` BLOB NOT NULL, " +
                "`issuedAt` INTEGER NOT NULL, " +
                "`newOnion` TEXT, " +
                "`signature` BLOB NOT NULL, " +
                "PRIMARY KEY(`oldPub`, `newPub`))"
        )
    }
}

internal val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_key_rotation_newPub` ON `key_rotation`(`newPub`)"
        )
    }
}

/**
 * v22 → v23: three structural hardening changes.
 *
 *  1. FK CASCADE on `message_reaction` → `message`. Recreate the table
 *     with the FK constraint so reactions are cleaned up when a message
 *     is deleted (disappearing messages, manual delete).
 *  2. FK CASCADE on `group_message_delivery` → `group_message`. Same
 *     orphan-row problem.
 *  3. Index on `message(from_pub)` — rekey queries were doing full scans.
 */
internal val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Recreate message_reaction with FK
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `message_reaction_new` (" +
                "`msg_id` BLOB NOT NULL, " +
                "`from_pub` BLOB NOT NULL, " +
                "`emoji` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msg_id`, `from_pub`), " +
                "FOREIGN KEY(`msg_id`) REFERENCES `message`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `message_reaction_new` " +
                "(msg_id, from_pub, emoji, created_at) " +
                "SELECT msg_id, from_pub, emoji, created_at FROM `message_reaction`"
        )
        db.execSQL("DROP TABLE `message_reaction`")
        db.execSQL("ALTER TABLE `message_reaction_new` RENAME TO `message_reaction`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_reaction_msg_id` " +
                "ON `message_reaction`(`msg_id`)"
        )

        // 2. Recreate group_message_delivery with FK
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_message_delivery_new` (" +
                "`msg_id` BLOB NOT NULL, " +
                "`peer_pub` BLOB NOT NULL, " +
                "`delivered_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msg_id`, `peer_pub`), " +
                "FOREIGN KEY(`msg_id`) REFERENCES `group_message`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `group_message_delivery_new` " +
                "(msg_id, peer_pub, delivered_at) " +
                "SELECT msg_id, peer_pub, delivered_at FROM `group_message_delivery`"
        )
        db.execSQL("DROP TABLE `group_message_delivery`")
        db.execSQL("ALTER TABLE `group_message_delivery_new` RENAME TO `group_message_delivery`")

        // 3. Index on message(from_pub) for rekey queries
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_from_pub` ON `message`(`from_pub`)"
        )
    }
}

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
