package com.wyspr.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.identity.PublicKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ViewProfileViewModel @Inject constructor(
    private val database: WysprDatabase,
    private val fetcher: ProfileFetcher,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ok(val onion: String, val html: String) : UiState
        data class NoOnion(val message: String) : UiState
        data class Failed(val onion: String, val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun start(peer: PublicKey) {
        viewModelScope.launch {
            val onion = withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                resolvePeerOnion(peer)
            }
            if (onion == null) {
                _state.value = UiState.NoOnion(
                    "We don't have a Tor address for this peer. They paired " +
                        "before .onion exchange was added, or before their Tor " +
                        "service was running. Re-pair to fix.",
                )
                return@launch
            }
            _state.value = UiState.Loading
            val r = fetcher.fetch(onion)
            _state.value = when (r) {
                is ProfileFetcher.Result.Ok -> UiState.Ok(onion, r.html)
                ProfileFetcher.Result.TorNotReady -> UiState.Failed(
                    onion,
                    "Tor isn't bootstrapped yet on this device. Wait a few seconds and try again.",
                )
                is ProfileFetcher.Result.HttpError -> UiState.Failed(
                    onion,
                    "Peer responded with HTTP ${r.code}. They may not have published a page yet.",
                )
                is ProfileFetcher.Result.NetworkError -> UiState.Failed(
                    onion,
                    "Couldn't reach the peer over Tor: ${r.message}",
                )
            }
        }
    }

    fun retry(peer: PublicKey) = start(peer)

    private suspend fun resolvePeerOnion(peer: PublicKey): String? {
        val edges = database.trustEdgeDao.all()
        val edge = edges.firstOrNull { e ->
            e.fromPub.contentEquals(peer.bytes) || e.toPub.contentEquals(peer.bytes)
        } ?: return null
        return edge.peerOnion?.takeIf { it.isNotBlank() }
    }
}
