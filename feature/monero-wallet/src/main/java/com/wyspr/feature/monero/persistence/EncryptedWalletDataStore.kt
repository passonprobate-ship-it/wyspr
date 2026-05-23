package com.wyspr.feature.monero.persistence

import android.content.Context
import android.util.Log
import com.wyspr.feature.monero.MoneroKeyStore
import im.molly.monero.sdk.WalletDataStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * mollyim [WalletDataStore] backed by an encrypted file in app-
 * private storage. The wallet bytes mollyim hands us — seed,
 * cached chain state, account index — are AES-GCM encrypted with a
 * 32-byte key derived from the Wyspr identity via
 * [MoneroKeyStore.deriveWalletWrapKey].
 *
 * Wire format on disk (single concatenated blob):
 *
 *     [ u8 version = 0x01 ][ 12 bytes IV ][ ciphertext + 16-byte GCM tag ]
 *
 * - Version byte lets us evolve the format later without losing
 *   existing wallets.
 * - Fresh random IV per write (never reuse — AES-GCM nonce-reuse
 *   catastrophically breaks confidentiality + integrity).
 * - GCM auth tag is appended to the ciphertext by JCE; we don't
 *   separate it.
 *
 * Identity wipe semantics: wiping the device's keystore identity
 * (e.g. via `AndroidKeystoreManager.reset()`) makes
 * [MoneroKeyStore.deriveWalletWrapKey] derive a different value,
 * which renders this file unreadable — the Monero seed is
 * effectively destroyed in lockstep with the messaging identity.
 *
 * Single-wallet for v1 — one file at `<filesDir>/monero/wallet.dat`.
 * Multi-wallet (e.g. per-community) would parameterise the filename.
 */
class EncryptedWalletDataStore(
    private val context: Context,
    private val keyStore: MoneroKeyStore,
) : WalletDataStore {

    private val file: File by lazy {
        File(context.filesDir, "monero").apply { mkdirs() }
            .let { File(it, "wallet.dat") }
    }

    /** True iff a wallet file is currently on disk. UI uses this to choose Create vs Restore. */
    fun exists(): Boolean = file.exists() && file.length() > MIN_FILE_BYTES

    /** Hard delete — used to forget a wallet without affecting the keystore identity. */
    fun delete(): Boolean = file.delete()

    override suspend fun load(): InputStream {
        val raw = try {
            file.readBytes()
        } catch (_: FileNotFoundException) {
            // mollyim's createNewWallet uses InMemoryWalletDataStore's
            // first call to load() to seed the (empty) cache; for our
            // disk-backed store, treat "no file" as empty bytes.
            Log.d(TAG, "load: no wallet file yet; returning empty")
            return ByteArrayInputStream(ByteArray(0))
        }
        if (raw.size < MIN_FILE_BYTES) {
            Log.w(TAG, "load: wallet file too small (${raw.size} bytes); treating as empty")
            return ByteArrayInputStream(ByteArray(0))
        }
        val version = raw[0].toInt() and 0xFF
        require(version == VERSION) { "unsupported wallet-blob version: $version" }
        val iv = raw.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = raw.copyOfRange(1 + IV_BYTES, raw.size)
        val plaintext = decrypt(iv, ciphertext)
        Log.d(TAG, "load: opened wallet (${plaintext.size} plaintext bytes)")
        return ByteArrayInputStream(plaintext)
    }

    override suspend fun save(writer: (OutputStream) -> Unit, overwrite: Boolean) {
        if (!overwrite && exists()) {
            error("save: overwrite=false but wallet already exists on disk")
        }
        // Capture mollyim's wallet bytes into memory, then encrypt
        // and persist atomically. Wallet blobs are bounded (a few
        // MB at most for a fully-synced wallet), so the in-memory
        // staging is fine.
        val plaintextBuf = java.io.ByteArrayOutputStream()
        writer(plaintextBuf)
        val plaintext = plaintextBuf.toByteArray()
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = encrypt(iv, plaintext)
        val out = ByteArray(1 + IV_BYTES + ciphertext.size)
        out[0] = VERSION.toByte()
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + IV_BYTES)
        // Atomic write: stage to .tmp, then rename. A process death
        // mid-save leaves the previous wallet file intact.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(out)
        require(tmp.renameTo(file)) { "rename ${tmp.name} → ${file.name} failed" }
        Log.d(TAG, "save: wrote wallet (${plaintext.size} plaintext, ${out.size} on-disk bytes)")
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher {
        val key = keyStore.deriveWalletWrapKey()
        val spec = SecretKeySpec(key, "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, spec, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    private fun encrypt(iv: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext)

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext)

    private companion object {
        private const val TAG = "EncryptedWalletDS"
        private const val VERSION = 0x01
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        // 1 version + 12 IV + 16 GCM tag = 29; anything smaller can't
        // be a valid blob. Reject without trying to decrypt so we
        // don't surface a confusing AES error on a torn file.
        private const val MIN_FILE_BYTES = 1 + IV_BYTES + 16
    }
}
