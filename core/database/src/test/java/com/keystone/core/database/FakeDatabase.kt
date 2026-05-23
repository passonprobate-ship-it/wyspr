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
 * In-memory [KeystoneDatabase] backed by [HashMap]-based fake DAOs.
 *
 * Useful for unit tests that need a database facade without the SQLCipher
 * / Room stack. Tracks the open/close lifecycle; DAO accessors throw
 * [IllegalStateException] before [open] and after [close]/[wipe].
 */
class FakeDatabase : KeystoneDatabase {

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

    private fun clearState() {
        _trustEdgeDao.clear()
        _revocationDao.clear()
        _accountDao.clear()
        _currencyEnvelopeDao.clear()
        _communityMembershipDao.clear()
        _messageDao.clear()
    }

    private fun requireOpen() {
        check(open) { "KeystoneDatabase not open. Call open() first." }
    }
}
