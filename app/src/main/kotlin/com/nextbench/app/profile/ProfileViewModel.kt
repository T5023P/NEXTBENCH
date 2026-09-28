package com.nextbench.app.profile

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.nextbench.data.firebase.ProfileUpdateDraft
import com.nextbench.data.firebase.ProfileRepository
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import java.io.File
import kotlin.math.ceil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfileTab { Listings, Posts }

enum class ProfileNoticeKind { Success, Error }

@Immutable
data class ProfileNotice(
    val id: Long,
    val message: String,
    val kind: ProfileNoticeKind,
)

@Immutable
data class ProfileEditorState(
    val open: Boolean = false,
    val name: String = "",
    val about: String = "",
    val username: String = "",
    val selectedProfilePicture: Uri? = null,
    val profilePictureFile: File? = null,
    val removeProfilePicture: Boolean = false,
    val isPreparingImage: Boolean = false,
    val isCheckingUsername: Boolean = false,
    val usernameAvailable: Boolean? = null,
    val isSaving: Boolean = false,
    val usernameError: String? = null,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() &&
            username.isNotBlank() &&
            usernameAvailable == true &&
            usernameError == null &&
            !isPreparingImage &&
            !isCheckingUsername &&
            !isSaving
}

@Immutable
data class ProfileUiState(
    val user: UserData? = null,
    val listings: List<Product> = emptyList(),
    val posts: List<Post> = emptyList(),
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val tab: ProfileTab = ProfileTab.Listings,
    val isLoading: Boolean = true,
    val error: String? = null,
    val editor: ProfileEditorState = ProfileEditorState(),
    val notice: ProfileNotice? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val imageStore: ProfileImageStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var viewerUid: String? = null
    private var observeJob: Job? = null
    private var usernameCheckJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        val uid = user?.uid?.takeIf(String::isNotBlank)
        if (viewerUid == uid && (uid == null || state.value.user != null)) return
        viewerUid = uid
        observeJob?.cancel()
        _state.value = ProfileUiState(user = user, isLoading = uid != null)
        if (uid == null) return

        observeJob = viewModelScope.launch {
            repository.observeProfile(uid)
                .catch { error ->
                    _state.update { it.copy(isLoading = false, error = error.profileMessage()) }
                }
                .collect { content ->
                    _state.update {
                        it.copy(
                            user = content.user ?: it.user,
                            listings = content.listings,
                            posts = content.posts,
                            followersCount = content.followersCount,
                            followingCount = content.followingCount,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
        }
    }

    fun selectTab(tab: ProfileTab) {
        if (tab != state.value.tab) _state.update { it.copy(tab = tab) }
    }

    fun retry() {
        val uid = viewerUid ?: return
        viewerUid = null
        syncViewer(state.value.user?.copy(uid = uid))
    }

    fun setFollowersOnly(enabled: Boolean) {
        val uid = viewerUid ?: return
        _state.update { current -> current.copy(user = current.user?.copy(chatPrivacy = current.user.chatPrivacy?.copy(followersOnly = enabled) ?: com.nextbench.data.model.ChatPrivacy(enabled))) }
        viewModelScope.launch {
            repository.updateFollowersOnly(uid, enabled).onFailure {
                _state.update { current -> current.copy(user = current.user?.copy(chatPrivacy = current.user.chatPrivacy?.copy(followersOnly = !enabled))) }
            }
        }
    }

    fun setHideLastSeen(hidden: Boolean) {
        val uid = viewerUid ?: return
        _state.update { current -> current.copy(user = current.user?.copy(hideLastSeen = hidden)) }
        viewModelScope.launch {
            repository.updateHideLastSeen(uid, hidden).onFailure {
                _state.update { current -> current.copy(user = current.user?.copy(hideLastSeen = !hidden)) }
            }
        }
    }

    fun openEditor() {
        val user = state.value.user ?: return
        cleanupEditorImage()
        _state.update {
            it.copy(
                editor = ProfileEditorState(
                    open = true,
                    name = user.name,
                    about = user.about.orEmpty(),
                    username = com.nextbench.data.firebase.normalizeUsernameInput(user.username.orEmpty()),
                    usernameAvailable = user.username?.isNotBlank() == true,
                ),
            )
        }
    }

    fun closeEditor() {
        if (state.value.editor.isSaving) return
        cleanupEditorImage()
        usernameCheckJob?.cancel()
        _state.update { it.copy(editor = ProfileEditorState()) }
    }

    fun setEditorName(value: String) = updateEditor {
        it.copy(name = value.take(ProfileRepository.MaxNameLength), error = null)
    }

    fun setEditorAbout(value: String) = updateEditor {
        it.copy(about = value.take(ProfileRepository.MaxAboutLength), error = null)
    }

    fun setEditorUsername(value: String) {
        val normalized = com.nextbench.data.firebase.normalizeUsernameInput(value)
        val currentUsername = com.nextbench.data.firebase.normalizeUsernameInput(state.value.user?.username.orEmpty())
        val cooldownError = if (normalized != currentUsername) usernameCooldownMessage(state.value.user) else null
        usernameCheckJob?.cancel()
        updateEditor {
            it.copy(
                username = normalized,
                usernameAvailable = if (normalized == currentUsername) true else null,
                isCheckingUsername = false,
                usernameError = cooldownError ?: com.nextbench.data.firebase.validateUsername(normalized).error,
                error = null,
            )
        }
    }

    fun checkUsername() {
        val uid = viewerUid ?: return
        val username = state.value.editor.username
        val currentUsername = com.nextbench.data.firebase.normalizeUsernameInput(state.value.user?.username.orEmpty())
        if (username != currentUsername) {
            usernameCooldownMessage(state.value.user)?.let { message ->
                updateEditor { it.copy(usernameAvailable = null, isCheckingUsername = false, usernameError = message, error = null) }
                return
            }
        }
        val validation = com.nextbench.data.firebase.validateUsername(username)
        if (!validation.valid) {
            updateEditor { it.copy(usernameAvailable = null, isCheckingUsername = false, usernameError = validation.error, error = null) }
            return
        }
        if (username == currentUsername) {
            updateEditor { it.copy(usernameAvailable = true, isCheckingUsername = false, usernameError = null, error = null) }
            return
        }
        usernameCheckJob?.cancel()
        updateEditor { it.copy(isCheckingUsername = true, usernameAvailable = null, usernameError = null, error = null) }
        usernameCheckJob = viewModelScope.launch {
            val result = repository.isUsernameAvailable(uid, username)
            if (state.value.editor.username != username) return@launch
            result.fold(
                onSuccess = { available ->
                    updateEditor {
                        it.copy(
                            isCheckingUsername = false,
                            usernameAvailable = available,
                            usernameError = if (available) null else "Username is already taken.",
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    updateEditor {
                        it.copy(
                            isCheckingUsername = false,
                            usernameAvailable = null,
                            usernameError = "Availability could not be checked.",
                            error = error.profileMessage(),
                        )
                    }
                },
            )
        }
    }

    fun prepareProfilePicture(uri: Uri) {
        if (state.value.editor.isSaving || state.value.editor.isPreparingImage) return
        cleanupEditorImage()
        updateEditor { it.copy(isPreparingImage = true, selectedProfilePicture = uri, removeProfilePicture = false, error = null) }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) { imageStore.prepare(uri) }
            prepared.fold(
                onSuccess = { file ->
                    if (!state.value.editor.open || state.value.editor.selectedProfilePicture != uri) {
                        file.delete()
                        return@fold
                    }
                    updateEditor { it.copy(isPreparingImage = false, profilePictureFile = file, error = null) }
                },
                onFailure = { error ->
                    if (!state.value.editor.open || state.value.editor.selectedProfilePicture != uri) return@fold
                    updateEditor {
                        it.copy(
                            isPreparingImage = false,
                            selectedProfilePicture = null,
                            profilePictureFile = null,
                            error = error.profileMessage(),
                        )
                    }
                },
            )
        }
    }

    fun removeProfilePicture() {
        cleanupEditorImage()
        updateEditor {
            it.copy(
                selectedProfilePicture = null,
                profilePictureFile = null,
                removeProfilePicture = true,
                error = null,
            )
        }
    }

    fun saveProfile() {
        val uid = viewerUid ?: return
        val editor = state.value.editor
        val validation = com.nextbench.data.firebase.validateProfileUpdate(
            ProfileUpdateDraft(
                name = editor.name,
                about = editor.about,
                username = editor.username,
                profilePictureFile = editor.profilePictureFile,
                removeProfilePicture = editor.removeProfilePicture,
            ),
        )
        if (validation != null) {
                updateEditor { it.copy(error = validation) }
            return
        }
        val currentUsername = com.nextbench.data.firebase.normalizeUsernameInput(state.value.user?.username.orEmpty())
        if (editor.username != currentUsername) {
            usernameCooldownMessage(state.value.user)?.let { message ->
                updateEditor { it.copy(usernameError = message, error = null) }
                return
            }
        }
        if (editor.username != currentUsername && editor.usernameAvailable != true) {
            updateEditor { it.copy(usernameError = "Check that your username is available before saving.", error = null) }
            return
        }
        updateEditor { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            repository.updateProfile(
                uid = uid,
                draft = ProfileUpdateDraft(
                    name = editor.name,
                    about = editor.about,
                    username = editor.username,
                    profilePictureFile = editor.profilePictureFile,
                    removeProfilePicture = editor.removeProfilePicture,
                ),
            ).fold(
                onSuccess = { updated ->
                    cleanupEditorImage()
                    _state.update {
                        it.copy(
                            user = it.user?.copy(
                                name = updated.name,
                                about = updated.about,
                                username = updated.username,
                                profilePicture = updated.profilePicture,
                                lastUsernameChange = if (editor.username != currentUsername) Timestamp.now() else it.user.lastUsernameChange,
                            ),
                            editor = ProfileEditorState(),
                            notice = ProfileNotice(++noticeId, "Profile updated.", ProfileNoticeKind.Success),
                        )
                    }
                },
                onFailure = { error -> updateEditor { it.copy(isSaving = false, error = error.profileMessage()) } },
            )
        }
    }

    fun dismissNotice(id: Long) = _state.update {
        if (it.notice?.id == id) it.copy(notice = null) else it
    }

    private fun updateEditor(transform: (ProfileEditorState) -> ProfileEditorState) {
        _state.update { current -> current.copy(editor = transform(current.editor)) }
    }

    private fun cleanupEditorImage() {
        state.value.editor.profilePictureFile?.delete()
    }

    override fun onCleared() {
        cleanupEditorImage()
        super.onCleared()
    }
}

internal fun Throwable.profileMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("Cloudinary", ignoreCase = true) -> "Image uploads are not configured for this build."
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load your profile."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.isNotBlank() -> raw
        else -> "Unable to load your profile. Please try again."
    }
}

internal fun usernameCooldownMessage(
    user: UserData?,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    val lastChange = user?.lastUsernameChange?.toDate()?.time ?: return null
    val remaining = com.nextbench.data.firebase.usernameCooldownRemaining(lastChange, nowMillis)
    if (remaining == 0L) return null
    val days = ceil(remaining.toDouble() / (24L * 60L * 60L * 1_000L)).toInt().coerceAtLeast(1)
    return "You can change your username again in $days ${if (days == 1) "day" else "days"}."
}
