package com.keystone.core.database

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
import com.keystone.core.database.dao.PeerPaymentAddressDao
import com.keystone.core.database.dao.PeerSubAddressMintDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.SeenCertNonceDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.dao.UserProfileDao

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
interface KeystoneDatabase {

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
}
