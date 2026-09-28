package com.nextbench.app.clubs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbCard
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.NbToast
import com.nextbench.core.designsystem.NbToastKind
import com.nextbench.core.designsystem.NbToastState
import com.nextbench.data.firebase.ClubRepository
import com.nextbench.data.model.Club
import com.nextbench.data.model.ClubType
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ClubJoinUiState(
    val code: String = "",
    val club: Club? = null,
    val isLoading: Boolean = true,
    val isJoining: Boolean = false,
    val error: String? = null,
    val joinedClubId: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class ClubJoinViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ClubRepository,
) : ViewModel() {
    private val inviteCode: String = requireNotNull(savedStateHandle["inviteCode"])
    private val _state = MutableStateFlow(ClubJoinUiState(code = inviteCode))
    val state: StateFlow<ClubJoinUiState> = _state.asStateFlow()
    private var viewer: UserData? = null

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (user == null || state.value.club != null || state.value.error != null)) return
        viewer = user
        val uid = user?.uid ?: return
        viewModelScope.launch {
            repository.findByInviteCode(uid, inviteCode).fold(
                onSuccess = { club -> _state.update { it.copy(club = club, isLoading = false, error = null) } },
                onFailure = { error -> _state.update { it.copy(isLoading = false, error = error.clubMessage()) } },
            )
        }
    }

    fun join(): Boolean {
        val uid = viewer?.uid ?: return false
        if (state.value.isJoining || state.value.club == null) return false
        _state.update { it.copy(isJoining = true, notice = null) }
        viewModelScope.launch {
            repository.joinByInviteCode(uid, inviteCode).fold(
                onSuccess = { club ->
                    if (club == null) _state.update { it.copy(isJoining = false, error = "This invite is no longer valid.") }
                    else _state.update { it.copy(isJoining = false, joinedClubId = club.id, notice = "Welcome to ${club.name}.") }
                },
                onFailure = { error -> _state.update { it.copy(isJoining = false, error = error.clubMessage()) } },
            )
        }
        return true
    }
}

@Composable
fun ClubJoinScreen(
    user: UserData?,
    onOpenClub: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClubJoinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }
    LaunchedEffect(state.joinedClubId) { state.joinedClubId?.let(onOpenClub) }

    Box(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        when {
            state.isLoading -> Column(modifier = Modifier.padding(NbDimens.space24), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) { NbSkeletonLine(widthFraction = 0.8f, height = 160.dp); NbSkeletonLine(widthFraction = 0.6f, height = 28.dp); NbSkeletonLine(widthFraction = 1f, height = 72.dp) }
            state.error != null || state.club == null -> NbEmptyState(icon = NbIcons.Shield, title = "Invite not found", message = state.error ?: "This club invite is no longer active.", modifier = Modifier.fillMaxSize())
            else -> state.club?.let { club -> ClubJoinContent(club, state.isJoining, state.notice, onJoin = { viewModel.join() }) }
        }
    }
}

@Composable
private fun ClubJoinContent(club: Club, joining: Boolean, notice: String?, onJoin: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space16), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visible = true, enter = fadeIn(NbMotion.entryTween()) + slideInVertically(NbMotion.entryTween()) { it / 3 }) {
            NbCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(NbDimens.space24), verticalArrangement = Arrangement.spacedBy(NbDimens.space16), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(RoundedCornerShape(NbDimens.radiusLg)).background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(if (ClubType.from(club.type) == ClubType.Private) NbIcons.Shield else NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(34.dp))
                    }
                    Text(club.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.headlineSmall, color = NbTheme.colors.ink, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                        NbPill(label = if (ClubType.from(club.type) == ClubType.Private) "Private" else "Public", contentColor = if (ClubType.from(club.type) == ClubType.Private) NbTheme.colors.inkMuted else NbTheme.colors.brandTeal)
                        NbPill(label = "${club.memberCount} members", contentColor = NbTheme.colors.brandPink)
                    }
                    club.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted) }
                    NbButton(text = "Join club", onClick = onJoin, loading = joining, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Secondary)
                }
            }
        }
        notice?.let { Text(it, color = NbTheme.colors.brandMint, style = MaterialTheme.typography.bodySmall) }
    }
}
