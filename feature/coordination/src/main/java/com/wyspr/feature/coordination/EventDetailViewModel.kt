package com.wyspr.feature.coordination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.CoordinationEventEntity
import com.wyspr.core.database.entities.CoordinationRsvpEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val database: WysprDatabase,
    private val keystore: KeystoreManager,
) : ViewModel() {

    private val _event = MutableStateFlow<CoordinationEventEntity?>(null)
    val event: StateFlow<CoordinationEventEntity?> = _event.asStateFlow()

    private val _rsvps = MutableStateFlow<List<CoordinationRsvpEntity>>(emptyList())
    val rsvps: StateFlow<List<CoordinationRsvpEntity>> = _rsvps.asStateFlow()

    private val _ownPub = MutableStateFlow<ByteArray?>(null)
    val ownPub: StateFlow<ByteArray?> = _ownPub.asStateFlow()

    private var eventId: ByteArray? = null

    fun bind(eventId: ByteArray) {
        this.eventId = eventId
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                _ownPub.value = keystore.loadOrCreateIdentityKey().publicKey
            }
            launch {
                database.coordinationEventDao.byIdFlow(eventId)
                    .collect { _event.value = it }
            }
            launch {
                database.coordinationRsvpDao.forEvent(eventId)
                    .collect { _rsvps.value = it }
            }
        }
    }

    fun rsvp(status: Int) {
        val id = eventId ?: return
        val ev = _event.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pub = keystore.loadOrCreateIdentityKey().publicKey
            val env = RsvpEnvelope.issue(
                keystore = keystore,
                eventId = id,
                responderPub = pub,
                communityId = ev.communityId,
                status = status,
            )
            database.coordinationRsvpDao.insert(
                CoordinationRsvpEntity(
                    eventId = env.eventId,
                    responderPub = env.responderPub,
                    communityId = env.communityId,
                    status = env.status,
                    createdAt = env.createdAt,
                    signature = env.signature,
                )
            )
            database.coordinationRsvpDao.updateIfNewer(
                eventId = env.eventId,
                responderPub = env.responderPub,
                status = env.status,
                createdAt = env.createdAt,
                signature = env.signature,
            )
        }
    }

    fun cancelEvent() {
        val ev = _event.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pub = keystore.loadOrCreateIdentityKey().publicKey
            if (!pub.contentEquals(ev.creatorPub)) return@launch
            val env = EventEnvelope.issue(
                keystore = keystore,
                id = ev.id,
                creatorPub = pub,
                communityId = ev.communityId,
                title = ev.title,
                description = ev.description,
                location = ev.location,
                startsAt = ev.startsAt,
                endsAt = ev.endsAt,
                status = 1,
            )
            database.coordinationEventDao.updateIfNewer(
                id = env.id,
                title = env.titleString,
                description = env.descriptionString,
                location = env.locationString,
                startsAt = env.startsAt,
                endsAt = env.endsAt,
                createdAt = env.createdAt,
                status = env.status,
                signature = env.signature,
            )
        }
    }
}
