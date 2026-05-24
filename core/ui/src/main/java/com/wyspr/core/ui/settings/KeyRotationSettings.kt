package com.wyspr.core.ui.settings

import android.content.Context
import android.content.SharedPreferences

class KeyRotationSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastRotationEpochSeconds(): Long =
        prefs.getLong(KEY_LAST_ROTATION, 0L)

    fun recordRotation(epochSeconds: Long = System.currentTimeMillis() / 1000) {
        prefs.edit().putLong(KEY_LAST_ROTATION, epochSeconds).apply()
    }

    fun isDueForRotation(
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val last = lastRotationEpochSeconds()
        if (last == 0L) return false
        return (nowSeconds - last) >= ROTATION_INTERVAL_SECONDS
    }

    fun seedIfNeeded() {
        if (lastRotationEpochSeconds() == 0L) {
            recordRotation()
        }
    }

    companion object {
        private const val PREFS_NAME = "wyspr.key_rotation.v1"
        private const val KEY_LAST_ROTATION = "last_rotation_epoch_seconds"
        const val ROTATION_INTERVAL_SECONDS = 90L * 24 * 3600
    }
}
