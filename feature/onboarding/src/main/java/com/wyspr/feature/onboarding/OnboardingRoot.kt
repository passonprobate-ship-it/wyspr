package com.wyspr.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.feature.onboarding.screens.CompareFingerprintsScreen
import com.wyspr.feature.onboarding.screens.KeyGenerationScreen
import com.wyspr.feature.onboarding.screens.PairScreen
import com.wyspr.feature.onboarding.screens.ResultScreen
import com.wyspr.feature.onboarding.screens.RolePickerScreen
import com.wyspr.feature.onboarding.screens.RunHandshakeScreen
import com.wyspr.feature.onboarding.screens.ShareApkScreen
import com.wyspr.feature.onboarding.screens.UpdateFromPeerScreen

/**
 * Mount point for the onboarding flow. The flow is strictly linear —
 * there's no inner NavController; the [OnboardingViewModel] is the
 * single source of truth and the only thing that drives state changes.
 *
 *     RolePicker → KeyGeneration → Pair → CompareFingerprints →
 *     RunHandshake → Result
 *
 * The previous Welcome / DisplayQr / ScanPeerQr screens were
 * collapsed: the brand moment lives at the top of RolePicker, and the
 * show-QR + scan-peer steps were merged into a single Pair screen
 * with both panes on-screen at once.
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
    /**
     * When true, force the flow back to RolePicker even if the user
     * has already paired before. This is the "Pair a new peer" CTA's
     * entry point — re-uses the same QR/scan/handshake flow to add
     * an additional trust edge. Default false matches first-launch
     * onboarding semantics: returning users skip to the main app.
     */
    startFresh: Boolean = false,
) {
    // onFindPeers is wired to the app-level Discovery route but the
    // onboarding flow now drives its own scan in-flow via the Pair
    // screen, so the external CTA is unused inside this composable.
    // Keeping the param so app-shell callers don't break.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Local-only flag for the peer-to-peer side-trips (APK share +
    // peer update). A single nullable enum replaces the previous
    // pair of independent booleans because two independent flags
    // could both flip true at once (e.g. one click queued while a
    // previous selection's reset hadn't run), making precedence
    // undeclared. A single state machine makes the choice explicit.
    var sideTrip by rememberSaveable { mutableStateOf<SideTrip?>(null) }

    if (sideTrip != null && state is OnboardingViewModel.UiState.Pair) {
        when (sideTrip) {
            SideTrip.ShareApp -> ShareApkScreen(onDone = { sideTrip = null })
            SideTrip.UpdateFromPeer -> UpdateFromPeerScreen(onDone = { sideTrip = null })
            null -> Unit
        }
        return
    }

    when (val s = state) {
        OnboardingViewModel.UiState.Booting -> {
            // Brief startup probe — the ViewModel decides whether to
            // advance to RolePicker (new install) or AlreadyOnboarded
            // (returning user with a trust edge). Show nothing for the
            // ~50–150ms this typically takes rather than flashing the
            // RolePicker screen.
        }

        OnboardingViewModel.UiState.AlreadyOnboarded -> {
            // Re-entry from "Pair a new peer" forces back to RolePicker;
            // first-launch path bounces straight to the main app.
            LaunchedEffect(Unit) {
                if (startFresh) viewModel.beginNewPairing()
                else onContinueToWallet()
            }
        }

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

        is OnboardingViewModel.UiState.Pair ->
            PairScreen(
                identity = s.identity,
                qrBase32 = s.base32,
                peerQr = s.peerQr,
                onPeerScanned = viewModel::onPeerQrScanned,
                onContinue = viewModel::onContinueFromPair,
                onRefreshQr = viewModel::refreshQr,
                onContinueToWallet = onContinueToWallet,
                onShareApp = { sideTrip = SideTrip.ShareApp },
                onUpdateFromPeer = { sideTrip = SideTrip.UpdateFromPeer },
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
                onRetry = viewModel::retryFromPair,
            )
    }
}

/**
 * Mutually-exclusive side-trip selector. `null` means the main
 * onboarding flow is on screen; any non-null value diverts to the
 * named ancillary screen until the user dismisses it.
 */
private enum class SideTrip { ShareApp, UpdateFromPeer }
