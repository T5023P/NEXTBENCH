package com.nextbench.app.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.BlockRepository
import com.nextbench.data.firebase.PublicProfileContent
import com.nextbench.data.firebase.PublicProfileRepository
import com.nextbench.data.firebase.PublicProfileStats
import com.nextbench.data.firebase.ChatRepository
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Immutable
data class PublicProfileUiState(
    val user: UserData? = null,
    val listings: List<Product> = emptyList(),
    val posts: List<Post> = emptyList(),
    val tab: ProfileTab = ProfileTab.Listings,
    val isLoading: Boolean = true,
    val error: String? = null,
    val resolvedId: String? = null,
    val stats: PublicProfileStats = PublicProfileStats(),
    val isFollowingBusy: Boolean = false,
    val isStartingChat: Boolean = false,
    val pendingRoomId: String? = null,
    val actionError: String? = null,
    val blockedByViewer: Boolean = false,
    val isBlockBusy: Boolean = false,
    val showBlockConfirmation: Boolean = false,
)

@HiltViewModel
class PublicProfileViewModel @Inject constructor(
    private val repository: PublicProfileRepository,
    private val chatRepository: ChatRepository,
    private val blockRepository: BlockRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PublicProfileUiState())
    val state: StateFlow<PublicProfileUiState> = _state.asStateFlow()

    private var blockJob: Job? = null
    private var observedBlockKey: String? = null

    fun load(key: String, username: Boolean, viewerId: String? = null) {
        if (key.isBlank()) return
        if (state.value.resolvedId == key && state.value.user != null) {
            // Already loaded; only make sure the block observer tracks the current viewer.
            observeBlock(state.value.resolvedId, viewerId)
            return
        }
        blockJob?.cancel()
        observedBlockKey = null
        _state.value = PublicProfileUiState(isLoading = true)
        viewModelScope.launch {
            val result = if (username) repository.resolveUsername(key) else Result.success(key)
            result.fold(
                onSuccess = { resolved ->
                    if (resolved.isNullOrBlank()) {
                        observeBlock(null, null)
                        _state.value = PublicProfileUiState(isLoading = false, error = "That profile could not be found.")
                    } else {
                        repository.load(resolved).fold(
                            onSuccess = { content ->
                                _state.value = content.toUiState(resolved)
                                observeBlock(resolved, viewerId)
                            },
                            onFailure = { error ->
                                observeBlock(null, null)
                                _state.value = PublicProfileUiState(isLoading = false, error = error.publicProfileMessage())
                            },
                        )
                    }
                },
                onFailure = { error ->
                    observeBlock(null, null)
                    _state.value = PublicProfileUiState(isLoading = false, error = error.publicProfileMessage())
                },
            )
        }
    }

    fun retry(key: String, username: Boolean, viewerId: String? = null) {
        blockJob?.cancel()
        observedBlockKey = null
        _state.value = PublicProfileUiState()
        load(key, username, viewerId)
    }

    fun selectTab(tab: ProfileTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun toggleFollow(viewer: UserData?) {
        val targetId = state.value.resolvedId ?: return
        val currentViewer = viewer ?: return
        if (state.value.isFollowingBusy || currentViewer.uid == targetId) return
        _state.value = state.value.copy(isFollowingBusy = true, actionError = null)
        viewModelScope.launch {
            repository.toggleFollow(targetId, currentViewer).fold(
                onSuccess = { following ->
                    _state.value = state.value.copy(
                        isFollowingBusy = false,
                        stats = state.value.stats.copy(
                            isFollowing = following,
                            followersCount = (state.value.stats.followersCount + if (following) 1 else -1).coerceAtLeast(0),
                        ),
                    )
                },
                onFailure = { error ->
                    _state.value = state.value.copy(isFollowingBusy = false, actionError = error.publicProfileMessage())
                },
            )
        }
    }

    fun startChat(viewer: UserData?) {
        val targetId = state.value.resolvedId ?: return
        val currentViewer = viewer ?: return
        if (state.value.isStartingChat || currentViewer.uid == targetId) return
        _state.value = state.value.copy(isStartingChat = true, actionError = null)
        viewModelScope.launch {
            chatRepository.getOrCreateDirectRoom(currentViewer, targetId).fold(
                onSuccess = { roomId -> _state.value = state.value.copy(isStartingChat = false, pendingRoomId = roomId) },
                onFailure = { error -> _state.value = state.value.copy(isStartingChat = false, actionError = error.publicProfileMessage()) },
            )
        }
    }

    fun consumeRoom(roomId: String) {
        if (state.value.pendingRoomId == roomId) _state.value = state.value.copy(pendingRoomId = null)
    }

    fun dismissActionError() {
        if (state.value.actionError != null) _state.value = state.value.copy(actionError = null)
    }

    fun requestBlock() {
        if (state.value.blockedByViewer || state.value.isBlockBusy) return
        _state.value = state.value.copy(showBlockConfirmation = true)
    }

    fun cancelBlockRequest() {
        if (state.value.isBlockBusy) return
        _state.value = state.value.copy(showBlockConfirmation = false)
    }

    fun confirmBlock(viewer: UserData?) {
        val targetId = state.value.resolvedId ?: return
        val currentViewer = viewer ?: return
        if (state.value.isBlockBusy || currentViewer.uid == targetId) return
        _state.value = state.value.copy(isBlockBusy = true, showBlockConfirmation = false, actionError = null)
        viewModelScope.launch {
            // Nothing is written to `blockedByViewer` here: the live block observer below is the only
            // writer, so a rejected or failed write cannot leave the UI claiming the block succeeded.
            blockRepository.blockUser(currentViewer.uid, targetId).fold(
                onSuccess = { _state.value = state.value.copy(isBlockBusy = false) },
                onFailure = { error ->
                    _state.value = state.value.copy(isBlockBusy = false, actionError = error.publicProfileMessage())
                },
            )
        }
    }

    fun unblock(viewer: UserData?) {
        val targetId = state.value.resolvedId ?: return
        val currentViewer = viewer ?: return
        if (state.value.isBlockBusy) return
        _state.value = state.value.copy(isBlockBusy = true, actionError = null)
        viewModelScope.launch {
            blockRepository.unblockUser(currentViewer.uid, targetId).fold(
                onSuccess = { _state.value = state.value.copy(isBlockBusy = false) },
                onFailure = { error ->
                    _state.value = state.value.copy(isBlockBusy = false, actionError = error.publicProfileMessage())
                },
            )
        }
    }

    /**
     * Tracks the canonical block document for the current viewer/target pair. Block state is read
     * back from Firestore instead of being mirrored locally, so blocking or unblocking reconciles
     * without a reload and there is exactly one source of truth.
     */
    private fun observeBlock(targetId: String?, viewerId: String?) {
        if (targetId.isNullOrBlank() || viewerId.isNullOrBlank() || targetId == viewerId) {
            blockJob?.cancel()
            blockJob = null
            observedBlockKey = null
            _state.value = _state.value.withBlockedByViewer(false)
            return
        }
        val key = "$viewerId:$targetId"
        if (observedBlockKey == key && blockJob?.isActive == true) return
        observedBlockKey = key
        blockJob?.cancel()
        blockJob = viewModelScope.launch {
            blockRepository.observeBlockedByViewer(viewerId, targetId)
                .catch { error -> _state.value = _state.value.copy(actionError = error.publicProfileMessage()) }
                .collect { blocked -> _state.value = _state.value.withBlockedByViewer(blocked) }
        }
    }
}

private fun PublicProfileContent.toUiState(id: String) = PublicProfileUiState(
    user = user,
    listings = listings,
    posts = posts,
    stats = stats,
    isLoading = false,
    resolvedId = id,
)

/**
 * Applies an observed block state. A block removes the follow rows in both directions, so cached
 * follow flags must not outlive it; otherwise a later unblock would show a stale "Following".
 */
internal fun PublicProfileUiState.withBlockedByViewer(blocked: Boolean): PublicProfileUiState =
    if (blocked) {
        copy(blockedByViewer = true, stats = stats.copy(isFollowing = false, isFollowedBy = false))
    } else {
        copy(blockedByViewer = false)
    }

/** States exactly what blocking does before the member confirms it. */
internal fun blockConfirmationBody(targetName: String): String =
    "Blocking $targetName hides their profile and listings from you, removes any follow between you, " +
        "and stops you both from messaging each other. They are not told."

internal fun Throwable.publicProfileMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to view profiles."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.isNotBlank() -> raw
        else -> "This profile could not be loaded. Please try again."
    }
}
