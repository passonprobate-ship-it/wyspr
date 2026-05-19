package com.keystone.core.currency

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CurrencyPrimaryKeyTest {

    @Test fun genesis_key_is_issuer_bytes() {
        val issuer = ByteArray(32) { 0xAA.toByte() }
        val key = CurrencyPrimaryKey.forGenesisIssuance(issuer)
        assertEquals(32, key.size)
        assertContentEquals(issuer, key)
    }

    @Test fun genesis_key_defensive_copies() {
        val issuer = ByteArray(32) { 0xAA.toByte() }
        val key = CurrencyPrimaryKey.forGenesisIssuance(issuer)
        issuer[0] = 0x00
        // Mutating the caller's buffer must not change the key.
        assertEquals(0xAA.toByte(), key[0])
    }

    @Test fun transfer_key_is_sender_then_seq_be64() {
        val sender = ByteArray(32) { 0x11 }
        val key = CurrencyPrimaryKey.forTransfer(sender, 0x01_02_03_04_05_06_07_08L)
        assertEquals(40, key.size)
        assertContentEquals(sender, key.copyOfRange(0, 32))
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            key.copyOfRange(32, 40),
        )
    }

    @Test fun service_key_is_issuer_then_period_be64() {
        val issuer = ByteArray(32) { 0x22 }
        val key = CurrencyPrimaryKey.forServiceIssuance(issuer, 1_700_000_000L)
        assertEquals(40, key.size)
        assertContentEquals(issuer, key.copyOfRange(0, 32))
    }

    @Test fun transfer_memo_matches_its_transfer_key() {
        val sender = ByteArray(32) { 0x33 }
        val tx = CurrencyPrimaryKey.forTransfer(sender, 7L)
        val memo = CurrencyPrimaryKey.forTransferMemo(sender, 7L)
        assertContentEquals(tx, memo)
    }

    @Test fun slash_key_is_target_bytes() {
        val target = ByteArray(32) { 0x44 }
        val key = CurrencyPrimaryKey.forSlash(target)
        assertContentEquals(target, key)
    }

    @Test fun distinct_inputs_yield_distinct_keys() {
        val sender = ByteArray(32) { 0x55 }
        val k1 = CurrencyPrimaryKey.forTransfer(sender, 1L)
        val k2 = CurrencyPrimaryKey.forTransfer(sender, 2L)
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test fun rejects_wrong_sized_keys() {
        assertFailsWith<IllegalArgumentException> {
            CurrencyPrimaryKey.forTransfer(ByteArray(31), 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            CurrencyPrimaryKey.forGenesisIssuance(ByteArray(33))
        }
        assertFailsWith<IllegalArgumentException> {
            CurrencyPrimaryKey.forSlash(ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            CurrencyPrimaryKey.forTransfer(ByteArray(32), -1L)
        }
    }

    @Test fun helpers_match_envelope_inputs() {
        val tx = Transfer(
            version = Transfer.VERSION,
            community = ByteArray(32),
            sender = ByteArray(32) { 0x66 },
            recipient = ByteArray(32),
            amount = 100L,
            seq = 5L,
            memoHash = null,
            issuedAt = 0L,
            nonce = ByteArray(16),
            signature = ByteArray(64),
        )
        assertContentEquals(
            CurrencyPrimaryKey.forTransfer(tx.sender, tx.seq),
            CurrencyPrimaryKey.of(tx),
        )

        val genesis = GenesisIssuance(
            version = GenesisIssuance.VERSION,
            community = ByteArray(32),
            issuer = ByteArray(32) { 0x77 },
            recipients = listOf(GenesisIssuance.Recipient(ByteArray(32), 1L)),
            totalSupply = 1L,
            issuedAt = 0L,
            nonce = ByteArray(16),
            signature = ByteArray(64),
        )
        assertContentEquals(
            CurrencyPrimaryKey.forGenesisIssuance(genesis.issuer),
            CurrencyPrimaryKey.of(genesis),
        )
    }
}
