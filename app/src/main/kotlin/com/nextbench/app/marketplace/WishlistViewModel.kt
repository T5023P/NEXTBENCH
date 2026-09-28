package com.nextbench.app.marketplace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.SavedListing
import com.nextbench.data.firebase.WishlistRepository
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WishlistNoticeKind { Info, Error }

@Immutable
data class WishlistNotice(
    val id: Long,
    val message: String,
    val kind: WishlistNoticeKind,
)

@Immutable
data class WishlistUiState(
    val items: List<SavedListing> = emptyList(),
    val busyIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val notice: WishlistNotice? = null,
)

@HiltViewModel
class WishlistViewModel @Inject constructor(
    private val repository: WishlistRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WishlistUiState())
    val state: StateFlow<WishlistUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var observeJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        viewer = user
        observeJob?.cancel()
        _state.value = WishlistUiState(isLoading = user != null)
        val uid = user?.uid ?: return
        observeJob = viewModelScope.launch {
            repository.observeSavedListings(uid)
                .catch { error ->
                    _state.update { it.copy(isLoading = false, error = error.wishlistMessage()) }
                }
                .collect { items ->
                    _state.update { it.copy(items = items, isLoading = false, error = null) }
                }
        }
    }

    fun remove(item: SavedListing): Boolean {
        val uid = viewer?.uid ?: return false
        if (item.wishlistId in state.value.busyIds) return false
        _state.update { it.copy(busyIds = it.busyIds + item.wishlistId) }
        viewModelScope.launch {
            repository.remove(item.wishlistId, uid).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            items = it.items.filterNot { saved -> saved.wishlistId == item.wishlistId },
                            busyIds = it.busyIds - item.wishlistId,
                        )
                    }
                    showNotice("Removed from saved listings", WishlistNoticeKind.Info)
                },
                onFailure = { error ->
                    _state.update { it.copy(busyIds = it.busyIds - item.wishlistId) }
                    showNotice(error.wishlistMessage(), WishlistNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun retry() {
        val current = viewer
        viewer = null
        syncViewer(current)
    }

    fun dismissNotice(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    private fun showNotice(message: String, kind: WishlistNoticeKind) {
        _state.update { it.copy(notice = WishlistNotice(++noticeId, message, kind)) }
    }
}

internal fun Throwable.wishlistMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load saved listings."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.isNotBlank() -> raw
        else -> "Unable to update saved listings. Please try again."
    }
}
