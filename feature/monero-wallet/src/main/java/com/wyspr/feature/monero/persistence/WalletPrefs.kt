package com.wyspr.feature.monero.persistence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight prefs for the Monero wallet feature.
 *
 * Sprint W4 settings tracked here:
 *  - `seedBackupAcknowledged` — flips true the first time the user
 *    completes the reveal-and-confirm flow. Drives the
 *    "back up your seed words" banner on the wallet screen — the
 *    banner persists until acknowledged. Reactive so the banner
 *    disappears the moment confirm lands.
 *
 * Backed by [EncryptedSharedPreferences] (same pattern as
 * `core:ui/settings/MailboxSettings`) so the flag isn't
 * forensically observable on disk. Falls back to plain prefs if
 * the security library can't initialise — the worst-case leak is
 * "this device might have already shown the user their seed",
 * which is not load-bearing.
 */
@Singleton
class WalletPrefs @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = openPrefs(context)
    private val _seedBackupAcknowledged = MutableStateFlow(
        prefs.getBoolean(KEY_SEED_BACKUP_ACKED, false),
    )
    val seedBackupAcknowledged: StateFlow<Boolean> = _seedBackupAcknowledged.asStateFlow()

    fun acknowledgeSeedBackup() {
        prefs.edit().putBoolean(KEY_SEED_BACKUP_ACKED, true).apply()
        _seedBackupAcknowledged.value = true
    }

    /** Reset to "not yet acknowledged" — used after a restore-from-seed. */
    fun resetSeedBackup() {
        prefs.edit().putBoolean(KEY_SEED_BACKUP_ACKED, false).apply()
        _seedBackupAcknowledged.value = false
    }

    private fun openPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { t ->
            Log.w(TAG, "encrypted prefs unavailable; falling back to plain", t)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private companion object {
        const val TAG = "WalletPrefs"
        const val PREFS_NAME = "monero_wallet_prefs"
        const val KEY_SEED_BACKUP_ACKED = "seed_backup_acknowledged"
    }
}
