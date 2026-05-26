package com.wyspr.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.wyspr.app.biometric.BiometricGate
import com.wyspr.app.biometric.BiometricUnlocker
import com.wyspr.app.profile.ProfileHttpServer
import com.wyspr.app.transport.AndroidMessagingNotifier
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.ui.WysprTheme
import com.wyspr.core.ui.settings.BiometricSettings
import com.wyspr.core.ui.settings.KeyRotationSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Extends FragmentActivity (not ComponentActivity) because BiometricPrompt
 * requires it. FragmentActivity ⊂ ComponentActivity, so we don't lose
 * anything else.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var biometricSettings: BiometricSettings
    @Inject lateinit var biometricUnlocker: BiometricUnlocker
    @Inject lateinit var torBackend: TorBackend
    @Inject lateinit var profileHttpServer: ProfileHttpServer
    @Inject lateinit var keyRotationService: KeyRotationService
    @Inject lateinit var keyRotationSettings: KeyRotationSettings

    // Notification permission request landed in API 33 (Tiramisu). The
    // transport foreground service can run without it — Android just
    // hides the notification — but that's worse UX. Result-callback
    // ignored: a denial just means the FGS runs silently.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* either way the FGS still runs */ }

    /**
     * Latest pending deep-link instruction emitted by either the
     * cold-start intent or onNewIntent. WysprNavHost collects
     * this and routes accordingly. Set back to null after
     * consumption so a rotation doesn't re-fire the deep link.
     */
    private val _deepLink = MutableStateFlow<DeepLink?>(null)
    val deepLink: StateFlow<DeepLink?> = _deepLink.asStateFlow()

    fun consumeDeepLink() {
        _deepLink.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDeepLink(intent)

        // FLAG_SECURE on every window — blocks screenshots, screen
        // recordings, and surfacing the activity in the recents thumbnail.
        // SECURITY-MODEL.md §1: assume the OS may be compromised.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        maybeRequestNotificationPermission()

        setContent {
            WysprTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val gateEnabled by biometricSettings.gateEnabled.collectAsStateWithLifecycle()
                    val pendingDeepLink by deepLink.collectAsStateWithLifecycle()
                    BiometricGate(enabled = gateEnabled) {
                        // Bootstrap Tor + the loopback profile HTTP
                        // server inside the unlocked branch. Tor used
                        // to spin up eagerly in
                        // WysprApplication.onCreate, which burned
                        // 10-60s of CPU + battery on slow devices even
                        // when the user only opened Settings or About.
                        // start() is idempotent on both sides.
                        LaunchedEffect(Unit) {
                            runCatching { torBackend.start() }
                            runCatching { profileHttpServer.start() }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                keyRotationService.recoverPendingRotation()
                                keyRotationSettings.seedIfNeeded()
                                if (keyRotationService.isDue()) {
                                    runCatching { keyRotationService.rotate() }
                                }
                            }
                        }
                        WysprNavHost(
                            unlocker = biometricUnlocker,
                            biometricSettings = biometricSettings,
                            keyRotationService = keyRotationService,
                            pendingDeepLink = pendingDeepLink,
                            onDeepLinkConsumed = ::consumeDeepLink,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    private fun readDeepLink(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            AndroidMessagingNotifier.ACTION_OPEN_CHAT -> {
                val hex = intent.getStringExtra(AndroidMessagingNotifier.EXTRA_PEER_HEX)
                    ?: return
                _deepLink.value = DeepLink.OpenChat(peerHex = hex)
            }
            com.wyspr.app.transport.AndroidPaymentNotifier.ACTION_OPEN_WALLET -> {
                _deepLink.value = DeepLink.OpenWallet
            }
        }
    }

    sealed interface DeepLink {
        data class OpenChat(val peerHex: String) : DeepLink
        data object OpenWallet : DeepLink
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
