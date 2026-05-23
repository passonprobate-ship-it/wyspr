package com.wyspr.app.biometric

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Suspending wrapper around [BiometricPrompt]. The caller hands in an
 * [FragmentActivity] (obtained from `LocalContext` in a Composable) and
 * awaits a Boolean result.
 *
 * Returns:
 *   - true  — user authenticated successfully; the wrapping key's auth
 *             window is now open for ~5 minutes.
 *   - false — user cancelled / authentication failed permanently /
 *             callback signalled an error.
 *
 * The activity reference is not stored, so this is safe to construct
 * fresh on each call without leaking.
 */
class BiometricUnlocker {

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock your identity",
        subtitle: String = "Authenticate to use your hardware-protected key.",
    ): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // Soft failure — let the user try again. We only resume
                    // on success / final error / cancellation.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricCapability.AUTHENTICATOR_FLAGS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation {
            // BiometricPrompt has no programmatic cancel; the system
            // dismisses the dialog when the activity backgrounds. Nothing
            // to clean up here beyond the coroutine itself.
        }
    }
}
