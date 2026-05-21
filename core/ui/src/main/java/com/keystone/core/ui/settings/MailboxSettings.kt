package com.keystone.core.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preference store for mailbox-mode state.
 *
 * Two settings exposed:
 *  - `hostEnabled` — whether this device is acting as a mailbox for the
 *    community. Drives `MailboxHost` start/stop. Default off.
 *  - `storageCapBytes` — soft cap on bytes held while hosting. Default
 *    50 MB per docs/MAILBOX.md. Not exposed in UI for v1 (baked
 *    default); kept as a setting so the host service can read one
 *    canonical source rather than re-deriving the constant.
 *
 * No UI exposes the cap in v1 — see docs/MAILBOX.md "no other settings".
 * If we surface it later, the storage exists already.
 */
class MailboxSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hostEnabled = MutableStateFlow(prefs.getBoolean(KEY_HOST, false))
    val hostEnabled: StateFlow<Boolean> = _hostEnabled.asStateFlow()

    private val _storageCapBytes = MutableStateFlow(
        prefs.getLong(KEY_CAP, DEFAULT_STORAGE_CAP_BYTES),
    )
    val storageCapBytes: StateFlow<Long> = _storageCapBytes.asStateFlow()

    fun setHostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOST, enabled).apply()
        _hostEnabled.value = enabled
    }

    fun setStorageCapBytes(bytes: Long) {
        require(bytes > 0L) { "storage cap must be positive" }
        prefs.edit().putLong(KEY_CAP, bytes).apply()
        _storageCapBytes.value = bytes
    }

    companion object {
        /** 50 MB — see docs/MAILBOX.md. */
        const val DEFAULT_STORAGE_CAP_BYTES = 50L * 1024 * 1024

        private const val PREFS_NAME = "keystone.mailbox.v1"
        private const val KEY_HOST = "host_enabled"
        private const val KEY_CAP = "storage_cap_bytes"
    }
}
