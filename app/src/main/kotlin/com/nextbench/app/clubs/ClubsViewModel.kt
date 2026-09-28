package com.nextbench.app.clubs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ClubRepository
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

enum class ClubNoticeKind { Info, Success, Error }

@Immutable
data class ClubNotice(val id: Long, val message: String, val kind: ClubNoticeKind)

@Immutable
data class ClubsUiState(
    val memberClubs: List<Club> = emptyList(),
    val publicClubs: List<Club> = emptyList(),
    val inviteCode: String = "",
    val isLoading: Boolean = true,
    val isJoining: Boolean = false,
    val showCreateClub: Boolean = false,
    val clubName: String = "",
    val clubDescription: String = "",
    val clubType: String = "public",
    val isCreating: Boolean = false,
    val createdClubId: String? = null,
    val error: String? = null,
    val notice: ClubNotice? = null,
) {
    val canJoin: Boolean get() = !isJoining && inviteCode.isNotBlank()
    val canCreate: Boolean get() = !isCreating && clubName.trim().length >= 2 && clubType in setOf("public", "private")
}

@HiltViewModel
class ClubsViewModel @Inject constructor(
    private val repository: ClubRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ClubsUiState())
    val state: StateFlow<ClubsUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var memberJob: Job? = null
    private var publicJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        viewer = user
        memberJob?.cancel()
        publicJob?.cancel()
        _state.value = ClubsUiState(isLoading = user != null)
        val uid = user?.uid ?: return
        memberJob = viewModelScope.launch {
            repository.observeMemberClubs(uid)
                .catch { error -> _state.update { it.copy(isLoading = false, error = error.clubMessage()) } }
                .collect { clubs -> _state.update { it.copy(memberClubs = clubs, isLoading = false, error = null) } }
        }
        publicJob = viewModelScope.launch {
            repository.observePublicClubs(uid, user.school, user.city)
                .catch { error -> _state.update { it.copy(isLoading = false, error = error.clubMessage()) } }
                .collect { clubs -> _state.update { it.copy(publicClubs = clubs, isLoading = false, error = null) } }
        }
    }

    fun setInviteCode(value: String) {
        _state.update { it.copy(inviteCode = value.filterNot(Char::isWhitespace).take(8), error = null) }
    }

    fun joinByCode(): Boolean {
        val uid = viewer?.uid ?: return false
        if (!state.value.canJoin) return false
        val code = state.value.inviteCode
        _state.update { it.copy(isJoining = true, error = null) }
        viewModelScope.launch {
            repository.joinByInviteCode(uid, code).fold(
                onSuccess = { club ->
                    _state.update { it.copy(isJoining = false, inviteCode = "") }
                    showNotice(
                        if (club == null) "That invite code is not valid." else "Welcome to ${club.name}.",
                        if (club == null) ClubNoticeKind.Error else ClubNoticeKind.Success,
                    )
                },
                onFailure = { error ->
                    _state.update { it.copy(isJoining = false) }
                    showNotice(error.clubMessage(), ClubNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun joinPublicClub(club: Club): Boolean {
        val uid = viewer?.uid ?: return false
        if (state.value.isJoining || uid in club.memberIds) return false
        _state.update { it.copy(isJoining = true, error = null) }
        viewModelScope.launch {
            repository.joinPublicClub(uid, club.id).fold(
                onSuccess = { joined ->
                    _state.update { it.copy(isJoining = false) }
                    showNotice(if (joined == null) "This club is no longer available." else "Joined ${club.name}.", if (joined == null) ClubNoticeKind.Error else ClubNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isJoining = false) }
                    showNotice(error.clubMessage(), ClubNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun openCreateClub() = _state.update { it.copy(showCreateClub = true, error = null) }

    fun closeCreateClub() {
        if (state.value.isCreating) return
        _state.update {
            it.copy(
                showCreateClub = false,
                clubName = "",
                clubDescription = "",
                clubType = "public",
                error = null,
            )
        }
    }

    fun setClubName(value: String) = _state.update { it.copy(clubName = value.take(100), error = null) }
    fun setClubDescription(value: String) = _state.update { it.copy(clubDescription = value.take(500), error = null) }
    fun setClubType(value: String) = _state.update {
        it.copy(clubType = value.takeIf { type -> type == "public" || type == "private" } ?: it.clubType, error = null)
    }

    fun createClub(): Boolean {
        val creator = viewer ?: return false
        val snapshot = state.value
        if (!snapshot.canCreate) return false
        _state.update { it.copy(isCreating = true, error = null) }
        viewModelScope.launch {
            repository.createClub(
                creator = creator,
                name = snapshot.clubName,
                description = snapshot.clubDescription,
                type = snapshot.clubType,
            ).fold(
                onSuccess = { clubId ->
                    _state.update {
                        it.copy(
                            showCreateClub = false,
                            clubName = "",
                            clubDescription = "",
                            clubType = "public",
                            isCreating = false,
                            createdClubId = clubId,
                        )
                    }
                    showNotice("Club created.", ClubNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isCreating = false, error = error.clubMessage()) }
                    showNotice(error.clubMessage(), ClubNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun consumeCreatedClub() = _state.update { it.copy(createdClubId = null) }

    fun retry() {
        val current = viewer
        viewer = null
        syncViewer(current)
    }

    fun dismissNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    private fun showNotice(message: String, kind: ClubNoticeKind) {
        _state.update { it.copy(notice = ClubNotice(++noticeId, message, kind)) }
    }
}

internal fun Throwable.clubMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to load clubs."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) -> "Your session expired. Sign in and try again."
        raw.contains("PERMISSION_DENIED", ignoreCase = true) || raw.contains("permission", ignoreCase = true) -> "Verify your student account before joining or posting in clubs."
        raw.isNotBlank() -> raw
        else -> "Unable to update clubs. Please try again."
    }
}
