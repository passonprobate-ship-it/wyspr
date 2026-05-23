package com.wyspr.core.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny preference store for the biometric-gate toggle.
 *
 * Uses plain SharedPreferences. The preference value itself is not
 * sensitive — it's just a boolean "user wants the gate." Defaults to
 * false so first-launch flow isn't gated before the user even reaches
 * the settings screen.
 *
 * Wrapped in a StateFlow so Compose can react to changes from any
 * source (toggle, future biometric-revocation handling) without
 * relying on the SharedPreferences listener machinery.
 */
class BiometricSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _gateEnabled = MutableStateFlow(prefs.getBoolean(KEY_GATE, false))
    val gateEnabled: StateFlow<Boolean> = _gateEnabled.asStateFlow()

    /**
     * Whether the *next* identity to be created should have its wrapping
     * key bound to biometric/credential auth. The wrapping key is set
     * once and not migrated — changing this flag affects only new
     * identities (i.e. after a reset). Default OFF so a first-install
     * walkthrough doesn't trip on devices without enrolled biometric.
     */
    private val _bindToBiometric = MutableStateFlow(prefs.getBoolean(KEY_BIND, false))
    val bindToBiometric: StateFlow<Boolean> = _bindToBiometric.asStateFlow()

    fun setGateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GATE, enabled).apply()
        _gateEnabled.value = enabled
    }

    fun setBindToBiometric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIND, enabled).apply()
        _bindToBiometric.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "wyspr.biometric.v1"
        const val KEY_GATE = "gate_enabled"
        const val KEY_BIND = "bind_to_biometric"
    }
}
