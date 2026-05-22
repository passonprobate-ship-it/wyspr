package com.keystone.app

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
import com.keystone.app.biometric.BiometricGate
import com.keystone.app.biometric.BiometricUnlocker
import com.keystone.app.profile.ProfileHttpServer
import com.keystone.app.transport.AndroidMessagingNotifier
import com.keystone.core.transport.TorBackend
import com.keystone.core.ui.KeystoneTheme
import com.keystone.core.ui.settings.BiometricSettings
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

    // Notification permission request landed in API 33 (Tiramisu). The
    // transport foreground service can run without it — Android just
    // hides the notification — but that's worse UX. Result-callback
    // ignored: a denial just means the FGS runs silently.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* either way the FGS still runs */ }

    /**
     * Latest pending deep-link instruction emitted by either the
     * cold-start intent or onNewIntent. KeystoneNavHost collects
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
            KeystoneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val gateEnabled by biometricSettings.gateEnabled.collectAsStateWithLifecycle()
                    val pendingDeepLink by deepLink.collectAsStateWithLifecycle()
                    BiometricGate(enabled = gateEnabled) {
                        // Bootstrap Tor + the loopback profile HTTP
                        // server inside the unlocked branch. Tor used
                        // to spin up eagerly in
                        // KeystoneApplication.onCreate, which burned
                        // 10-60s of CPU + battery on slow devices even
                        // when the user only opened Settings or About.
                        // start() is idempotent on both sides.
                        LaunchedEffect(Unit) {
                            runCatching { torBackend.start() }
                            runCatching { profileHttpServer.start() }
                        }
                        KeystoneNavHost(
                            unlocker = biometricUnlocker,
                            biometricSettings = biometricSettings,
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
        if (intent.action != AndroidMessagingNotifier.ACTION_OPEN_CHAT) return
        val hex = intent.getStringExtra(AndroidMessagingNotifier.EXTRA_PEER_HEX)
            ?: return
        _deepLink.value = DeepLink.OpenChat(peerHex = hex)
    }

    sealed interface DeepLink {
        data class OpenChat(val peerHex: String) : DeepLink
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
