package com.keystone.feature.monero

import com.keystone.core.crypto.KeystoreManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the keystore subkey used to wrap Monero wallet secrets at
 * rest. Pattern matches `KEYSTONE/v1/db` (SQLCipher key) and
 * `KEYSTONE/v1/tor-hs` (Tor HSv3 key) — the wallet's spend and
 * view secrets are encrypted with a key derived deterministically
 * from the device's hardware-backed identity, so wiping the
 * identity (`AndroidKeystoreManager.reset()`) renders the wallet
 * file unrecoverable even if the on-disk ciphertext is exfiltrated.
 *
 * v0.7.0a doesn't ship the wallet file yet (the JNI engine
 * doesn't produce one), so this class is the *seam*: when the
 * engine lands, the per-wallet ciphertext is opened/closed via
 * [deriveWalletWrapKey]. Keeping the seam thin and tested early
 * avoids the situation where the keystore code is bolted on after
 * the wallet has already shipped with a weaker scheme.
 */
@Singleton
class MoneroKeyStore @Inject constructor(
    private val keystore: KeystoreManager,
) {

    /**
     * Derive the 32-byte symmetric key Keystone uses to wrap
     * Monero wallet files. Synchronous — the underlying HKDF +
     * keystore-sign call is CPU bound and ~10 ms; callers
     * dispatch to [kotlinx.coroutines.Dispatchers.Default] if
     * they need to keep the main thread free.
     *
     * The subkey label `KEYSTONE/v1/monero` is intentionally
     * versioned so a future migration (e.g. to per-account
     * subkeys for multi-wallet support) can use a different
     * label without touching this one.
     */
    fun deriveWalletWrapKey(): ByteArray {
        return keystore.deriveSubkey(SUBKEY_LABEL, OUTPUT_BYTES)
    }

    companion object {
        private val SUBKEY_LABEL = "KEYSTONE/v1/monero".encodeToByteArray()
        private const val OUTPUT_BYTES = 32
    }
}
