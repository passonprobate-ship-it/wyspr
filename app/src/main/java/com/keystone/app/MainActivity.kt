package com.keystone.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.app.biometric.BiometricGate
import com.keystone.app.biometric.BiometricUnlocker
import com.keystone.core.ui.KeystoneTheme
import com.keystone.core.ui.settings.BiometricSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Extends FragmentActivity (not ComponentActivity) because BiometricPrompt
 * requires it. FragmentActivity ⊂ ComponentActivity, so we don't lose
 * anything else.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var biometricSettings: BiometricSettings
    @Inject lateinit var biometricUnlocker: BiometricUnlocker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE on every window — blocks screenshots, screen
        // recordings, and surfacing the activity in the recents thumbnail.
        // SECURITY-MODEL.md §1: assume the OS may be compromised.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        setContent {
            KeystoneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val gateEnabled by biometricSettings.gateEnabled.collectAsStateWithLifecycle()
                    BiometricGate(enabled = gateEnabled) {
                        KeystoneNavHost(unlocker = biometricUnlocker)
                    }
                }
            }
        }
    }
}
