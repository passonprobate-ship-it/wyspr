package com.wyspr.app.biometric

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricPrompt
// BiometricSettings is consumed at the caller site, not here — gate is dumb.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Gate composable that prompts for biometric/device-credential auth
 * before showing [content].
 *
 *   - If [enabled] is false, [content] is shown immediately.
 *   - If the host activity is not a [FragmentActivity], the gate is
 *     bypassed (would be a programming error — BiometricPrompt needs
 *     FragmentActivity).
 *   - If the device has no biometric capability *and* no device
 *     credential, the gate is bypassed with a brief notice — refusing
 *     to launch on a device without a screen lock would lock the user
 *     out entirely.
 *   - Successful auth unlocks for the lifetime of this composable
 *     instance. Re-locking on app background is enforced by
 *     [unlockedKey] being rememberSaveable — a process-death recreate
 *     starts locked. Foreground/background within the same process
 *     does not re-prompt (intentional — would be too aggressive).
 */
@Composable
fun BiometricGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var capability by remember { mutableStateOf(BiometricCapability.current(context)) }

    // Refresh capability when the user returns from system settings
    // (e.g. after enrolling a biometric). Without this the gate would
    // stay stuck on NOT_ENROLLED until a process restart.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        capability = BiometricCapability.current(context)
    }

    // Saveable so a configuration change doesn't re-prompt; cleared on
    // process death which re-locks naturally.
    var unlocked by rememberSaveable(enabled) {
        mutableStateOf(!enabled || activity == null || capability == BiometricCapability.UNAVAILABLE)
    }
    var lastError by remember { mutableStateOf<String?>(null) }

    if (unlocked) {
        content()
        return
    }

    val prompt = remember(activity) {
        activity?.let {
            BiometricPrompt(
                it,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        unlocked = true
                        lastError = null
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        lastError = errString.toString()
                    }

                    override fun onAuthenticationFailed() {
                        lastError = "Authentication failed. Try again."
                    }
                },
            )
        }
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Wyspr")
            .setSubtitle("Confirm it's you before opening your community network.")
            .setAllowedAuthenticators(BiometricCapability.AUTHENTICATOR_FLAGS)
            .build()
    }

    LaunchedEffect(enabled, prompt) {
        if (enabled && prompt != null && capability.canAuthenticate) {
            prompt.authenticate(promptInfo)
        }
    }

    LockedScreen(
        gateEnabled = enabled,
        capability = capability,
        lastError = lastError,
        onRetry = {
            if (prompt != null && capability.canAuthenticate) {
                prompt.authenticate(promptInfo)
            }
        },
        onSkipForNotEnrolled = { unlocked = true },
        onOpenBiometricSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                )
            }.onFailure {
                // Fallback for devices that don't support the biometric
                // enroll action directly.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun LockedScreen(
    gateEnabled: Boolean,
    capability: BiometricCapability,
    lastError: String?,
    onRetry: () -> Unit,
    onSkipForNotEnrolled: () -> Unit,
    onOpenBiometricSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Wyspr is locked", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = when (capability) {
                BiometricCapability.AVAILABLE ->
                    "Authenticate to continue."
                BiometricCapability.NOT_ENROLLED ->
                    if (gateEnabled) {
                        "No biometric is enrolled on this device. Enroll a fingerprint or set up Face Unlock in system settings to unlock Wyspr."
                    } else {
                        "No biometric is enrolled on this device. Enroll a fingerprint or set up Face Unlock in system settings, or continue without the gate."
                    }
                BiometricCapability.UNAVAILABLE ->
                    "Biometric not available on this device. Continuing…"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        lastError?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when (capability) {
            BiometricCapability.AVAILABLE -> Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) { Text("Authenticate") }
            BiometricCapability.NOT_ENROLLED -> {
                if (gateEnabled) {
                    // Gate is explicitly enabled — don't allow skip;
                    // direct the user to enroll biometrics.
                    Button(
                        onClick = onOpenBiometricSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) { Text("Open biometric settings") }
                } else {
                    // First-run default — allow continuing without.
                    Button(
                        onClick = onSkipForNotEnrolled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) { Text("Continue without biometric") }
                }
            }
            BiometricCapability.UNAVAILABLE -> Unit // bypass handled above
        }
    }
}
