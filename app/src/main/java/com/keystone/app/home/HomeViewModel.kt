package com.keystone.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.Fingerprint
import com.keystone.core.identity.PublicKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the post-onboarding app home tile menu. Surfaces:
 *   - the user's own short fingerprint (so they can sanity-check
 *     which identity is loaded before tapping into Messages)
 *   - the total unread inbound message count across 1:1 + group
 *     threads, used to badge the Messages tile
 *
 * Everything else (peer count, current Tor state, etc.) is left for
 * later — this VM is intentionally narrow so the home stays cheap
 * to recompose.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val keystore: KeystoreManager,
    private val database: KeystoneDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Identity load is one-shot — it doesn't change without
            // an explicit reset (which routes back through onboarding
            // and tears down the activity), so a single fetch is fine.
            val ownPub = withContext(Dispatchers.IO) {
                PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
            }
            _state.value = _state.value.copy(fingerprint = ownPub.fingerprint)
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (!database.isOpen) database.open() }
            // The total badge sums 1:1 + group unread. If either DAO
            // is unavailable we just emit zero so the UI never blocks
            // on the badge.
            val direct = runCatching { database.messageDao.totalUnreadFlow() }
                .getOrElse { flowOf(0) }
            val group = runCatching { database.groupMessageDao.totalUnreadFlow() }
                .getOrElse { flowOf(0) }
            combine(direct, group) { a, b -> a + b }.collect { total ->
                _state.value = _state.value.copy(unread = total)
            }
        }
    }

    data class UiState(
        val fingerprint: Fingerprint? = null,
        val unread: Int = 0,
    )
}
