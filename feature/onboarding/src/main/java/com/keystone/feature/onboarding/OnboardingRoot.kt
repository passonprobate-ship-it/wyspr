package com.keystone.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.feature.onboarding.screens.CompareFingerprintsScreen
import com.keystone.feature.onboarding.screens.DisplayQrScreen
import com.keystone.feature.onboarding.screens.KeyGenerationScreen
import com.keystone.feature.onboarding.screens.ResultScreen
import com.keystone.feature.onboarding.screens.RolePickerScreen
import com.keystone.feature.onboarding.screens.RunHandshakeScreen
import com.keystone.feature.onboarding.screens.ScanPeerQrScreen
import com.keystone.feature.onboarding.screens.ShareApkScreen
import com.keystone.feature.onboarding.screens.WelcomeScreen

/**
 * Mount point for the onboarding flow. The flow is strictly linear —
 * there's no inner NavController; the [OnboardingViewModel] is the
 * single source of truth and the only thing that drives state changes.
 *
 * [onContinueToWallet] fires once a trust edge exists.
 */
@Composable
fun OnboardingRoot(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onContinueToWallet: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onFindPeers: () -> Unit = {},
    biometricPrompt: suspend () -> Boolean = { true },
    /**
     * Suspending gate the host wires to an activity-level permission
     * launcher for `BlePermissions.required`. Returns true on
     * full grant, false on any denial. Defaults to "always granted"
     * for unit + preview composition.
     */
    blePermissionGate: suspend () -> Boolean = { true },
) {
    // onFindPeers is wired to the app-level Discovery route but the
    // onboarding flow now drives its own scan in-flow via
    // [startScanning], so the external CTA is unused inside this
    // composable. Keeping the param so app-shell callers don't break.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Local-only flag for the peer-to-peer APK share side-trip. It
    // never enters the OnboardingViewModel's state machine because
    // the share flow is orthogonal to the handshake — taking the
    // detour does not advance or rewind the main flow.
    var showShareApp by rememberSaveable { mutableStateOf(false) }

    if (showShareApp && state is OnboardingViewModel.UiState.DisplayQr) {
        ShareApkScreen(onDone = { showShareApp = false })
        return
    }

    when (val s = state) {
        OnboardingViewModel.UiState.Welcome ->
            WelcomeScreen(onContinue = viewModel::continueFromWelcome)

        OnboardingViewModel.UiState.RolePicker ->
            RolePickerScreen(onPick = viewModel::pickRole)

        is OnboardingViewModel.UiState.KeyGeneration -> {
            LaunchedEffect(Unit) {
                if (s.status is OnboardingViewModel.KeyGenStatus.Pending) {
                    viewModel.generateIdentity(biometricPrompt)
                }
            }
            KeyGenerationScreen(
                status = s.status,
                onStart = { viewModel.generateIdentity(biometricPrompt) },
            )
        }

        is OnboardingViewModel.UiState.DisplayQr ->
            DisplayQrScreen(
                identity = s.identity,
                backing = s.backing,
                qrBase32 = s.base32,
                onRefresh = viewModel::refreshQr,
                onContinueToWallet = onContinueToWallet,
                onFindPeers = viewModel::startScanning,
                onShareApp = { showShareApp = true },
            )

        is OnboardingViewModel.UiState.ScanPeerQr ->
            ScanPeerQrScreen(
                onScanned = viewModel::onPeerQrScanned,
                onCancel = viewModel::back,
                onInvalid = { /* surface as a transient toast in a later UI pass */ },
            )

        is OnboardingViewModel.UiState.CompareFingerprints ->
            CompareFingerprintsScreen(
                mine = s.localQr.fingerprint,
                theirs = s.peerQr.fingerprint,
                onMatch = { viewModel.onFingerprintsMatched(blePermissionGate) },
                onNoMatch = viewModel::onFingerprintsNoMatch,
                onCancel = viewModel::back,
            )

        is OnboardingViewModel.UiState.RunHandshake ->
            RunHandshakeScreen(
                state = s.sessionState,
                onCancel = {
                    viewModel.cancelHandshake()
                    viewModel.back()
                },
            )

        is OnboardingViewModel.UiState.Result ->
            ResultScreen(
                outcome = s.outcome,
                onHome = onContinueToWallet,
                onRetry = viewModel::retryFromDisplayQr,
            )
    }
}
