package com.wyspr.core.database

import com.wyspr.core.database.dao.AccountDao
import com.wyspr.core.database.dao.AddressBookDao
import com.wyspr.core.database.dao.GroupMessageDeliveryDao
import com.wyspr.core.database.dao.ReactionDao
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
import com.wyspr.core.database.dao.RevocationDao
import com.wyspr.core.database.dao.SeenCertNonceDao
import com.wyspr.core.database.dao.TrustEdgeDao
import com.wyspr.core.database.dao.UserProfileDao

/**
 * In-memory [WysprDatabase] backed by [HashMap]-based fake DAOs.
 *
 * Useful for unit tests that need a database facade without the SQLCipher
 * / Room stack. Tracks the open/close lifecycle; DAO accessors throw
 * [IllegalStateException] before [open] and after [close]/[wipe].
 */
class FakeDatabase : WysprDatabase {

    @Volatile
    private var open: Boolean = false

    override val isOpen: Boolean get() = open

    private val _trustEdgeDao = FakeTrustEdgeDao()
    private val _revocationDao = FakeRevocationDao()
    private val _accountDao = FakeAccountDao()
    private val _currencyEnvelopeDao = FakeCurrencyEnvelopeDao()
    private val _communityMembershipDao = FakeCommunityMembershipDao()
    private val _messageDao = FakeMessageDao()

    override val trustEdgeDao: TrustEdgeDao
        get() { requireOpen(); return _trustEdgeDao }
    override val revocationDao: RevocationDao
        get() { requireOpen(); return _revocationDao }
    override val accountDao: AccountDao
        get() { requireOpen(); return _accountDao }
    override val currencyEnvelopeDao: CurrencyEnvelopeDao
        get() { requireOpen(); return _currencyEnvelopeDao }
    override val communityMembershipDao: CommunityMembershipDao
        get() { requireOpen(); return _communityMembershipDao }
    override val messageDao: MessageDao
        get() { requireOpen(); return _messageDao }

    // Stubs for DAOs the existing test suite never touches. Throwing
    // is preferable to silently returning a fake that doesn't match
    // the interface contract — any test that reaches one of these is
    // missing infrastructure, not data.
    override val contactDao: ContactDao
        get() = TODO("ContactDao not faked")
    override val groupDao: GroupDao
        get() = TODO("GroupDao not faked")
    override val groupMemberDao: GroupMemberDao
        get() = TODO("GroupMemberDao not faked")
    override val groupMessageDao: GroupMessageDao
        get() = TODO("GroupMessageDao not faked")
    override val userProfileDao: UserProfileDao
        get() = TODO("UserProfileDao not faked")
    override val mailboxBindingDao: MailboxBindingDao
        get() = TODO("MailboxBindingDao not faked")
    override val mailboxStoredDao: MailboxStoredDao
        get() = TODO("MailboxStoredDao not faked")
    override val mailboxPullCursorDao: MailboxPullCursorDao
        get() = TODO("MailboxPullCursorDao not faked")
    override val handshakeQuarantineDao: HandshakeQuarantineDao
        get() = TODO("HandshakeQuarantineDao not faked")
    override val seenCertNonceDao: SeenCertNonceDao
        get() = TODO("SeenCertNonceDao not faked")
    override val peerPaymentAddressDao: PeerPaymentAddressDao
        get() = TODO("PeerPaymentAddressDao not faked")
    override val peerSubAddressMintDao: PeerSubAddressMintDao
        get() = TODO("PeerSubAddressMintDao not faked")
    override val addressBookDao: AddressBookDao
        get() = TODO("AddressBookDao not faked")
    override val reactionDao: ReactionDao
        get() = TODO("ReactionDao not faked")
    override val groupMessageDeliveryDao: GroupMessageDeliveryDao
        get() = TODO("GroupMessageDeliveryDao not faked")

    override suspend fun open() {
        open = true
    }

    override suspend fun close() {
        open = false
        clearState()
    }

    override suspend fun wipe() {
        open = false
        clearState()
    }

    override suspend fun walCheckpointTruncate() { }

    private fun clearState() {
        _trustEdgeDao.clear()
        _revocationDao.clear()
        _accountDao.clear()
        _currencyEnvelopeDao.clear()
        _communityMembershipDao.clear()
        _messageDao.clear()
    }

    private fun requireOpen() {
        check(open) { "WysprDatabase not open. Call open() first." }
    }
}
