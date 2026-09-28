package com.nextbench.app.invite

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.InviteContent
import com.nextbench.data.firebase.InviteRepository
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

enum class InviteNoticeKind { Info, Success, Error }

@Immutable
data class InviteNotice(
    val id: Long,
    val message: String,
    val kind: InviteNoticeKind,
)

@Immutable
data class InviteUiState(
    val inviteCode: String? = null,
    val referralCount: Int = 0,
    val referredBy: String? = null,
    val redemptionCode: String = "",
    val isLoading: Boolean = true,
    val isGenerating: Boolean = false,
    val isRedeeming: Boolean = false,
    val error: String? = null,
    val notice: InviteNotice? = null,
) {
    val hasUsedReferral: Boolean get() = !referredBy.isNullOrBlank()
    val inviteLink: String? get() = inviteCode?.let(::inviteLinkForCode)
    val canRedeem: Boolean get() = !isRedeeming && !hasUsedReferral && redemptionCode.length == InviteCodeLength
}

@HiltViewModel
class InviteViewModel @Inject constructor(
    private val repository: InviteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    private var viewerUid: String? = null
    private var observeJob: Job? = null
    private var noticeId = 0L

    fun syncViewer(user: UserData?) {
        val uid = user?.uid?.takeIf(String::isNotBlank)
        if (viewerUid == uid && (uid == null || state.value.inviteCode != null || state.value.isLoading)) return
        viewerUid = uid
        observeJob?.cancel()
        _state.value = InviteUiState(
            inviteCode = user?.referralCode?.trim()?.takeIf(String::isNotBlank),
            referralCount = user?.referralCount?.coerceAtLeast(0) ?: 0,
            referredBy = user?.referredBy,
            isLoading = uid != null,
        )
        if (uid == null) return

        observeJob = viewModelScope.launch {
            repository.observeInvite(uid)
                .catch { error ->
                    _state.update { it.copy(isLoading = false, error = error.inviteMessage()) }
                }
                .collect { content ->
                    _state.update { it.merge(content) }
                }
        }
    }

    fun setRedemptionCode(value: String) {
        _state.update {
            it.copy(
                redemptionCode = normalizeInviteCode(value),
                error = null,
            )
        }
    }

    fun generateCode(): Boolean {
        val uid = viewerUid ?: return false
        if (state.value.isGenerating) return false
        _state.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch {
            repository.createCode(uid).fold(
                onSuccess = { code ->
                    _state.update {
                        it.copy(inviteCode = code, isGenerating = false, error = null)
                    }
                    showNotice("Your invite link is ready to share.", InviteNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isGenerating = false, error = error.inviteMessage()) }
                },
            )
        }
        return true
    }

    fun redeemCode(): Boolean {
        val uid = viewerUid ?: return false
        val code = state.value.redemptionCode
        when {
            state.value.isRedeeming -> return false
            state.value.hasUsedReferral -> {
                showNotice("An invite code has already been applied to your account.", InviteNoticeKind.Info)
                return false
            }
            code.length != InviteCodeLength -> {
                showNotice("Enter the 8-character invite code to continue.", InviteNoticeKind.Error)
                return false
            }
        }

        _state.update { it.copy(isRedeeming = true, error = null) }
        viewModelScope.launch {
            repository.redeemCode(uid, code).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            redemptionCode = "",
                            referredBy = ReferralAppliedMarker,
                            isRedeeming = false,
                            error = null,
                        )
                    }
                    showNotice("Invite accepted. Welcome to the circle.", InviteNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isRedeeming = false, error = null) }
                    showNotice(error.inviteMessage(), InviteNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun inviteLinkCopied() {
        showNotice("Invite link copied.", InviteNoticeKind.Success)
    }

    fun retry() {
        val currentUid = viewerUid ?: return
        viewerUid = null
        syncViewer(UserData(uid = currentUid))
    }

    fun dismissNotice(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    private fun showNotice(message: String, kind: InviteNoticeKind) {
        _state.update { it.copy(notice = InviteNotice(++noticeId, message, kind)) }
    }

    private fun InviteUiState.merge(content: InviteContent): InviteUiState = copy(
        inviteCode = content.referralCode ?: inviteCode,
        referralCount = content.referralCount,
        referredBy = content.referredBy ?: referredBy,
        isLoading = false,
        error = null,
    )
}

internal const val InviteCodeLength = 8
internal const val ReferralAppliedMarker = "applied"

internal fun normalizeInviteCode(value: String): String =
    value.filter(Char::isLetterOrDigit).uppercase().take(InviteCodeLength)

internal fun inviteLinkForCode(code: String): String =
    "https://www.nextbench.in/?ref=${code.trim().uppercase()}"

internal fun Throwable.inviteMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load invites."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("unauthenticated", ignoreCase = true) || raw.contains("session expired", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.contains("already", ignoreCase = true) && raw.contains("referral", ignoreCase = true) ->
            "An invite code has already been applied to your account."
        raw.contains("24 hours", ignoreCase = true) || raw.contains("new accounts", ignoreCase = true) ->
            "Invite codes can only be applied during your first 24 hours."
        raw.contains("own referral", ignoreCase = true) ->
            "You cannot use your own invite code."
        raw.contains("invalid referral", ignoreCase = true) || raw.contains("not-found", ignoreCase = true) ->
            "That invite code is not valid. Check it and try again."
        raw.isNotBlank() -> raw
        else -> "Unable to update invites. Please try again."
    }
}
