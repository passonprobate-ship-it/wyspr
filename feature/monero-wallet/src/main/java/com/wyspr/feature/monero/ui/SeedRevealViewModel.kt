package com.wyspr.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.feature.monero.MoneroWalletService
import com.wyspr.feature.monero.SeedMnemonic
import com.wyspr.feature.monero.persistence.WalletPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives [SeedRevealScreen]. Decoupled from the wallet's open
 * state — the seed lives in [com.wyspr.feature.monero.persistence.SeedStorage]
 * which can be read whether or not mollyim's wallet is currently
 * bootstrapped. Reveal is gated by biometric in the UI before
 * [reveal] is called; this VM is only ever invoked post-auth.
 */
@HiltViewModel
class SeedRevealViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val prefs: WalletPrefs,
) : ViewModel() {

    data class State(
        val words: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Idempotent bootstrap of the underlying wallet service so the
     * seed file is guaranteed to exist when reveal runs. Cheap if
     * the wallet is already bootstrapped.
     */
    fun bootstrap() {
        viewModelScope.launch { walletService.bootstrap() }
    }

    /**
     * Decode the on-disk seed bytes into 25 Electrum-style words.
     * Pushed onto Dispatchers.Default since the JNI decoder is
     * CPU-bound. The bytes never leave this VM — the State only
     * carries the words.
     */
    fun reveal() {
        viewModelScope.launch {
            val words = withContext(Dispatchers.Default) {
                val seed = walletService.readSeedBytes() ?: return@withContext emptyList()
                runCatching { SeedMnemonic.toWords(seed) }.getOrElse { emptyList() }
            }
            _state.value = _state.value.copy(words = words)
        }
    }

    fun acknowledge() {
        prefs.acknowledgeSeedBackup()
    }
}
