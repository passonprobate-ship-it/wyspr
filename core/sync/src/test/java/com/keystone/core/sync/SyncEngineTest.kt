package com.keystone.core.sync

import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Convergence test for the anti-entropy engine. Two engines back two
 * in-memory repositories with different envelope sets; one [runSession]
 * on each over a paired Link should leave both repositories holding the
 * union of the original sets.
 */
class SyncEngineTest {

    private val community = ByteArray(32) { 0xAA.toByte() }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /**
     * Stores arbitrary envelopes in memory. [ingest] uses content hash
     * dedup: re-ingesting an identical row is a no-op (mirrors what the
     * production CurrencyEnvelopeDao would do on a PK collision with
     * matching body).
     */
    private class FakeRepository : SyncRepository {
        val rows = mutableMapOf<EnvelopeKey, EnvelopeRow>()
        override suspend fun haveSet(community: ByteArray): List<EnvelopeKey> =
            rows.values.filter { it.community.contentEquals(community) }
                .map { EnvelopeKey(it.typeTag, it.primaryKey) }
        override suspend fun envelopesByKeys(
            community: ByteArray,
            keys: List<EnvelopeKey>,
        ): List<EnvelopeRow> = keys.mapNotNull { k ->
            rows[k]?.takeIf { it.community.contentEquals(community) }
        }
        override suspend fun ingest(row: EnvelopeRow): Boolean {
            rows[EnvelopeKey(row.typeTag, row.primaryKey)] = row
            return true
        }
    }

    /**
     * Pair of in-memory Links. Each Link's send goes into one channel;
     * its incoming() reads from the other. Together they look exactly
     * like a duplex BLE socket to the engine.
     */
    private class LinkPair {
        private val aToB = Channel<ByteArray>(Channel.UNLIMITED)
        private val bToA = Channel<ByteArray>(Channel.UNLIMITED)

        val a: Link = ChannelLink(send = aToB, recv = bToA)
        val b: Link = ChannelLink(send = bToA, recv = aToB)
    }

    private class ChannelLink(
        private val send: Channel<ByteArray>,
        private val recv: Channel<ByteArray>,
    ) : Link {
        override val endpoint = PeerEndpoint(Transport.Kind.BluetoothLe, "test")
        override suspend fun send(frame: ByteArray) { send.send(frame) }
        // receiveAsFlow (not consumeAsFlow) so the engine can do several
        // `.firstOrNull()` reads in sequence; consumeAsFlow is single-use.
        override fun incoming(): Flow<ByteArray> = recv.receiveAsFlow()
        override suspend fun close() {}
    }

    private fun envelopeRow(typeTag: Int, key: ByteArray, body: ByteArray = ByteArray(8)): EnvelopeRow =
        EnvelopeRow(
            community = community,
            typeTag = typeTag,
            primaryKey = key,
            body = body,
            observedAt = 1_700_000_000L,
        )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun two_engines_converge_on_union() = runBlocking {
        val repoA = FakeRepository().apply {
            ingest(envelopeRow(0x12, ByteArray(40) { 0x01 }, ByteArray(10) { 0xAA.toByte() }))
            ingest(envelopeRow(0x12, ByteArray(40) { 0x02 }, ByteArray(10) { 0xBB.toByte() }))
        }
        val repoB = FakeRepository().apply {
            ingest(envelopeRow(0x12, ByteArray(40) { 0x02 }, ByteArray(10) { 0xBB.toByte() }))
            ingest(envelopeRow(0x10, ByteArray(32) { 0x99.toByte() }, ByteArray(40) { 0xCC.toByte() }))
        }
        val engineA = SyncEngine(repoA)
        val engineB = SyncEngine(repoB)
        val link = LinkPair()

        val (resultA, resultB) = listOf(
            async { engineA.runSession(link.a, community, SyncEngine.Role.Initiator) },
            async { engineB.runSession(link.b, community, SyncEngine.Role.Responder) },
        ).awaitAll()

        // Both repos now hold all three rows.
        assertEquals(3, repoA.rows.size)
        assertEquals(3, repoB.rows.size)
        // They hold the SAME three keys.
        assertEquals(repoA.rows.keys, repoB.rows.keys)

        // Each engine sent the one row the other was missing.
        assertEquals(1, resultA.sent)
        assertEquals(1, resultB.sent)
        // ...and received one in return.
        assertEquals(1, resultA.received)
        assertEquals(1, resultB.received)
        assertEquals(0, resultA.rejected)
        assertEquals(0, resultB.rejected)
    }

    @Test
    fun already_in_sync_is_a_no_op() = runBlocking {
        val shared = listOf(
            envelopeRow(0x12, ByteArray(40) { 0x01 }),
            envelopeRow(0x10, ByteArray(32) { 0x02 }),
        )
        val repoA = FakeRepository().apply { shared.forEach { ingest(it) } }
        val repoB = FakeRepository().apply { shared.forEach { ingest(it) } }
        val link = LinkPair()

        listOf(
            async { SyncEngine(repoA).runSession(link.a, community, SyncEngine.Role.Initiator) },
            async { SyncEngine(repoB).runSession(link.b, community, SyncEngine.Role.Responder) },
        ).awaitAll()

        assertEquals(2, repoA.rows.size)
        assertEquals(2, repoB.rows.size)
    }

    @Test
    fun rows_for_other_community_are_rejected() = runBlocking {
        val otherCommunity = ByteArray(32) { 0xBB.toByte() }
        val repoA = FakeRepository()
        val repoB = FakeRepository().apply {
            // A row tagged for a DIFFERENT community.
            ingest(
                EnvelopeRow(
                    community = otherCommunity,
                    typeTag = 0x12,
                    primaryKey = ByteArray(40) { 0x55 },
                    body = ByteArray(10),
                    observedAt = 0L,
                )
            )
        }
        val link = LinkPair()

        listOf(
            async { SyncEngine(repoA).runSession(link.a, community, SyncEngine.Role.Initiator) },
            async { SyncEngine(repoB).runSession(link.b, community, SyncEngine.Role.Responder) },
        ).awaitAll()

        // Repo A stays empty — repo B's row is for a different
        // community, so it's not in repo B's HaveSet for `community`
        // (the filter happens in haveSet itself).
        assertTrue(repoA.rows.isEmpty())
    }
}
