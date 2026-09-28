package com.nextbench.app.clubs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ClubRepository
import com.nextbench.data.model.Club
import com.nextbench.data.model.ClubSettings
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ClubSettingsNotice(val id: Long, val message: String, val isError: Boolean = false)

@Immutable
data class ClubSettingsUiState(
    val club: Club? = null,
    val members: Map<String, UserData> = emptyMap(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isLeaving: Boolean = false,
    val isManagingRole: Boolean = false,
    val memberActionTargetId: String? = null,
    val roleTargetId: String? = null,
    val roleAction: ClubRoleAction? = null,
    val leftClub: Boolean = false,
    val error: String? = null,
    val notice: ClubSettingsNotice? = null,
) {
    fun isLead(uid: String?): Boolean = !uid.isNullOrBlank() && uid == club?.leadId
    fun isMember(uid: String?): Boolean = !uid.isNullOrBlank() && uid in club?.memberIds.orEmpty()

    fun canBeginRoleAction(viewerId: String?, action: ClubRoleAction, targetId: String): Boolean {
        val currentClub = club ?: return false
        if (viewerId.isNullOrBlank() || isManagingRole) return false
        return when (action) {
            ClubRoleAction.Promote -> viewerId == currentClub.leadId && targetId in currentClub.memberIds && targetId != currentClub.leadId && targetId !in currentClub.coLeadIds
            ClubRoleAction.Demote -> viewerId == currentClub.leadId && targetId in currentClub.coLeadIds
            ClubRoleAction.Transfer -> viewerId == currentClub.leadId && targetId in currentClub.memberIds && targetId != currentClub.leadId
            ClubRoleAction.Remove -> (viewerId == currentClub.leadId || viewerId in currentClub.coLeadIds) && targetId in currentClub.memberIds && targetId != currentClub.leadId && targetId != viewerId && (viewerId == currentClub.leadId || targetId !in currentClub.coLeadIds)
        }
    }
}

enum class ClubRoleAction { Promote, Demote, Transfer, Remove }

@HiltViewModel
class ClubSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ClubRepository,
) : ViewModel() {
    private val clubId: String = requireNotNull(savedStateHandle["clubId"]) { "Club settings requires a clubId." }
    private val _state = MutableStateFlow(ClubSettingsUiState())
    val state: StateFlow<ClubSettingsUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var clubJob: Job? = null
    private var loadedMemberIds: List<String> = emptyList()
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        viewer = user
        clubJob?.cancel()
        loadedMemberIds = emptyList()
        _state.value = ClubSettingsUiState(isLoading = user != null)
        val uid = user?.uid ?: return
        clubJob = viewModelScope.launch {
            repository.observeClub(clubId, uid)
                .catch { error -> _state.update { it.copy(isLoading = false, error = error.clubMessage()) } }
                .collect { club ->
                    _state.update {
                        it.copy(
                            club = club,
                            isLoading = false,
                            error = if (club == null) "This club is no longer available." else null,
                        )
                    }
                    val visibleMemberIds = club?.memberIds.orEmpty().take(50)
                    if (visibleMemberIds != loadedMemberIds) {
                        loadedMemberIds = visibleMemberIds
                        repository.loadPublicMembers(uid, visibleMemberIds).onSuccess { users ->
                            if (loadedMemberIds == visibleMemberIds) {
                                _state.update { current -> current.copy(members = users.associateBy(UserData::uid)) }
                            }
                        }
                    }
                }
        }
    }

    fun updateSettings(transform: (ClubSettings) -> ClubSettings): Boolean {
        val uid = viewer?.uid ?: return false
        val snapshot = state.value
        val club = snapshot.club ?: return false
        if (!snapshot.isLead(uid) || snapshot.isSaving) return false
        val next = transform(club.settings)
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            repository.updateSettings(uid, clubId, next).fold(
                onSuccess = { _state.update { it.copy(isSaving = false) }; showNotice("Club settings updated.") },
                onFailure = { error -> _state.update { it.copy(isSaving = false) }; showNotice(error.clubMessage(), true) },
            )
        }
        return true
    }

    fun updateVisibility(): Boolean {
        val uid = viewer?.uid ?: return false
        val club = state.value.club ?: return false
        if (!state.value.isLead(uid) || state.value.isSaving) return false
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            repository.updateVisibility(uid, clubId, if (club.type == "public") "private" else "public").fold(
                onSuccess = { _state.update { it.copy(isSaving = false) }; showNotice("Club visibility updated.") },
                onFailure = { error -> _state.update { it.copy(isSaving = false) }; showNotice(error.clubMessage(), true) },
            )
        }
        return true
    }

    fun leaveClub(): Boolean {
        val uid = viewer?.uid ?: return false
        val snapshot = state.value
        if (!snapshot.isMember(uid) || snapshot.isLead(uid) || snapshot.isLeaving) return false
        _state.update { it.copy(isLeaving = true) }
        viewModelScope.launch {
            repository.leaveClub(uid, clubId).fold(
                onSuccess = { _state.update { it.copy(isLeaving = false, leftClub = true) } },
                onFailure = { error -> _state.update { it.copy(isLeaving = false) }; showNotice(error.clubMessage(), true) },
            )
        }
        return true
    }

    fun beginRoleAction(action: ClubRoleAction, targetId: String): Boolean {
        val uid = viewer?.uid ?: return false
        if (!state.value.canBeginRoleAction(uid, action, targetId)) return false
        _state.update { it.copy(memberActionTargetId = null, roleTargetId = targetId, roleAction = action, error = null) }
        return true
    }

    fun openMemberActions(targetId: String): Boolean {
        val uid = viewer?.uid ?: return false
        if (ClubRoleAction.entries.none { state.value.canBeginRoleAction(uid, it, targetId) }) return false
        _state.update { it.copy(memberActionTargetId = targetId) }
        return true
    }

    fun closeMemberActions() = _state.update { it.copy(memberActionTargetId = null) }

    fun cancelRoleAction() {
        if (!state.value.isManagingRole) _state.update { it.copy(roleTargetId = null, roleAction = null) }
    }

    fun confirmRoleAction(): Boolean {
        val uid = viewer?.uid ?: return false
        val snapshot = state.value
        val targetId = snapshot.roleTargetId ?: return false
        val action = snapshot.roleAction ?: return false
        if (snapshot.isManagingRole) return false
        _state.update { it.copy(isManagingRole = true, error = null) }
        viewModelScope.launch {
            val result = when (action) {
                ClubRoleAction.Promote -> repository.promoteCoLead(uid, clubId, targetId)
                ClubRoleAction.Demote -> repository.demoteCoLead(uid, clubId, targetId)
                ClubRoleAction.Transfer -> repository.transferLeadership(uid, clubId, targetId)
                ClubRoleAction.Remove -> repository.removeMember(uid, clubId, targetId)
            }
            result.fold(
                onSuccess = {
                    _state.update { it.copy(isManagingRole = false, roleTargetId = null, roleAction = null) }
                    showNotice(
                        when (action) {
                            ClubRoleAction.Promote -> "Member promoted to co-lead."
                            ClubRoleAction.Demote -> "Co-lead demoted to member."
                            ClubRoleAction.Transfer -> "Leadership transferred."
                            ClubRoleAction.Remove -> "Member removed from the club."
                        },
                    )
                },
                onFailure = { error ->
                    _state.update { it.copy(isManagingRole = false) }
                    showNotice(error.clubMessage(), true)
                },
            )
        }
        return true
    }

    fun dismissNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    private fun showNotice(message: String, isError: Boolean = false) {
        _state.update { it.copy(notice = ClubSettingsNotice(++noticeId, message, isError)) }
    }
}
