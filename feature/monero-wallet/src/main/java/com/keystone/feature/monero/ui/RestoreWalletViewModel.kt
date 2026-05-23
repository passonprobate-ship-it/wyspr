package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.SeedMnemonic
import com.keystone.feature.monero.persistence.WalletPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import im.molly.monero.sdk.RestorePoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives [RestoreWalletScreen]. The actual restore is suspended
 * inside [restore] so the caller can await its success before
 * navigating away. Errors land in [State.errorMessage] for the
 * UI to render.
 */
@HiltViewModel
class RestoreWalletViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val prefs: WalletPrefs,
) : ViewModel() {

    data class State(
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Decode and restore. Returns true on success so the caller
     * can pop the screen. On error the message lands in
     * [State.errorMessage] and the screen stays open.
     */
    suspend fun restore(seedText: String, restoreHeightText: String): Boolean {
        val seed = SeedMnemonic.fromWords(seedText)
            ?: SeedMnemonic.fromHex(seedText)
        if (seed == null) {
            _state.value = State(
                errorMessage = "Seed not recognised. Need 25 valid words or 64 hex chars.",
            )
            return false
        }
        val restorePoint = restoreHeightText.toIntOrNull()
            ?.let { RestorePoint.blockHeight(it) }
            ?: RestorePoint.Genesis
        val outcome = walletService.restoreFromSeed(seed, restorePoint)
        seed.fill(0)
        return when (outcome) {
            MoneroWalletService.RestoreResult.Ok -> {
                // A fresh wallet means a fresh seed-backup reminder.
                // The user just typed these words in from paper, so
                // we'd be wrong to claim they've already backed up.
                prefs.resetSeedBackup()
                _state.value = State(errorMessage = null)
                true
            }
            is MoneroWalletService.RestoreResult.Error -> {
                _state.value = State(errorMessage = outcome.message)
                false
            }
        }
    }
}
