package com.wyspr.core.database

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
import com.wyspr.core.database.dao.GroupMessageDeliveryDao
import com.wyspr.core.database.dao.ReactionDao
import com.wyspr.core.database.dao.RevocationDao
import com.wyspr.core.database.dao.SeenCertNonceDao
import com.wyspr.core.database.dao.TrustEdgeDao
import com.wyspr.core.database.dao.UserProfileDao

/**
 * The on-device persistent store.
 *
 * Backed by SQLCipher (via Room's SupportFactory). The 256-bit database
 * key is derived from the hardware-keystore identity key via HKDF —
 * the database cannot be opened on another device, even if the file is
 * copied off. SECURITY-MODEL.md §4.
 *
 * Tables (each backed by a Room @Dao):
 *
 *   - trust_edge         — Web of Trust edges
 *   - revocation         — observed RevocationCertificates
 *   - account            — per-identity Gem balance cache + seq + slashed flag
 *   - currency_envelope  — append-only log of every verified currency envelope
 *
 * Room schema definitions live in the same module. This interface is the
 * facade exposed to the rest of the app; nothing else reaches into Room
 * directly. Every DAO accessor throws if the database is not [isOpen] —
 * call [open] first.
 */
interface WysprDatabase {

    /** Open or create the encrypted DB. The passphrase is derived from the keystore. */
    suspend fun open()

    /** Close and wipe in-memory keys. Subsequent calls require open() again. */
    suspend fun close()

    /**
     * Close (if open) and delete the database files on disk. The
     * keystore-derived passphrase is gone after an identity reset, so
     * the encrypted DB file would be unreadable anyway — wiping it
     * prevents stale orphans and lets the next open() create a fresh
     * one under the new key.
     */
    suspend fun wipe()

    /**
     * Force a WAL checkpoint and truncate the WAL file so deleted
     * rows are not recoverable from the write-ahead log. Called after
     * purging disappearing messages.
     */
    suspend fun walCheckpointTruncate()

    /** Available after open(). */
    val isOpen: Boolean

    val trustEdgeDao: TrustEdgeDao
    val revocationDao: RevocationDao
    val accountDao: AccountDao
    val currencyEnvelopeDao: CurrencyEnvelopeDao
    val communityMembershipDao: CommunityMembershipDao
    val messageDao: MessageDao
    val contactDao: ContactDao
    val groupDao: GroupDao
    val groupMemberDao: GroupMemberDao
    val groupMessageDao: GroupMessageDao
    val userProfileDao: UserProfileDao
    val mailboxBindingDao: MailboxBindingDao
    val mailboxStoredDao: MailboxStoredDao
    val mailboxPullCursorDao: MailboxPullCursorDao
    val handshakeQuarantineDao: HandshakeQuarantineDao
    val seenCertNonceDao: SeenCertNonceDao
    val peerPaymentAddressDao: PeerPaymentAddressDao
    val peerSubAddressMintDao: PeerSubAddressMintDao
    val addressBookDao: AddressBookDao
    val reactionDao: ReactionDao
    val groupMessageDeliveryDao: GroupMessageDeliveryDao
}
