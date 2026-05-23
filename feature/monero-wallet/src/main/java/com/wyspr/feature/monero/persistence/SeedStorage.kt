package com.wyspr.feature.monero.persistence

import android.content.Context
import com.wyspr.feature.monero.MoneroKeyStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted on-disk store for the wallet's 32-byte spend secret.
 *
 * mollyim's [im.molly.monero.sdk.WalletProvider.createNewWallet]
 * mints a seed internally and never gives it back to us, so the
 * "show me my seed" feature would be impossible — there'd be no
 * bytes to render as 25 words. To work around this, the wallet
 * bootstrap path uses [im.molly.monero.sdk.WalletProvider.restoreWallet]
 * with a Wyspr-generated random [im.molly.monero.sdk.SecretKey]
 * even for brand-new wallets. We keep the bytes here, encrypted
 * under the same keystore-derived key the wallet blob uses.
 *
 * On-disk format mirrors [EncryptedWalletDataStore]:
 *
 *     [ u8 version = 0x01 ][ 12 bytes IV ][ ciphertext + 16-byte GCM tag ]
 *
 * Lives in `<filesDir>/monero/seed.dat`. Wiping the keystore
 * identity makes the wrap key undecryptable, so the seed file
 * dies with the device identity — same property as the wallet
 * blob.
 */
class SeedStorage(
    private val context: Context,
    private val keyStore: MoneroKeyStore,
) {

    private val file: File by lazy {
        File(context.filesDir, "monero").apply { mkdirs() }
            .let { File(it, "seed.dat") }
    }

    fun exists(): Boolean = file.exists() && file.length() > MIN_FILE_BYTES

    /** Return the plaintext 32-byte seed, or null if no seed is on disk. */
    fun read(): ByteArray? {
        if (!exists()) return null
        val raw = file.readBytes()
        val version = raw[0].toInt() and 0xFF
        require(version == VERSION) { "unsupported seed-blob version: $version" }
        val iv = raw.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = raw.copyOfRange(1 + IV_BYTES, raw.size)
        return cipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext)
    }

    /** Encrypt + persist [seed]. Atomic via .tmp rename. */
    fun write(seed: ByteArray) {
        require(seed.size == SEED_LENGTH) { "expected $SEED_LENGTH-byte seed, got ${seed.size}" }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(seed)
        val out = ByteArray(1 + IV_BYTES + ciphertext.size)
        out[0] = VERSION.toByte()
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + IV_BYTES)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(out)
        require(tmp.renameTo(file)) { "rename ${tmp.name} → ${file.name} failed" }
    }

    /** Hard delete — used during wallet reset / identity wipe. */
    fun delete(): Boolean = file.delete()

    private fun cipher(mode: Int, iv: ByteArray): Cipher {
        val key = keyStore.deriveWalletWrapKey()
        val spec = SecretKeySpec(key, "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, spec, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    companion object {
        const val SEED_LENGTH = 32
        private const val VERSION = 0x01
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MIN_FILE_BYTES = 1 + IV_BYTES + 16
    }
}
