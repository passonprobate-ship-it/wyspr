package com.keystone.app.biometric

import android.content.Context
import androidx.biometric.BiometricManager

/**
 * Tiny wrapper around [BiometricManager.canAuthenticate]. Exposes the
 * three states the UI cares about — "ready," "user must enroll,"
 * "device can't do it" — instead of the underlying enum spaghetti.
 */
enum class BiometricCapability {
    /** Device has biometrics enrolled and ready. */
    AVAILABLE,
    /** Hardware exists but the user hasn't enrolled (or it was revoked). */
    NOT_ENROLLED,
    /** No biometric hardware, or temporarily unavailable. */
    UNAVAILABLE,
    ;

    val canAuthenticate: Boolean get() = this == AVAILABLE

    companion object {
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun current(context: Context): BiometricCapability {
            val manager = BiometricManager.from(context)
            return when (manager.canAuthenticate(AUTHENTICATORS)) {
                BiometricManager.BIOMETRIC_SUCCESS -> AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NOT_ENROLLED
                else -> UNAVAILABLE
            }
        }

        /** Authenticator set to pass to [BiometricPrompt.PromptInfo.setAllowedAuthenticators]. */
        const val AUTHENTICATOR_FLAGS = AUTHENTICATORS
    }
}
