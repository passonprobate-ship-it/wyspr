package com.wyspr.feature.coordination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.CoordinationEventEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

@HiltViewModel
class CreateEventViewModel @Inject constructor(
    private val database: WysprDatabase,
    private val keystore: KeystoreManager,
) : ViewModel() {

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun create(
        title: String,
        description: String?,
        location: String?,
        startsAt: Long,
        endsAt: Long?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!database.isOpen) database.open()
            val ownPub = keystore.loadOrCreateIdentityKey().publicKey
            val membership = database.communityMembershipDao.firstOrNull() ?: return@launch
            val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val env = EventEnvelope.issue(
                keystore = keystore,
                id = id,
                creatorPub = ownPub,
                communityId = membership.communityId,
                title = title,
                description = description,
                location = location,
                startsAt = startsAt,
                endsAt = endsAt,
            )
            database.coordinationEventDao.insert(
                CoordinationEventEntity(
                    id = env.id,
                    creatorPub = env.creatorPub,
                    communityId = env.communityId,
                    title = env.titleString,
                    description = env.descriptionString,
                    location = env.locationString,
                    startsAt = env.startsAt,
                    endsAt = env.endsAt,
                    createdAt = env.createdAt,
                    status = env.status,
                    signature = env.signature,
                )
            )
            _done.value = true
        }
    }
}
