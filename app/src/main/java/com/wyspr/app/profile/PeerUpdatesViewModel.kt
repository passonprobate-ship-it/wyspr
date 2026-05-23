package com.wyspr.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.transport.TorBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the periodic background poll for newer peer versions and
 * exposes the result to the MainShell. The Chats tab's banner
 * subscribes to [available] and renders an update prompt for any
 * peer whose installed Wyspr is ahead of this one.
 *
 * Poll cadence:
 * - Wait for Tor Ready before the first probe.
 * - Then once every [REFRESH_INTERVAL_MS] (15 min).
 * - First refresh delayed by [FIRST_DELAY_MS] so cold start doesn't
 *   race the Tor bootstrap on first launch.
 */
@HiltViewModel
class PeerUpdatesViewModel @Inject constructor(
    private val checker: PeerVersionChecker,
    private val torBackend: TorBackend,
) : ViewModel() {

    val available: StateFlow<List<PeerVersionChecker.PeerUpdate>> = checker.available

    init {
        viewModelScope.launch {
            // Wait until Tor reports Ready at least once — probing
            // before bootstrap completes wastes a 30s timeout per peer.
            runCatching {
                torBackend.state.first { it is TorBackend.State.Ready }
            }
            delay(FIRST_DELAY_MS)
            while (isActive) {
                runCatching { checker.refresh() }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun dismiss(peerPub: ByteArray) {
        checker.dismiss(peerPub)
    }

    private companion object {
        /** Quiet window after Tor Ready before the first probe. */
        const val FIRST_DELAY_MS = 30_000L
        /** Inter-poll delay. Long enough to be unobtrusive; short
         *  enough that "ask peer to update" gets noticed within an
         *  hour of them landing a new build. */
        const val REFRESH_INTERVAL_MS = 15L * 60 * 1000
    }
}
