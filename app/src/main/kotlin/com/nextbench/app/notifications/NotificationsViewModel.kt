package com.nextbench.app.notifications

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.NotificationRepository
import com.nextbench.data.model.Notification
import com.nextbench.data.model.NotificationType
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

enum class NotificationFilter(val label: String) {
    All("All"),
    Deals("Deals"),
    Social("Social"),
    System("System"),
}

enum class NotificationNoticeKind { Info, Error }

@Immutable
data class NotificationNotice(
    val id: Long,
    val message: String,
    val kind: NotificationNoticeKind,
)

@Immutable
data class NotificationsUiState(
    val notifications: List<Notification> = emptyList(),
    val filter: NotificationFilter = NotificationFilter.All,
    val isLoading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
    val notice: NotificationNotice? = null,
) {
    val visibleNotifications: List<Notification>
        get() = notifications.filter { filter.matches(it.type) }
    val unreadCount: Int
        get() = visibleNotifications.count { !it.read }
    val unreadTotal: Int
        get() = notifications.count { !it.read }
    val counts: Map<NotificationFilter, Int>
        get() = NotificationFilter.entries.associateWith { selected -> notifications.count { !it.read && selected.matches(it.type) } }
}

private fun NotificationFilter.matches(type: String): Boolean = when (this) {
    NotificationFilter.All -> true
    NotificationFilter.Deals -> type in setOf("listing_approved", "listing_rejected", "item_reserved", "item_sold", "new_review")
    NotificationFilter.Social -> type in setOf("new_message", "mention", "new_post", "repost")
    NotificationFilter.System -> type in setOf("user_approved", "admin_promoted") || type !in setOf(
        "listing_approved", "listing_rejected", "item_reserved", "item_sold", "new_review", "new_message", "mention", "new_post", "repost",
    )
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var observeJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        viewer = user
        observeJob?.cancel()
        _state.value = NotificationsUiState(isLoading = user != null)
        val uid = user?.uid ?: return
        observeJob = viewModelScope.launch {
            repository.observeNotifications(uid)
                .catch { error -> _state.update { it.copy(isLoading = false, error = error.notificationMessage()) } }
                .collect { notifications -> _state.update { it.copy(notifications = notifications, isLoading = false, error = null) } }
        }
    }

    fun selectFilter(filter: NotificationFilter) = _state.update { it.copy(filter = filter) }

    fun markRead(notification: Notification): Boolean {
        val uid = viewer?.uid ?: return false
        if (notification.read || notification.id in state.value.busyIds) return false
        _state.update { it.copy(busyIds = it.busyIds + notification.id) }
        viewModelScope.launch {
            repository.markRead(notification.id, uid).fold(
                onSuccess = { _state.update { it.copy(busyIds = it.busyIds - notification.id) } },
                onFailure = { error ->
                    _state.update { it.copy(busyIds = it.busyIds - notification.id) }
                    showNotice(error.notificationMessage(), NotificationNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun markAllRead(): Boolean {
        val uid = viewer?.uid ?: return false
        val unreadIds = state.value.visibleNotifications.filterNot(Notification::read).map(Notification::id)
        if (unreadIds.isEmpty()) return false
        _state.update { it.copy(busyIds = it.busyIds + unreadIds) }
        viewModelScope.launch {
            repository.markAllRead(unreadIds, uid).fold(
                onSuccess = { _state.update { it.copy(busyIds = it.busyIds - unreadIds.toSet()) } },
                onFailure = { error ->
                    _state.update { it.copy(busyIds = it.busyIds - unreadIds.toSet()) }
                    showNotice(error.notificationMessage(), NotificationNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun delete(notification: Notification): Boolean {
        val uid = viewer?.uid ?: return false
        if (notification.id in state.value.busyIds) return false
        _state.update { it.copy(busyIds = it.busyIds + notification.id) }
        viewModelScope.launch {
            repository.delete(notification.id, uid).fold(
                onSuccess = {
                    _state.update { it.copy(busyIds = it.busyIds - notification.id, notifications = it.notifications.filterNot { current -> current.id == notification.id }) }
                    showNotice("Notification removed", NotificationNoticeKind.Info)
                },
                onFailure = { error ->
                    _state.update { it.copy(busyIds = it.busyIds - notification.id) }
                    showNotice(error.notificationMessage(), NotificationNoticeKind.Error)
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

    fun dismissNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    private fun showNotice(message: String, kind: NotificationNoticeKind) {
        _state.update { it.copy(notice = NotificationNotice(++noticeId, message, kind)) }
    }
}

internal fun Throwable.notificationMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to load notifications."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) -> "Your session expired. Sign in and try again."
        raw.isNotBlank() -> raw
        else -> "Unable to update notifications. Please try again."
    }
}
