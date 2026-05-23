package com.wyspr.core.currency

import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.dao.AccountDao
import com.wyspr.core.database.dao.CurrencyEnvelopeDao
import com.wyspr.core.database.dao.RevocationDao
import com.wyspr.core.database.dao.TrustEdgeDao
import com.wyspr.core.database.entities.AccountEntity
import com.wyspr.core.database.entities.CurrencyEnvelopeEntity
import com.wyspr.core.database.entities.RevocationEntity
import com.wyspr.core.database.entities.TrustEdgeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Unit tests with in-memory fakes for WysprDatabase + DAOs +
 * KeystoreManager. Exercises the WalletService logic without Room or
 * SQLCipher — the persistence layer is hammered separately by
 * instrumented tests on device.
 */
class WalletServiceTest {

    // --------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------

    private val community = ByteArray(32) { 0xAA.toByte() }

    private val myPub = ByteArray(32) { 0x11 }
    private val bobPub = ByteArray(32) { 0x22 }
    private val carolPub = ByteArray(32) { 0x33 }
    private val me = AccountId(myPub)
    private val bob = AccountId(bobPub)
    private val carol = AccountId(carolPub)

    private val founderPub = ByteArray(32) { 0xFF.toByte() }

    private fun genesisCert(vararg recipients: Pair<ByteArray, Long>): GenesisIssuance {
        val list = recipients.map { (pub, amt) -> GenesisIssuance.Recipient(pub, amt) }
        return GenesisIssuance(
            version = GenesisIssuance.VERSION,
            community = community,
            issuer = founderPub,
            recipients = list,
            totalSupply = list.sumOf { it.amount },
            issuedAt = 1_700_000_000L,
            nonce = ByteArray(16) { 0x01 },
            signature = ByteArray(64) { 0x02 },
        )
    }

    private fun makeService(): TestRig {
        val keystore = FakeKeystore(myPub)
        val database = FakeDatabase()
        var now = 1_700_000_500L
        val service = WalletService(
            keystore = keystore,
            database = database,
            clock = { now },
            random = java.security.SecureRandom(),
        )
        return TestRig(service, database, keystore) { now = it }
    }

    private class TestRig(
        val service: WalletService,
        val database: FakeDatabase,
        val keystore: FakeKeystore,
        val setClock: (Long) -> Unit,
    )

    // --------------------------------------------------------------------
    // Read surface
    // --------------------------------------------------------------------

    @Test fun me_reports_zero_balance_and_seq_one_when_fresh(): Unit = runBlocking {
        val rig = makeService()
        val info = rig.service.me()
        assertEquals(me, info.account)
        assertEquals(0L, info.balance)
        assertEquals(1L, info.nextSeq)
        assertEquals(false, info.slashed)
    }

    @Test fun balance_returns_zero_for_unknown_account(): Unit = runBlocking {
        val rig = makeService()
        assertEquals(0L, rig.service.balance(carol))
    }

    // --------------------------------------------------------------------
    // Genesis ingestion
    // --------------------------------------------------------------------

    @Test fun record_genesis_credits_recipients(): Unit = runBlocking {
        val rig = makeService()
        val out = rig.service.recordGenesis(genesisCert(myPub to 1_000L, bobPub to 500L))
        assertIs<WalletService.Ingested>(out)
        assertEquals(1_000L, rig.service.balance(me))
        assertEquals(500L, rig.service.balance(bob))
    }

    @Test fun duplicate_genesis_same_content_returns_duplicate(): Unit = runBlocking {
        val rig = makeService()
        val cert = genesisCert(myPub to 100L)
        rig.service.recordGenesis(cert)
        val again = rig.service.recordGenesis(cert)
        assertIs<WalletService.Duplicate>(again)
        // Balance didn't double.
        assertEquals(100L, rig.service.balance(me))
    }

    // --------------------------------------------------------------------
    // Send
    // --------------------------------------------------------------------

    @Test fun send_without_balance_returns_insufficient_funds(): Unit = runBlocking {
        val rig = makeService()
        val out = rig.service.send(community, bob, 50L)
        assertIs<WalletService.InsufficientFunds>(out)
        assertEquals(0L, (out as WalletService.InsufficientFunds).balance)
        assertEquals(50L, out.requested)
    }

    @Test fun send_to_self_returns_self_transfer(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(myPub to 100L))
        val out = rig.service.send(community, me, 10L)
        assertIs<WalletService.SelfTransfer>(out)
    }

    @Test fun send_after_genesis_debits_sender_credits_recipient(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(myPub to 1_000L))
        val out = rig.service.send(community, bob, 250L)
        val sent = assertIs<WalletService.Sent>(out)
        assertEquals(myPub.toList(), sent.transfer.sender.toList())
        assertEquals(bobPub.toList(), sent.transfer.recipient.toList())
        assertEquals(250L, sent.transfer.amount)
        assertEquals(1L, sent.transfer.seq)
        assertEquals(750L, rig.service.balance(me))
        assertEquals(250L, rig.service.balance(bob))
        // Next seq should now be 2.
        assertEquals(2L, rig.service.me().nextSeq)
    }

    @Test fun consecutive_sends_advance_seq_monotonically(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(myPub to 1_000L))
        val a = rig.service.send(community, bob, 100L) as WalletService.Sent
        val b = rig.service.send(community, carol, 200L) as WalletService.Sent
        assertEquals(1L, a.transfer.seq)
        assertEquals(2L, b.transfer.seq)
        assertEquals(700L, rig.service.balance(me))
        assertEquals(100L, rig.service.balance(bob))
        assertEquals(200L, rig.service.balance(carol))
    }

    // --------------------------------------------------------------------
    // Inbound transfer ingestion
    // --------------------------------------------------------------------

    @Test fun record_inbound_transfer_credits_recipient(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(bobPub to 500L))
        val tx = inboundTransferFrom(bobPub, 1L, recipient = myPub, amount = 200L)
        val out = rig.service.recordInboundTransfer(tx)
        assertIs<WalletService.Ingested>(out)
        assertEquals(200L, rig.service.balance(me))
        assertEquals(300L, rig.service.balance(bob))
    }

    @Test fun duplicate_inbound_transfer_returns_duplicate(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(bobPub to 500L))
        val tx = inboundTransferFrom(bobPub, 1L, recipient = myPub, amount = 100L)
        rig.service.recordInboundTransfer(tx)
        val again = rig.service.recordInboundTransfer(tx)
        assertIs<WalletService.Duplicate>(again)
        // Balance wasn't double-applied.
        assertEquals(100L, rig.service.balance(me))
    }

    @Test fun double_spend_detected_when_bodies_differ(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(bobPub to 500L))
        val a = inboundTransferFrom(bobPub, 1L, recipient = myPub, amount = 100L)
        val b = inboundTransferFrom(bobPub, 1L, recipient = carolPub, amount = 100L) // same seq
        rig.service.recordInboundTransfer(a)
        val out = rig.service.recordInboundTransfer(b)
        val ds = assertIs<WalletService.DoubleSpend>(out)
        // existing should be the first one we saw (paid me), incoming the conflicting one.
        assertEquals(myPub.toList(), ds.existing.recipient.toList())
        assertEquals(carolPub.toList(), ds.incoming.recipient.toList())
    }

    // --------------------------------------------------------------------
    // Slashing
    // --------------------------------------------------------------------

    @Test fun marking_account_slashed_blocks_them_as_sender_recipient(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(myPub to 1_000L, bobPub to 100L))
        rig.service.markAccountSlashed(bob)
        // Balance zeroed.
        assertEquals(0L, rig.service.balance(bob))
        // Future send to bob still goes through at the wallet layer — the
        // ledger reducer / sync layer enforces the recipient-slashed rule.
        // What we verify here is the cache state.
        assertTrue(rig.database.accounts.values.any { it.pub.contentEquals(bobPub) && it.slashed })
    }

    @Test fun marking_unknown_account_slashed_creates_slashed_row(): Unit = runBlocking {
        val rig = makeService()
        rig.service.markAccountSlashed(carol)
        val row = rig.database.accounts.values.firstOrNull { it.pub.contentEquals(carolPub) }
        assertTrue(row != null && row.slashed && row.balanceCached == 0L)
    }

    // --------------------------------------------------------------------
    // Recompute
    // --------------------------------------------------------------------

    @Test fun recompute_balances_matches_incremental_cache(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(myPub to 1_000L, bobPub to 200L))
        rig.service.send(community, bob, 250L)
        val inbound = inboundTransferFrom(bobPub, 1L, recipient = carolPub, amount = 100L)
        rig.service.recordInboundTransfer(inbound)

        val before = mapOf(
            me to rig.service.balance(me),
            bob to rig.service.balance(bob),
            carol to rig.service.balance(carol),
        )
        val state = rig.service.recomputeBalances(community)
        val after = mapOf(
            me to rig.service.balance(me),
            bob to rig.service.balance(bob),
            carol to rig.service.balance(carol),
        )
        assertEquals(before, after)
        assertEquals(750L, state.balanceOf(me))
        assertEquals(350L, state.balanceOf(bob))
        assertEquals(100L, state.balanceOf(carol))
    }

    @Test fun recompute_after_unordered_inserts_still_correct(): Unit = runBlocking {
        val rig = makeService()
        rig.service.recordGenesis(genesisCert(bobPub to 1_000L))
        // Bob sends seq=2 to me before seq=1 — incremental cache may be
        // wrong, but recompute MUST converge to the right answer.
        val tx2 = inboundTransferFrom(bobPub, 2L, recipient = myPub, amount = 100L)
        val tx1 = inboundTransferFrom(bobPub, 1L, recipient = carolPub, amount = 200L)
        rig.service.recordInboundTransfer(tx2)
        rig.service.recordInboundTransfer(tx1)

        val state = rig.service.recomputeBalances(community)
        // Bob: 1000 - 200 (seq=1) - 100 (seq=2) = 700
        assertEquals(700L, state.balanceOf(bob))
        assertEquals(100L, state.balanceOf(me))
        assertEquals(200L, state.balanceOf(carol))
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    private fun inboundTransferFrom(
        senderPub: ByteArray,
        seq: Long,
        recipient: ByteArray,
        amount: Long,
    ): Transfer = Transfer(
        version = Transfer.VERSION,
        community = community,
        sender = senderPub,
        recipient = recipient,
        amount = amount,
        seq = seq,
        memoHash = null,
        issuedAt = 1_700_000_000L + seq,
        nonce = ByteArray(16) { (0xA0 + seq.toInt()).toByte() },
        signature = ByteArray(64) { 0xBB.toByte() },
    )

    // --------------------------------------------------------------------
    // Fakes
    // --------------------------------------------------------------------

    private class FakeKeystore(private val pub: ByteArray) : KeystoreManager {
        override val backing = KeystoreManager.Backing.TEE
        private val subkey = ByteArray(32) { 0x99.toByte() }
        private val handle = object : KeystoreManager.IdentityKeyHandle {
            override val publicKey: ByteArray get() = pub.copyOf()
            override val alias: String = "test.identity"
        }
        override fun loadOrCreateIdentityKey() = handle
        override fun deriveSubkey(info: ByteArray, length: Int): ByteArray =
            subkey.copyOf(length)
        override fun sign(message: ByteArray): ByteArray {
            // Deterministic stub signature — not cryptographically valid;
            // the WalletService never verifies its own outbound signatures.
            val sig = ByteArray(64)
            for (i in sig.indices) sig[i] = (message[i % message.size].toInt() xor i).toByte()
            return sig
        }

        override fun deriveStaticX25519(): Pair<ByteArray, ByteArray> {
            // Not exercised — WalletService never speaks Noise.
            throw NotImplementedError("WalletService tests do not need X25519")
        }
    }

    private class FakeDatabase : WysprDatabase {
        override var isOpen: Boolean = true
            private set
        override suspend fun open() { isOpen = true }
        override suspend fun close() { isOpen = false }
        override suspend fun wipe() {
            accounts.clear()
            envelopes.clear()
            isOpen = false
        }

        val accounts = mutableMapOf<String, AccountEntity>()
        val envelopes = mutableListOf<CurrencyEnvelopeEntity>()

        override val accountDao: AccountDao = FakeAccountDao(accounts)
        override val currencyEnvelopeDao: CurrencyEnvelopeDao = FakeEnvelopeDao(envelopes)

        // Not exercised by these tests.
        override val trustEdgeDao: TrustEdgeDao = object : TrustEdgeDao {
            override suspend fun upsert(edge: TrustEdgeEntity) = throw NotImplementedError()
            override suspend fun all(): List<TrustEdgeEntity> = throw NotImplementedError()
            override suspend fun delete(from: ByteArray, to: ByteArray) = throw NotImplementedError()
            override suspend fun byToPub(toPub: ByteArray): TrustEdgeEntity? = throw NotImplementedError()
            override suspend fun peerOnionForEndpoints(a: ByteArray, b: ByteArray): String? =
                throw NotImplementedError()
            override suspend fun count(): Int = throw NotImplementedError()
        }
        override val revocationDao: RevocationDao = object : RevocationDao {
            override suspend fun upsert(revocation: RevocationEntity) = throw NotImplementedError()
            override suspend fun all(): List<RevocationEntity> = throw NotImplementedError()
            override suspend fun byTarget(target: ByteArray): List<RevocationEntity> = throw NotImplementedError()
        }
        override val communityMembershipDao: com.wyspr.core.database.dao.CommunityMembershipDao =
            object : com.wyspr.core.database.dao.CommunityMembershipDao {
                override suspend fun upsert(row: com.wyspr.core.database.entities.CommunityMembershipEntity) =
                    throw NotImplementedError()
                override suspend fun firstOrNull(): com.wyspr.core.database.entities.CommunityMembershipEntity? =
                    throw NotImplementedError()
                override suspend fun all(): List<com.wyspr.core.database.entities.CommunityMembershipEntity> =
                    throw NotImplementedError()
                override suspend fun count(): Int = throw NotImplementedError()
                override suspend fun deleteAll() = throw NotImplementedError()
            }
        // Messaging is not exercised in WalletServiceTest, but the
        // WysprDatabase contract now requires the field. Every
        // method throws so a stray test that reaches for it fails
        // loudly instead of silently returning empty.
        override val messageDao: com.wyspr.core.database.dao.MessageDao =
            object : com.wyspr.core.database.dao.MessageDao {
                override suspend fun upsert(message: com.wyspr.core.database.entities.MessageEntity) =
                    throw NotImplementedError()
                override fun threadFlow(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun threadSnapshot(peerPub: ByteArray) = throw NotImplementedError()
                override fun latestPerThreadFlow() = throw NotImplementedError()
                override suspend fun unreadCountFor(peerPub: ByteArray) = throw NotImplementedError()
                override fun totalUnreadFlow() = throw NotImplementedError()
                override suspend fun pendingOutboundFrom(selfPub: ByteArray) = throw NotImplementedError()
                override suspend fun pendingOutboundFromTo(selfPub: ByteArray, peerPub: ByteArray) =
                    throw NotImplementedError()
                override suspend fun pendingReadAckFor(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun updateStatus(id: ByteArray, status: String) = throw NotImplementedError()
                override suspend fun bulkTransitionStatus(
                    ids: List<ByteArray>,
                    fromStatus: String,
                    newStatus: String,
                ): Int = throw NotImplementedError()
                override suspend fun bulkTransitionStatus2(
                    ids: List<ByteArray>,
                    fromStatusA: String,
                    fromStatusB: String,
                    newStatus: String,
                ): Int = throw NotImplementedError()
                override suspend fun idsWithStatus(ids: List<ByteArray>, status: String): List<ByteArray> =
                    throw NotImplementedError()
                override suspend fun delete(id: ByteArray) = throw NotImplementedError()
                override suspend fun byId(id: ByteArray) = throw NotImplementedError()
            }

        // The remaining DAOs aren't touched by WalletServiceTest but
        // the WysprDatabase interface requires them. All methods
        // throw — a stray test that reaches in fails loudly.
        override val contactDao: com.wyspr.core.database.dao.ContactDao =
            object : com.wyspr.core.database.dao.ContactDao {
                override suspend fun upsert(contact: com.wyspr.core.database.entities.ContactEntity) =
                    throw NotImplementedError()
                override suspend fun byPub(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun all() = throw NotImplementedError()
                override fun allFlow() = throw NotImplementedError()
                override suspend fun clear(peerPub: ByteArray) = throw NotImplementedError()
            }
        override val groupDao: com.wyspr.core.database.dao.GroupDao =
            object : com.wyspr.core.database.dao.GroupDao {
                override suspend fun upsert(group: com.wyspr.core.database.entities.GroupEntity) =
                    throw NotImplementedError()
                override suspend fun byId(groupId: ByteArray) = throw NotImplementedError()
                override suspend fun all() = throw NotImplementedError()
                override fun allFlow() = throw NotImplementedError()
                override suspend fun setNickname(groupId: ByteArray, nickname: String?) =
                    throw NotImplementedError()
                override suspend fun delete(groupId: ByteArray) = throw NotImplementedError()
            }
        override val groupMemberDao: com.wyspr.core.database.dao.GroupMemberDao =
            object : com.wyspr.core.database.dao.GroupMemberDao {
                override suspend fun upsert(member: com.wyspr.core.database.entities.GroupMemberEntity) =
                    throw NotImplementedError()
                override suspend fun upsertAll(members: List<com.wyspr.core.database.entities.GroupMemberEntity>) =
                    throw NotImplementedError()
                override suspend fun activeForGroup(groupId: ByteArray) = throw NotImplementedError()
                override suspend fun allForGroup(groupId: ByteArray) = throw NotImplementedError()
                override fun allForGroupFlow(groupId: ByteArray) = throw NotImplementedError()
                override suspend fun groupsForMember(memberPub: ByteArray) = throw NotImplementedError()
                override suspend fun setStatus(groupId: ByteArray, memberPub: ByteArray, status: String) =
                    throw NotImplementedError()
                override suspend fun delete(groupId: ByteArray, memberPub: ByteArray) =
                    throw NotImplementedError()
            }
        override val groupMessageDao: com.wyspr.core.database.dao.GroupMessageDao =
            object : com.wyspr.core.database.dao.GroupMessageDao {
                override suspend fun upsert(message: com.wyspr.core.database.entities.GroupMessageEntity) =
                    throw NotImplementedError()
                override fun threadFlow(groupId: ByteArray) = throw NotImplementedError()
                override suspend fun threadSnapshot(groupId: ByteArray) = throw NotImplementedError()
                override fun latestPerGroupFlow() = throw NotImplementedError()
                override suspend fun pendingOutboundFrom(selfPub: ByteArray) = throw NotImplementedError()
                override fun totalUnreadFlow() = throw NotImplementedError()
                override suspend fun updateStatus(id: ByteArray, status: String) = throw NotImplementedError()
                override suspend fun exists(id: ByteArray): Boolean = throw NotImplementedError()
                override suspend fun delete(id: ByteArray) = throw NotImplementedError()
            }
        override val userProfileDao: com.wyspr.core.database.dao.UserProfileDao =
            object : com.wyspr.core.database.dao.UserProfileDao {
                override suspend fun upsert(profile: com.wyspr.core.database.entities.UserProfileEntity) =
                    throw NotImplementedError()
                override suspend fun get() = throw NotImplementedError()
                override fun getFlow() = throw NotImplementedError()
            }
        override val mailboxBindingDao: com.wyspr.core.database.dao.MailboxBindingDao =
            object : com.wyspr.core.database.dao.MailboxBindingDao {
                override suspend fun upsert(binding: com.wyspr.core.database.entities.MailboxBindingEntity) =
                    throw NotImplementedError()
                override suspend fun forOwner(ownerPub: ByteArray) = throw NotImplementedError()
                override fun forOwnerFlow(ownerPub: ByteArray) = throw NotImplementedError()
                override suspend fun all() = throw NotImplementedError()
                override suspend fun deleteForOwner(ownerPub: ByteArray) = throw NotImplementedError()
                override suspend fun deleteForOwnerHost(ownerPub: ByteArray, mailboxPub: ByteArray) =
                    throw NotImplementedError()
                override suspend fun deleteAll() = throw NotImplementedError()
            }
        override val mailboxStoredDao: com.wyspr.core.database.dao.MailboxStoredDao =
            object : com.wyspr.core.database.dao.MailboxStoredDao {
                override suspend fun insert(stored: com.wyspr.core.database.entities.MailboxStoredEntity): Long =
                    throw NotImplementedError()
                override suspend fun forRecipientSince(toPub: ByteArray, sinceCursor: Long, limit: Int) =
                    throw NotImplementedError()
                override suspend fun envelopeIdsForRecipient(toPub: ByteArray) = throw NotImplementedError()
                override suspend fun deleteIds(ids: List<ByteArray>) = throw NotImplementedError()
                override suspend fun expireBefore(nowSeconds: Long): Int = throw NotImplementedError()
                override suspend fun allOldestFirstForEviction() = throw NotImplementedError()
                override fun totalSizeBytesFlow() = throw NotImplementedError()
                override fun totalCountFlow() = throw NotImplementedError()
                override fun uniqueRecipientsFlow() = throw NotImplementedError()
                override suspend fun purgeAll() = throw NotImplementedError()
            }
        override val mailboxPullCursorDao: com.wyspr.core.database.dao.MailboxPullCursorDao =
            object : com.wyspr.core.database.dao.MailboxPullCursorDao {
                override suspend fun getCursor(mailboxPub: ByteArray) = throw NotImplementedError()
                override suspend fun upsert(entity: com.wyspr.core.database.entities.MailboxPullCursorEntity) =
                    throw NotImplementedError()
                override suspend fun clear(mailboxPub: ByteArray) = throw NotImplementedError()
            }
        override val handshakeQuarantineDao: com.wyspr.core.database.dao.HandshakeQuarantineDao =
            object : com.wyspr.core.database.dao.HandshakeQuarantineDao {
                override suspend fun forPeer(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun isQuarantined(peerPub: ByteArray, nowSeconds: Long) =
                    throw NotImplementedError()
                override suspend fun upsert(entity: com.wyspr.core.database.entities.HandshakeQuarantineEntity) =
                    throw NotImplementedError()
                override suspend fun sweepExpired(nowSeconds: Long): Int = throw NotImplementedError()
            }
        override val seenCertNonceDao: com.wyspr.core.database.dao.SeenCertNonceDao =
            object : com.wyspr.core.database.dao.SeenCertNonceDao {
                override suspend fun isSeen(nonce: ByteArray) = throw NotImplementedError()
                override suspend fun mark(entity: com.wyspr.core.database.entities.SeenCertNonceEntity): Long =
                    throw NotImplementedError()
                override suspend fun sweepBefore(cutoffSeconds: Long): Int = throw NotImplementedError()
            }
        override val peerPaymentAddressDao: com.wyspr.core.database.dao.PeerPaymentAddressDao =
            object : com.wyspr.core.database.dao.PeerPaymentAddressDao {
                override suspend fun upsert(entity: com.wyspr.core.database.entities.PeerPaymentAddressEntity) =
                    throw NotImplementedError()
                override suspend fun currentForPeer(peerPub: ByteArray, chain: String) =
                    throw NotImplementedError()
                override fun currentForPeerFlow(peerPub: ByteArray, chain: String) =
                    throw NotImplementedError()
                override suspend fun allForPeer(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun revoke(peerPub: ByteArray, chain: String, address: String, nowSeconds: Long) =
                    throw NotImplementedError()
                override suspend fun deleteForPeer(peerPub: ByteArray) = throw NotImplementedError()
                override suspend fun deleteAll() = throw NotImplementedError()
            }
        override val peerSubAddressMintDao: com.wyspr.core.database.dao.PeerSubAddressMintDao =
            object : com.wyspr.core.database.dao.PeerSubAddressMintDao {
                override suspend fun forPeer(peerPub: ByteArray, chain: String) =
                    throw NotImplementedError()
                override suspend fun all() = throw NotImplementedError()
                override suspend fun upsert(entity: com.wyspr.core.database.entities.PeerSubAddressMintEntity) =
                    throw NotImplementedError()
            }
        override val addressBookDao: com.wyspr.core.database.dao.AddressBookDao =
            object : com.wyspr.core.database.dao.AddressBookDao {
                override suspend fun upsert(entity: com.wyspr.core.database.entities.AddressBookEntity) =
                    throw NotImplementedError()
                override fun forChainFlow(chain: String) = throw NotImplementedError()
                override suspend fun forChain(chain: String) = throw NotImplementedError()
                override suspend fun touch(chain: String, label: String, nowSeconds: Long) =
                    throw NotImplementedError()
                override suspend fun delete(chain: String, label: String) =
                    throw NotImplementedError()
            }
    }

    private class FakeAccountDao(private val rows: MutableMap<String, AccountEntity>) : AccountDao {
        private fun key(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        override suspend fun upsert(account: AccountEntity) { rows[key(account.pub)] = account }
        override suspend fun insertNew(account: AccountEntity) {
            val k = key(account.pub)
            if (rows.containsKey(k)) throw IllegalStateException("PK conflict")
            rows[k] = account
        }
        override suspend fun update(account: AccountEntity) { rows[key(account.pub)] = account }
        override suspend fun get(pub: ByteArray): AccountEntity? = rows[key(pub)]
        override suspend fun all(): List<AccountEntity> = rows.values.toList()
        override suspend fun count(): Int = rows.size
        override suspend fun markSlashed(pub: ByteArray) {
            rows[key(pub)]?.let { rows[key(pub)] = it.copy(slashed = true, balanceCached = 0L) }
        }
        override suspend fun deleteAll() { rows.clear() }
    }

    private class FakeEnvelopeDao(
        private val rows: MutableList<CurrencyEnvelopeEntity>,
    ) : CurrencyEnvelopeDao {
        private fun matchKey(e: CurrencyEnvelopeEntity, community: ByteArray, typeTag: Int, primaryKey: ByteArray) =
            e.community.contentEquals(community) && e.typeTag == typeTag && e.primaryKey.contentEquals(primaryKey)

        override suspend fun insert(envelope: CurrencyEnvelopeEntity) {
            val collision = rows.any { matchKey(it, envelope.community, envelope.typeTag, envelope.primaryKey) }
            if (collision) throw IllegalStateException("PK collision (fake SQLiteConstraintException)")
            rows.add(envelope)
        }
        override suspend fun replaceLocal(envelope: CurrencyEnvelopeEntity) {
            rows.removeAll { matchKey(it, envelope.community, envelope.typeTag, envelope.primaryKey) }
            rows.add(envelope)
        }
        override suspend fun get(community: ByteArray, typeTag: Int, primaryKey: ByteArray): CurrencyEnvelopeEntity? =
            rows.firstOrNull { matchKey(it, community, typeTag, primaryKey) }
        override suspend fun byType(community: ByteArray, typeTag: Int) =
            rows.filter { it.community.contentEquals(community) && it.typeTag == typeTag }
        override suspend fun byTypeOrdered(community: ByteArray, typeTag: Int) =
            byType(community, typeTag).sortedBy { it.observedAt }
        override suspend fun forCommunity(community: ByteArray) =
            rows.filter { it.community.contentEquals(community) }
        override suspend fun countForCommunity(community: ByteArray): Int =
            forCommunity(community).size
        override suspend fun count(): Int = rows.size
        override suspend fun deleteAll() { rows.clear() }
    }
}
