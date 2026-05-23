package com.wyspr.core.currency

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerReducerTest {

    // --------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------

    private val community = ByteArray(32) { 0xAA.toByte() }
    private val founder = ByteArray(32) { 0xFF.toByte() }
    private val alicePub = ByteArray(32) { 0x11 }
    private val bobPub = ByteArray(32) { 0x22 }
    private val carolPub = ByteArray(32) { 0x33 }
    private val alice = AccountId(alicePub)
    private val bob = AccountId(bobPub)
    private val carol = AccountId(carolPub)

    private fun genesis(
        vararg recipients: Pair<ByteArray, Long>,
    ): LedgerReducer.GenesisEvent {
        val recipientList = recipients.map { (pub, amt) ->
            GenesisIssuance.Recipient(pub, amt)
        }
        val cert = GenesisIssuance(
            version = GenesisIssuance.VERSION,
            community = community,
            issuer = founder,
            recipients = recipientList,
            totalSupply = recipientList.sumOf { it.amount },
            issuedAt = 1_700_000_000L,
            nonce = ByteArray(16) { 0x01 },
            signature = ByteArray(64) { 0x02 },
        )
        return LedgerReducer.GenesisEvent(cert)
    }

    private fun transfer(
        senderPub: ByteArray,
        recipientPub: ByteArray,
        amount: Long,
        seq: Long,
        memoHash: ByteArray? = null,
    ): LedgerReducer.TransferEvent {
        val t = Transfer(
            version = Transfer.VERSION,
            community = community,
            sender = senderPub,
            recipient = recipientPub,
            amount = amount,
            seq = seq,
            memoHash = memoHash,
            issuedAt = 1_700_000_100L + seq,
            nonce = ByteArray(16) { seq.toByte() },
            signature = ByteArray(64) { 0xCC.toByte() },
        )
        return LedgerReducer.TransferEvent(t)
    }

    // --------------------------------------------------------------------
    // Happy path
    // --------------------------------------------------------------------

    @Test fun genesis_credits_recipients() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L, bobPub to 500L)
        ))
        assertEquals(1_000L, state.balanceOf(alice))
        assertEquals(500L, state.balanceOf(bob))
        assertEquals(0L, state.balanceOf(carol))
        assertTrue(state.genesisApplied)
        assertTrue(state.rejections.isEmpty())
    }

    @Test fun transfer_debits_sender_credits_recipient_advances_seq() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, bobPub, 250L, seq = 1L),
        ))
        assertEquals(750L, state.balanceOf(alice))
        assertEquals(250L, state.balanceOf(bob))
        assertEquals(1L, state.seqOf(alice))
        assertTrue(state.rejections.isEmpty())
    }

    @Test fun multiple_transfers_advance_seq_monotonically() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, bobPub, 100L, seq = 1L),
            transfer(alicePub, carolPub, 200L, seq = 2L),
            transfer(alicePub, bobPub, 50L, seq = 3L),
        ))
        assertEquals(650L, state.balanceOf(alice))
        assertEquals(150L, state.balanceOf(bob))
        assertEquals(200L, state.balanceOf(carol))
        assertEquals(3L, state.seqOf(alice))
        assertTrue(state.rejections.isEmpty())
    }

    @Test fun zero_amount_transfer_is_legal_and_advances_seq() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 100L),
            transfer(alicePub, bobPub, 0L, seq = 1L),
        ))
        assertEquals(100L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(1L, state.seqOf(alice))
        assertTrue(state.rejections.isEmpty())
    }

    // --------------------------------------------------------------------
    // Rejection rules
    // --------------------------------------------------------------------

    @Test fun duplicate_genesis_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 100L),
            genesis(bobPub to 200L),
        ))
        assertEquals(100L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(1, state.rejections.size)
        assertEquals(LedgerReducer.Reason.DUPLICATE_GENESIS, state.rejections[0].reason)
    }

    @Test fun unknown_sender_rejected_with_specific_reason() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(carolPub, bobPub, 50L, seq = 1L),
        ))
        assertEquals(0L, state.balanceOf(carol))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(1, state.rejections.size)
        assertEquals(LedgerReducer.Reason.UNKNOWN_SENDER, state.rejections[0].reason)
    }

    @Test fun seq_gap_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, bobPub, 50L, seq = 2L),
        ))
        assertEquals(1_000L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(0L, state.seqOf(alice))
        assertEquals(LedgerReducer.Reason.SEQ_GAP, state.rejections[0].reason)
    }

    @Test fun seq_regression_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, bobPub, 50L, seq = 1L),
            transfer(alicePub, bobPub, 50L, seq = 1L), // same seq again
        ))
        assertEquals(950L, state.balanceOf(alice))
        assertEquals(50L, state.balanceOf(bob))
        assertEquals(1L, state.seqOf(alice))
        assertEquals(1, state.rejections.size)
        assertEquals(LedgerReducer.Reason.SEQ_REGRESSION, state.rejections[0].reason)
    }

    @Test fun overspend_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 100L),
            transfer(alicePub, bobPub, 200L, seq = 1L),
        ))
        assertEquals(100L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(0L, state.seqOf(alice))
        assertEquals(LedgerReducer.Reason.OVERSPEND, state.rejections[0].reason)
    }

    @Test fun self_transfer_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, alicePub, 50L, seq = 1L),
        ))
        assertEquals(1_000L, state.balanceOf(alice))
        assertEquals(0L, state.seqOf(alice))
        assertEquals(LedgerReducer.Reason.SELF_TRANSFER, state.rejections[0].reason)
    }

    // --------------------------------------------------------------------
    // Slashing
    // --------------------------------------------------------------------

    @Test fun slash_zeroes_balance_and_marks_slashed() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            LedgerReducer.SlashEvent(alice),
        ))
        assertEquals(0L, state.balanceOf(alice))
        assertTrue(state.isSlashed(alice))
    }

    @Test fun transfer_from_slashed_sender_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            LedgerReducer.SlashEvent(alice),
            transfer(alicePub, bobPub, 50L, seq = 1L),
        ))
        assertEquals(0L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(1, state.rejections.size)
        assertEquals(LedgerReducer.Reason.SENDER_SLASHED, state.rejections[0].reason)
    }

    @Test fun transfer_to_slashed_recipient_rejected() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L, bobPub to 100L),
            LedgerReducer.SlashEvent(bob),
            transfer(alicePub, bobPub, 50L, seq = 1L),
        ))
        assertEquals(1_000L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertEquals(LedgerReducer.Reason.RECIPIENT_SLASHED, state.rejections[0].reason)
    }

    @Test fun duplicate_slash_is_idempotent_not_a_rejection() {
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 100L),
            LedgerReducer.SlashEvent(alice),
            LedgerReducer.SlashEvent(alice),
        ))
        assertTrue(state.isSlashed(alice))
        assertTrue(state.rejections.isEmpty())
    }

    @Test fun past_transfers_to_now_slashed_account_stand() {
        // Bob is paid by Alice, then Bob gets slashed later. Bob's
        // pre-slash balance is wiped (he can't spend it), but the past
        // transfer itself is not unwound — Alice's balance is unchanged.
        val state = LedgerReducer.fold(listOf(
            genesis(alicePub to 1_000L),
            transfer(alicePub, bobPub, 250L, seq = 1L),
            LedgerReducer.SlashEvent(bob),
        ))
        assertEquals(750L, state.balanceOf(alice))
        assertEquals(0L, state.balanceOf(bob))
        assertTrue(state.isSlashed(bob))
        assertFalse(state.isSlashed(alice))
    }

    // --------------------------------------------------------------------
    // Purity
    // --------------------------------------------------------------------

    @Test fun apply_returns_new_state_does_not_mutate() {
        val initial = LedgerReducer.fold(listOf(genesis(alicePub to 100L)))
        val after = LedgerReducer.apply(initial, transfer(alicePub, bobPub, 50L, 1L))
        // Original state is unchanged.
        assertEquals(100L, initial.balanceOf(alice))
        assertEquals(0L, initial.balanceOf(bob))
        // New state has the transfer applied.
        assertEquals(50L, after.balanceOf(alice))
        assertEquals(50L, after.balanceOf(bob))
    }

    @Test fun empty_state_is_well_defined() {
        val s = LedgerReducer.State.EMPTY
        assertEquals(0L, s.balanceOf(alice))
        assertEquals(0L, s.seqOf(alice))
        assertFalse(s.isSlashed(alice))
        assertFalse(s.genesisApplied)
    }
}
