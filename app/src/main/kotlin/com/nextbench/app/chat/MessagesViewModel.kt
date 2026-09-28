package com.nextbench.app.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ChatRepository
import com.nextbench.data.firebase.ChatRoomListItem
import com.nextbench.data.firebase.ClubRepository
import com.nextbench.data.firebase.InboxBulkOperation
import com.nextbench.data.model.Club
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

/** A unified inbox entry covering both direct messages and club chats. */
sealed interface InboxItem {
    /** Stable key for use as a LazyColumn item key (prefixed to avoid id collisions). */
    val listKey: String
    val sortMillis: Long
    val isPinned: Boolean

    @Immutable
    data class DirectMessage(val room: ChatRoomListItem) : InboxItem {
        override val listKey get() = "dm:${room.room.id}"
        override val sortMillis get() = room.room.updatedAt?.toDate()?.time ?: Long.MIN_VALUE
        override val isPinned get() = room.pinned
    }

    @Immutable
    data class ClubItem(val club: Club, val viewerId: String) : InboxItem {
        override val listKey get() = "club:${club.id}"
        override val sortMillis get() = club.updatedAt?.toDate()?.time ?: Long.MIN_VALUE
        override val isPinned get() = viewerId in club.pinnedBy
        val unread: Boolean get() = viewerId in club.unreadBy && viewerId !in club.mutedBy
        val muted: Boolean get() = viewerId in club.mutedBy
    }
}

@Immutable
data class MessagesUiState(
    val rooms: List<ChatRoomListItem> = emptyList(),
    val memberClubs: List<Club> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewerId: String? = null,
    val busyRoomIds: Set<String> = emptySet(),
    val selectedRoomIds: Set<String> = emptySet(),
    val isBulkActionRunning: Boolean = false,
    val notice: ChatNotice? = null,
) {
    /** DM-only filtered list — used by bulk-action selection logic. */
    val visibleRooms: List<ChatRoomListItem>
        get() = rooms.filter { item ->
            val viewerId = viewerId ?: return@filter false
            if (item.deleted) return@filter false
            if (showArchived != item.archived) return@filter false
            val search = query.trim()
            search.isBlank() || listOfNotNull(
                item.otherUser?.name,
                item.room.productTitle,
                item.room.lastMessage,
            ).any { it.contains(search, ignoreCase = true) }
        }

    /** Merged DM + club list shown in the inbox. */
    val visibleItems: List<InboxItem>
        get() {
            val uid = viewerId ?: return emptyList()
            val search = query.trim()

            val dmItems = rooms
                .filter { item ->
                    if (item.deleted) return@filter false
                    if (showArchived != item.archived) return@filter false
                    search.isBlank() || listOfNotNull(
                        item.otherUser?.name,
                        item.room.productTitle,
                        item.room.lastMessage,
                    ).any { it.contains(search, ignoreCase = true) }
                }
                .map { InboxItem.DirectMessage(it) }

            // Clubs only appear in the active (non-archived) view for now.
            val clubItems = if (!showArchived) {
                memberClubs
                    .filter { club ->
                        val deleted = uid in club.deletedBy && uid !in club.unreadBy
                        if (deleted) return@filter false
                        search.isBlank() || listOf(club.name, club.lastMessage.orEmpty())
                            .any { it.contains(search, ignoreCase = true) }
                    }
                    .map { InboxItem.ClubItem(it, uid) }
            } else emptyList()

            return (dmItems + clubItems)
                .sortedWith(
                    compareByDescending<InboxItem> { it.isPinned }
                        .thenByDescending { it.sortMillis },
                )
        }

    val archivedCount: Int
        get() = rooms.count { item -> !item.deleted && item.archived }
    val selectedRooms: List<ChatRoomListItem>
        get() = rooms.filter { it.room.id in selectedRoomIds }
    val selectionMode: Boolean get() = selectedRoomIds.isNotEmpty()
    val allSelectedPinned: Boolean get() = selectedRooms.isNotEmpty() && selectedRooms.all(ChatRoomListItem::pinned)
    val allSelectedRead: Boolean get() = selectedRooms.isNotEmpty() && selectedRooms.none(ChatRoomListItem::hasUnreadActivity)
    val allSelectedMuted: Boolean get() = selectedRooms.isNotEmpty() && selectedRooms.all(ChatRoomListItem::muted)
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val clubRepository: ClubRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var roomsJob: Job? = null
    private var clubsJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        viewer = user
        roomsJob?.cancel()
        clubsJob?.cancel()
        _state.value = MessagesUiState(
            isLoading = user != null,
            viewerId = user?.uid,
        )
        val uid = user?.uid ?: return
        roomsJob = viewModelScope.launch {
            repository.observeRooms(uid)
                .catch { error ->
                    _state.update {
                        it.copy(isLoading = false, error = error.chatMessage())
                    }
                }
                .collect { rooms ->
                    _state.update {
                        it.copy(
                            rooms = rooms,
                            selectedRoomIds = it.selectedRoomIds.intersect(rooms.map { room -> room.room.id }.toSet()),
                            isLoading = false,
                            error = null,
                        )
                    }
                }
        }
        clubsJob = viewModelScope.launch {
            clubRepository.observeMemberClubs(uid)
                .catch { /* clubs are supplementary; swallow errors silently */ }
                .collect { clubs -> _state.update { it.copy(memberClubs = clubs) } }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value.take(MaxSearchLength)) }
    }

    fun toggleArchived() {
        _state.update { it.copy(showArchived = !it.showArchived, selectedRoomIds = emptySet()) }
    }

    fun toggleSelection(item: ChatRoomListItem): Boolean {
        val roomId = item.room.id
        if (roomId.isBlank() || roomId in state.value.busyRoomIds) return false
        val selected = state.value.selectedRoomIds
        if (roomId !in selected && selected.size >= ChatRepository.MaxInboxSelection) {
            showNotice("Select up to ${ChatRepository.MaxInboxSelection} conversations at once.", ChatNoticeKind.Info)
            return false
        }
        _state.update {
            it.copy(selectedRoomIds = if (roomId in it.selectedRoomIds) it.selectedRoomIds - roomId else it.selectedRoomIds + roomId)
        }
        return true
    }

    fun clearSelection() = _state.update { it.copy(selectedRoomIds = emptySet()) }

    fun bulkTogglePin(): Boolean = bulkAction(if (state.value.allSelectedPinned) InboxBulkOperation.Unpin else InboxBulkOperation.Pin)

    fun bulkToggleRead(): Boolean = bulkAction(if (state.value.allSelectedRead) InboxBulkOperation.MarkUnread else InboxBulkOperation.MarkRead)

    fun bulkToggleMute(): Boolean = bulkAction(if (state.value.allSelectedMuted) InboxBulkOperation.Unmute else InboxBulkOperation.Mute)

    fun bulkToggleArchive(): Boolean = bulkAction(if (state.value.showArchived) InboxBulkOperation.Restore else InboxBulkOperation.Archive)

    fun bulkDelete(): Boolean = bulkAction(InboxBulkOperation.Delete)

    fun toggleArchive(item: ChatRoomListItem): Boolean = roomAction(
        item = item,
        successMessage = if (item.archived) "Conversation restored" else "Conversation archived",
    ) { roomId, uid -> repository.setArchived(roomId, uid, !item.archived) }

    fun toggleMute(item: ChatRoomListItem): Boolean = roomAction(
        item = item,
        successMessage = if (item.muted) "Notifications unmuted" else "Notifications muted",
    ) { roomId, uid -> repository.setMuted(roomId, uid, !item.muted) }

    fun togglePin(item: ChatRoomListItem): Boolean = roomAction(
        item = item,
        successMessage = if (item.pinned) "Conversation unpinned" else "Conversation pinned",
    ) { roomId, uid -> repository.setPinned(roomId, uid, !item.pinned) }

    fun toggleRead(item: ChatRoomListItem): Boolean = roomAction(
        item = item,
        successMessage = if (item.hasUnreadActivity) "Marked as read" else "Marked as unread",
    ) { roomId, uid -> repository.setUnread(roomId, uid, !item.hasUnreadActivity) }

    fun delete(item: ChatRoomListItem): Boolean = roomAction(
        item = item,
        successMessage = "Conversation removed",
    ) { roomId, uid -> repository.deleteForUser(roomId, uid) }

    fun dismissNotice(id: Long) {
        _state.update { current -> if (current.notice?.id == id) current.copy(notice = null) else current }
    }

    fun retry() {
        val current = viewer
        viewer = null
        syncViewer(current)
    }

    private fun roomAction(
        item: ChatRoomListItem,
        successMessage: String,
        operation: suspend (roomId: String, uid: String) -> Result<Unit>,
    ): Boolean {
        val uid = viewer?.uid ?: return false
        val roomId = item.room.id
        if (roomId.isBlank() || roomId in state.value.busyRoomIds) return false
        _state.update { it.copy(busyRoomIds = it.busyRoomIds + roomId) }
        viewModelScope.launch {
            operation(roomId, uid).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            busyRoomIds = it.busyRoomIds - roomId,
                            notice = ChatNotice(++noticeId, successMessage, ChatNoticeKind.Success),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busyRoomIds = it.busyRoomIds - roomId,
                            notice = ChatNotice(++noticeId, error.chatMessage(), ChatNoticeKind.Error),
                        )
                    }
                },
            )
        }
        return true
    }

    private fun bulkAction(operation: InboxBulkOperation): Boolean {
        val uid = viewer?.uid ?: return false
        val selectedIds = state.value.selectedRoomIds
        if (selectedIds.isEmpty() || state.value.isBulkActionRunning) return false
        _state.update { it.copy(isBulkActionRunning = true, busyRoomIds = it.busyRoomIds + selectedIds) }
        viewModelScope.launch {
            repository.updateInboxBulk(selectedIds, uid, operation).fold(
                onSuccess = { result ->
                    val message = operation.resultMessage(result.updatedRooms, result.failedRooms)
                    _state.update {
                        it.copy(
                            isBulkActionRunning = false,
                            busyRoomIds = it.busyRoomIds - selectedIds,
                            selectedRoomIds = emptySet(),
                            notice = ChatNotice(
                                ++noticeId,
                                message,
                                if (result.updatedRooms > 0) ChatNoticeKind.Success else ChatNoticeKind.Error,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isBulkActionRunning = false,
                            busyRoomIds = it.busyRoomIds - selectedIds,
                            notice = ChatNotice(++noticeId, error.chatMessage(), ChatNoticeKind.Error),
                        )
                    }
                },
            )
        }
        return true
    }

    private fun showNotice(message: String, kind: ChatNoticeKind) {
        _state.update { it.copy(notice = ChatNotice(++noticeId, message, kind)) }
    }

    companion object {
        private const val MaxSearchLength = 80
    }
}

private fun InboxBulkOperation.resultMessage(updated: Int, failed: Int): String {
    val action = when (this) {
        InboxBulkOperation.Pin -> "Pinned"
        InboxBulkOperation.Unpin -> "Unpinned"
        InboxBulkOperation.MarkRead -> "Marked read"
        InboxBulkOperation.MarkUnread -> "Marked unread"
        InboxBulkOperation.Mute -> "Muted"
        InboxBulkOperation.Unmute -> "Unmuted"
        InboxBulkOperation.Archive -> "Archived"
        InboxBulkOperation.Restore -> "Restored"
        InboxBulkOperation.Delete -> "Removed"
    }
    return if (failed > 0) "$action $updated conversation${if (updated == 1) "" else "s"}; $failed failed" else "$action $updated conversation${if (updated == 1) "" else "s"}"
}
