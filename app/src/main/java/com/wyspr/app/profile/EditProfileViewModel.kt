package com.wyspr.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.UserProfileEntity
import com.wyspr.core.transport.TorBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the user's "My web page" editor. Loads the
 * existing profile (if any), exposes the device's own .onion so the
 * user knows the URL to share, and persists edits via [UserProfileDao].
 */
@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val database: WysprDatabase,
    torBackend: TorBackend,
) : ViewModel() {

    data class FormState(
        val loading: Boolean = true,
        val displayName: String = "",
        val avatarEmoji: String = "",
        val bio: String = "",
        val links: String = "",
        val saved: Boolean = false,
    )

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()

    val onion: StateFlow<String?> = torBackend.onionAddress

    init {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                database.userProfileDao.get()
            }
            _form.value = FormState(
                loading = false,
                displayName = p?.displayName.orEmpty(),
                avatarEmoji = p?.avatarEmoji.orEmpty(),
                bio = p?.bio.orEmpty(),
                links = p?.links.orEmpty(),
            )
        }
    }

    fun setDisplayName(v: String) { _form.value = _form.value.copy(displayName = v, saved = false) }
    fun setAvatarEmoji(v: String) { _form.value = _form.value.copy(avatarEmoji = v, saved = false) }
    fun setBio(v: String) { _form.value = _form.value.copy(bio = v, saved = false) }
    fun setLinks(v: String) { _form.value = _form.value.copy(links = v, saved = false) }

    fun save() {
        val s = _form.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!database.isOpen) database.open()
                database.userProfileDao.upsert(
                    UserProfileEntity(
                        id = 0,
                        displayName = s.displayName.trim().ifEmpty { null },
                        bio = s.bio.trim().ifEmpty { null },
                        avatarEmoji = s.avatarEmoji.trim().take(8).ifEmpty { null },
                        links = s.links.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                            .ifEmpty { null },
                    ),
                )
            }
            _form.value = _form.value.copy(saved = true)
        }
    }
}
