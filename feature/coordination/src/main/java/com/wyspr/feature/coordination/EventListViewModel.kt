package com.wyspr.feature.coordination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.CoordinationEventEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class EventListViewModel @Inject constructor(
    private val database: WysprDatabase,
) : ViewModel() {

    private val _upcoming = MutableStateFlow<List<CoordinationEventEntity>>(emptyList())
    val upcoming: StateFlow<List<CoordinationEventEntity>> = _upcoming.asStateFlow()

    private val _past = MutableStateFlow<List<CoordinationEventEntity>>(emptyList())
    val past: StateFlow<List<CoordinationEventEntity>> = _past.asStateFlow()

    private var bound = false

    fun bind() {
        if (bound) return
        bound = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
            }
            val membership = withContext(Dispatchers.IO) {
                database.communityMembershipDao.firstOrNull()
            } ?: return@launch
            val communityId = membership.communityId
            val now = System.currentTimeMillis() / 1000
            launch {
                database.coordinationEventDao.upcomingForCommunity(communityId, now)
                    .collect { _upcoming.value = it }
            }
            launch {
                database.coordinationEventDao.pastOrCancelledForCommunity(communityId, now)
                    .collect { _past.value = it }
            }
        }
    }
}
