package com.nextbench.app.create

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.NewPostDraft
import com.nextbench.data.firebase.PostComposerRepository
import com.nextbench.data.firebase.PostPrivacy
import com.nextbench.data.firebase.PostType
import com.nextbench.data.firebase.PostUploadProgress
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CreateStep { Choose, Compose }

@Immutable
data class ComposerImage(
    val id: String,
    val uri: Uri,
    val file: File,
)

@Immutable
data class CreateUiState(
    val step: CreateStep = CreateStep.Choose,
    val type: PostType = PostType.Others,
    val privacy: PostPrivacy = PostPrivacy.Public,
    val anonymous: Boolean = false,
    val title: String = "",
    val content: String = "",
    val images: List<ComposerImage> = emptyList(),
    val isPreparingMedia: Boolean = false,
    val isPublishing: Boolean = false,
    val progress: PostUploadProgress? = null,
    val error: String? = null,
    val publishedPostId: String? = null,
) {
    val hasDraft: Boolean get() = title.isNotBlank() || content.isNotBlank() || images.isNotEmpty()
    val canPublish: Boolean
        get() = canPublishDraft(
            title = title,
            content = content,
            imageCount = images.size,
            isPreparingMedia = isPreparingMedia,
            isPublishing = isPublishing,
        )
}

internal fun canPublishDraft(
    title: String,
    content: String,
    imageCount: Int,
    isPreparingMedia: Boolean = false,
    isPublishing: Boolean = false,
): Boolean =
    !isPublishing &&
        !isPreparingMedia &&
        (title.isNotBlank() || content.isNotBlank() || imageCount > 0) &&
        title.length <= PostComposerRepository.MaxTitleLength &&
        content.length <= PostComposerRepository.MaxContentLength

@HiltViewModel
class CreateViewModel @Inject constructor(
    private val repository: PostComposerRepository,
    private val mediaStore: PostMediaStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    fun startPost() = _state.update { it.copy(step = CreateStep.Compose, error = null) }
    fun setTitle(value: String) = _state.update { it.copy(title = value.take(PostComposerRepository.MaxTitleLength), error = null) }
    fun setContent(value: String) = _state.update { it.copy(content = value.take(PostComposerRepository.MaxContentLength), error = null) }
    fun selectPrivacy(value: PostPrivacy) = _state.update { it.copy(privacy = value, error = null) }

    fun selectType(type: PostType) = _state.update {
        it.copy(
            type = type,
            anonymous = type == PostType.Confession,
            error = null,
        )
    }

    fun setAnonymous(value: Boolean) = _state.update {
        if (it.type == PostType.Confession) it.copy(anonymous = value, error = null) else it
    }

    fun prepareImages(uris: List<Uri>) {
        val remaining = PostComposerRepository.MaxImages - state.value.images.size
        if (remaining <= 0 || state.value.isPreparingMedia || state.value.isPublishing) return
        val selected = uris.take(remaining)
        if (selected.isEmpty()) return
        _state.update { it.copy(isPreparingMedia = true, error = null) }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) { selected.map(mediaStore::prepare) }
            val firstFailure = prepared.firstOrNull(Result<PreparedPostImage>::isFailure)?.exceptionOrNull()
            if (firstFailure != null) {
                prepared.mapNotNull(Result<PreparedPostImage>::getOrNull).forEach { it.file.delete() }
                _state.update { it.copy(isPreparingMedia = false, error = firstFailure.createMessage()) }
                return@launch
            }
            val images = prepared.mapNotNull(Result<PreparedPostImage>::getOrNull).map { image ->
                ComposerImage(image.file.name, image.uri, image.file)
            }
            _state.update { it.copy(images = it.images + images, isPreparingMedia = false, error = null) }
        }
    }

    fun removeImage(id: String) {
        if (state.value.isPublishing) return
        val image = state.value.images.firstOrNull { it.id == id } ?: return
        image.file.delete()
        _state.update { it.copy(images = it.images.filterNot { item -> item.id == id }, error = null) }
    }

    fun publish(user: UserData) {
        val snapshot = state.value
        if (!snapshot.canPublish) return
        if (snapshot.type == PostType.Confession && snapshot.anonymous && user.anonymousPersonaName.isNullOrBlank()) {
            _state.update { it.copy(error = "Set an anonymous persona from your profile before posting anonymously.") }
            return
        }
        _state.update { it.copy(isPublishing = true, progress = null, error = null) }
        viewModelScope.launch {
            repository.publish(
                user = user,
                draft = NewPostDraft(
                    type = snapshot.type.raw,
                    title = snapshot.title,
                    content = snapshot.content,
                    privacy = snapshot.privacy.raw,
                    anonymous = snapshot.anonymous,
                    images = snapshot.images.map(ComposerImage::file),
                ),
                onProgress = { progress -> _state.update { it.copy(progress = progress) } },
            ).fold(
                onSuccess = { postId ->
                    snapshot.images.forEach { it.file.delete() }
                    _state.value = CreateUiState(publishedPostId = postId)
                },
                onFailure = { error ->
                    _state.update { it.copy(isPublishing = false, progress = null, error = error.createMessage()) }
                },
            )
        }
    }

    fun consumePublishedPost() = _state.update { it.copy(publishedPostId = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun discardDraft() {
        if (state.value.isPublishing) return
        state.value.images.forEach { it.file.delete() }
        _state.value = CreateUiState()
    }

    fun backToChooser() {
        if (!state.value.hasDraft && !state.value.isPublishing) _state.value = CreateUiState()
    }

    override fun onCleared() {
        state.value.images.forEach { it.file.delete() }
        super.onCleared()
    }
}

internal fun Throwable.createMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("Cloudinary", ignoreCase = true) -> "Image uploads are not configured for this build."
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to publish."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) -> "Your session expired. Sign in and try again."
        raw.isNotBlank() -> raw
        else -> "Your post could not be published. Please try again."
    }
}
