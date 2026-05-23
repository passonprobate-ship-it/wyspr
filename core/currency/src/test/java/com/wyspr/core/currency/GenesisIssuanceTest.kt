package com.wyspr.core.currency

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GenesisIssuanceTest {

    private fun sample(
        recipients: List<GenesisIssuance.Recipient> = listOf(
            GenesisIssuance.Recipient(account = ByteArray(32) { 0x11 }, amount = 1_000L),
            GenesisIssuance.Recipient(account = ByteArray(32) { 0x22 }, amount = 2_500L),
        ),
        nonce: ByteArray = ByteArray(16) { 0x33 },
        signature: ByteArray = ByteArray(64) { 0x44 },
        issuedAt: Long = 1_700_000_000L,
    ) = GenesisIssuance(
        version = GenesisIssuance.VERSION,
        community = ByteArray(32) { 0xAA.toByte() },
        issuer = ByteArray(32) { 0xBB.toByte() },
        recipients = recipients,
        totalSupply = recipients.sumOf { it.amount },
        issuedAt = issuedAt,
        nonce = nonce,
        signature = signature,
    )

    @Test fun round_trip() {
        val cert = sample()
        val decoded = GenesisIssuance.decode(cert.encode())
        assertEquals(cert, decoded)
    }

    @Test fun encode_is_deterministic() {
        val a = sample().encode()
        val b = sample().encode()
        assertContentEquals(a, b)
    }

    @Test fun signed_bytes_excludes_signature() {
        val cert = sample()
        val signedA = cert.signedBytes()
        val differentSig = cert.copy(signature = ByteArray(64) { 0x77 })
        val signedB = differentSig.signedBytes()
        assertContentEquals(signedA, signedB)
    }

    @Test fun signed_bytes_matches_assemble_helper() {
        val cert = sample()
        val helper = GenesisIssuance.signedBytesOf(
            community = cert.community,
            issuer = cert.issuer,
            recipients = cert.recipients,
            totalSupply = cert.totalSupply,
            issuedAt = cert.issuedAt,
            nonce = cert.nonce,
        )
        assertContentEquals(cert.signedBytes(), helper)
    }

    @Test fun signed_bytes_changes_with_content() {
        val a = sample().signedBytes()
        val b = sample(issuedAt = 1_700_000_001L).signedBytes()
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun rejects_supply_mismatch() {
        assertFailsWith<IllegalArgumentException> {
            GenesisIssuance(
                version = 1,
                community = ByteArray(32),
                issuer = ByteArray(32),
                recipients = listOf(GenesisIssuance.Recipient(ByteArray(32), 100)),
                totalSupply = 200, // mismatched
                issuedAt = 0,
                nonce = ByteArray(16),
                signature = ByteArray(64),
            )
        }
    }

    @Test fun rejects_wrong_field_sizes() {
        assertFailsWith<IllegalArgumentException> {
            sample().copy(community = ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException> {
            sample().copy(issuer = ByteArray(33))
        }
        assertFailsWith<IllegalArgumentException> {
            sample().copy(nonce = ByteArray(15))
        }
        assertFailsWith<IllegalArgumentException> {
            sample().copy(signature = ByteArray(63))
        }
    }

    @Test fun rejects_empty_recipients() {
        assertFailsWith<IllegalArgumentException> {
            GenesisIssuance(
                version = 1,
                community = ByteArray(32),
                issuer = ByteArray(32),
                recipients = emptyList(),
                totalSupply = 0,
                issuedAt = 0,
                nonce = ByteArray(16),
                signature = ByteArray(64),
            )
        }
    }

    @Test fun decode_rejects_truncated_bytes() {
        val good = sample().encode()
        val truncated = good.copyOf(good.size - 1)
        assertFailsWith<IllegalArgumentException> {
            GenesisIssuance.decode(truncated)
        }
    }

    @Test fun decode_rejects_wrong_kind_tag() {
        val cert = sample()
        val encoded = cert.encode()
        // Byte layout: [0] array header, [1] version uint(1), [2] kind uint(0).
        // Flip kind to 1 (SERVICE) and confirm GENESIS decoder rejects it.
        encoded[2] = 0x01
        // require() throws IllegalArgumentException, not IllegalStateException.
        assertFailsWith<IllegalArgumentException> {
            GenesisIssuance.decode(encoded)
        }
    }
}
