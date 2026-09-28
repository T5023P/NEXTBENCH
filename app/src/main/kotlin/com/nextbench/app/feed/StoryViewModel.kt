package com.nextbench.app.feed

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.StoryMediaDraft
import com.nextbench.data.firebase.StoryRepository
import com.nextbench.data.model.Story
import com.nextbench.data.model.StoryPrivacy
import com.nextbench.data.model.StoryTrayEntry
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class StoryComposerMedia(
    val prepared: PreparedStoryMedia,
)

@Immutable
data class StoryUiState(
    val tray: List<StoryTrayEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val likedStoryIds: Set<String> = emptySet(),
    val busyStoryIds: Set<String> = emptySet(),
    val composerMedia: StoryComposerMedia? = null,
    val privacy: StoryPrivacy = StoryPrivacy.Public,
    val isPreparingMedia: Boolean = false,
    val isPublishing: Boolean = false,
    val interactionMessage: String? = null,
    val replyCompletedStoryId: String? = null,
)

@HiltViewModel
class StoryViewModel @Inject constructor(
    private val repository: StoryRepository,
    private val mediaStore: StoryMediaStore,
) : ViewModel() {
    private val _state = MutableStateFlow(StoryUiState())
    val state: StateFlow<StoryUiState> = _state.asStateFlow()
    private var currentUid: String? = null
    private var loadJob: Job? = null

    fun syncUser(uid: String?) {
        if (uid == currentUid) return
        currentUid = uid
        loadJob?.cancel()
        clearComposer()
        if (uid.isNullOrBlank()) {
            _state.value = StoryUiState()
        } else {
            refresh()
        }
    }

    fun refresh() {
        if (currentUid.isNullOrBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.loadTray().fold(
                onSuccess = { tray -> _state.update { it.copy(tray = tray, isLoading = false, error = null) } },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.storyMessage("Stories are unavailable right now."),
                        )
                    }
                },
            )
        }
    }

    fun markSeen(entry: StoryTrayEntry) {
        _state.update { state ->
            state.copy(tray = state.tray.map { if (it.authorId == entry.authorId) it.copy(allSeen = true) else it })
        }
        viewModelScope.launch { repository.markSeen(entry) }
    }

    fun recordView(story: Story) {
        viewModelScope.launch { repository.recordView(story) }
    }

    fun loadLiked(storyId: String) {
        if (storyId in state.value.likedStoryIds) return
        viewModelScope.launch {
            repository.hasLiked(storyId).onSuccess { liked ->
                if (liked) _state.update { it.copy(likedStoryIds = it.likedStoryIds + storyId) }
            }
        }
    }

    fun toggleLike(storyId: String) {
        if (storyId in state.value.busyStoryIds) return
        _state.update { it.copy(busyStoryIds = it.busyStoryIds + storyId) }
        viewModelScope.launch {
            repository.toggleLike(storyId).fold(
                onSuccess = { liked ->
                    _state.update {
                        it.copy(
                            likedStoryIds = if (liked) it.likedStoryIds + storyId else it.likedStoryIds - storyId,
                            busyStoryIds = it.busyStoryIds - storyId,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busyStoryIds = it.busyStoryIds - storyId,
                            interactionMessage = error.storyMessage("The story could not be liked."),
                        )
                    }
                },
            )
        }
    }

    fun reply(user: UserData, storyId: String, content: String) {
        if (storyId in state.value.busyStoryIds) return
        _state.update { it.copy(busyStoryIds = it.busyStoryIds + storyId, replyCompletedStoryId = null) }
        viewModelScope.launch {
            repository.reply(
                storyId = storyId,
                username = user.username?.takeIf(String::isNotBlank) ?: user.name,
                content = content,
            ).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            busyStoryIds = it.busyStoryIds - storyId,
                            replyCompletedStoryId = storyId,
                            interactionMessage = "Reply sent",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busyStoryIds = it.busyStoryIds - storyId,
                            interactionMessage = error.storyMessage("The reply could not be sent."),
                        )
                    }
                },
            )
        }
    }

    fun consumeReplyCompletion() = _state.update { it.copy(replyCompletedStoryId = null) }

    fun prepareMedia(uri: Uri) {
        if (state.value.isPreparingMedia || state.value.isPublishing) return
        clearComposer()
        _state.update { it.copy(isPreparingMedia = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { mediaStore.prepare(uri) }
            result.fold(
                onSuccess = { media ->
                    _state.update { it.copy(composerMedia = StoryComposerMedia(media), isPreparingMedia = false) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isPreparingMedia = false,
                            interactionMessage = error.storyMessage("The selected media could not be prepared."),
                        )
                    }
                },
            )
        }
    }

    fun selectPrivacy(privacy: StoryPrivacy) = _state.update { it.copy(privacy = privacy) }

    fun publish(user: UserData) {
        val media = state.value.composerMedia?.prepared ?: return
        if (state.value.isPublishing) return
        _state.update { it.copy(isPublishing = true, interactionMessage = null) }
        viewModelScope.launch {
            repository.publish(
                user = user,
                draft = StoryMediaDraft(
                    file = media.file,
                    mimeType = media.mimeType,
                    width = media.width,
                    height = media.height,
                    durationMs = media.durationMs,
                ),
                privacy = state.value.privacy,
            ).fold(
                onSuccess = {
                    media.file.delete()
                    _state.update {
                        it.copy(
                            composerMedia = null,
                            privacy = StoryPrivacy.Public,
                            isPublishing = false,
                            interactionMessage = "Story shared",
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isPublishing = false,
                            interactionMessage = error.storyMessage("The story could not be shared."),
                        )
                    }
                },
            )
        }
    }

    fun delete(story: Story) {
        if (story.id in state.value.busyStoryIds) return
        _state.update { it.copy(busyStoryIds = it.busyStoryIds + story.id) }
        viewModelScope.launch {
            repository.delete(story).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            busyStoryIds = it.busyStoryIds - story.id,
                            interactionMessage = "Story deleted",
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busyStoryIds = it.busyStoryIds - story.id,
                            interactionMessage = error.storyMessage("The story could not be deleted."),
                        )
                    }
                },
            )
        }
    }

    fun dismissComposer() {
        if (state.value.isPublishing) return
        clearComposer()
    }

    fun dismissMessage() = _state.update { it.copy(interactionMessage = null) }

    private fun clearComposer() {
        state.value.composerMedia?.prepared?.file?.delete()
        _state.update {
            it.copy(
                composerMedia = null,
                privacy = StoryPrivacy.Public,
                isPreparingMedia = false,
                isPublishing = false,
            )
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        state.value.composerMedia?.prepared?.file?.delete()
        super.onCleared()
    }
}

private fun Throwable.storyMessage(fallback: String): String {
    val detail = message.orEmpty()
    return when {
        detail.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build."
        detail.contains("permission", ignoreCase = true) -> "Your account cannot do that yet. Check verification and privacy settings."
        detail.isNotBlank() && detail.length <= 140 -> detail
        else -> fallback
    }
}
