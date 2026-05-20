package com.keystone.core.database

import com.keystone.core.database.dao.AccountDao
import com.keystone.core.database.dao.CommunityMembershipDao
import com.keystone.core.database.dao.CurrencyEnvelopeDao
import com.keystone.core.database.dao.MessageDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.TrustEdgeDao

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
