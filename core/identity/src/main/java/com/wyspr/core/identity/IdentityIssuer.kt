package com.wyspr.core.identity

import com.wyspr.core.crypto.KeystoreManager

/**
 * Bridges hardware key material into the public Identity model.
 *
 * Identity is the in-app handle to "who I am on this device": a public
 * key plus the keystore alias that backs the matching private key. The
 * private key itself never appears in this module — it stays in the
 * keystore, accessible only through the [KeystoreManager.sign] surface.
 */
object IdentityIssuer {

    /**
     * Load or create the local identity. Idempotent: calling this on every
     * cold start returns the same identity as long as the user hasn't
     * cleared app data or uninstalled the app.
     */
    fun issue(keystore: KeystoreManager): Identity {
        val handle = keystore.loadOrCreateIdentityKey()
        return Identity(
            publicKey = PublicKey(handle.publicKey),
            keystoreAlias = handle.alias,
        )
    }
}
