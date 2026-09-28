package com.nextbench.app.verification

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.VerificationOutcome
import com.nextbench.data.firebase.VerificationRepository
import com.nextbench.data.firebase.VerificationStage
import com.nextbench.data.model.AccountType
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VerificationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VerificationStep { StudentId, Selfie, Status }

enum class VerificationPhotoTarget { StudentId, Selfie }

internal enum class AccountVerificationState {
    Capture,
    Rejected,
    Pending,
    ManualReview,
    Approved,
    OrganizationReview,
}

data class VerificationUiState(
    val step: VerificationStep = VerificationStep.StudentId,
    val idCard: File? = null,
    val selfie: File? = null,
    val preparingTarget: VerificationPhotoTarget? = null,
    val stage: VerificationStage? = null,
    val outcome: VerificationOutcome? = null,
    val rejectionReason: String? = null,
    val error: String? = null,
    val isSubmitting: Boolean = false,
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val repository: VerificationRepository,
    private val photoStore: VerificationPhotoStore,
) : ViewModel() {
    private val _state = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = _state.asStateFlow()

    private var pendingCapture: CapturedPhoto? = null
    private var pendingCaptureTarget: VerificationPhotoTarget? = null
    private var syncedAccountKey: String? = null
    // Whether identity evidence is on file, read from the owner-only pointer (never from a
    // field on the public profile document), plus the account it belongs to so a stale
    // observation cannot decide the state for a different user.
    private var submitted = false
    private var observedSubmissionUid: String? = null
    private var submissionJob: Job? = null
    private var lastUser: UserData? = null

    fun syncAccount(user: UserData) {
        lastUser = user
        observeSubmission(user.uid)
        val status = VerificationStatus.from(user.verificationStatus)
        val key = "${user.uid}:${user.accountType}:${user.verified}:${status.raw}:$submitted"
        if (key == syncedAccountKey || state.value.isSubmitting) return
        syncedAccountKey = key
        val accountState = accountVerificationState(user, submitted)
        _state.update { current ->
            when (accountState) {
                AccountVerificationState.Approved -> current.copy(
                    step = VerificationStep.Status,
                    stage = VerificationStage.Complete,
                    outcome = VerificationOutcome(status = VerificationStatus.Approved.raw, automated = true),
                    error = null,
                )
                AccountVerificationState.OrganizationReview -> current.copy(
                    step = VerificationStep.Status,
                    stage = VerificationStage.Complete,
                    outcome = VerificationOutcome(status = status.raw),
                    rejectionReason = user.verificationRejectionReason,
                    error = null,
                )
                AccountVerificationState.ManualReview -> current.copy(
                    step = VerificationStep.Status,
                    stage = VerificationStage.Complete,
                    outcome = VerificationOutcome(
                        status = status.raw,
                        reason = user.verificationRejectionReason,
                    ),
                    rejectionReason = user.verificationRejectionReason,
                    error = null,
                )
                AccountVerificationState.Pending -> current.copy(
                    step = VerificationStep.Status,
                    stage = VerificationStage.Complete,
                    outcome = VerificationOutcome(status = status.raw),
                    error = null,
                )
                AccountVerificationState.Rejected -> current.copy(
                    step = VerificationStep.StudentId,
                    rejectionReason = user.verificationRejectionReason,
                    outcome = null,
                    stage = null,
                    error = null,
                )
                AccountVerificationState.Capture -> current
            }
        }
    }

    /**
     * Tracks the owner-only verification pointer so the status screen reflects a submission
     * even though the client no longer stores identity evidence on the profile document.
     */
    private fun observeSubmission(uid: String) {
        if (observedSubmissionUid == uid) return
        observedSubmissionUid = uid
        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
            repository.observeSubmission(uid).collect { submission ->
                val next = submission != null
                if (next == submitted) return@collect
                submitted = next
                lastUser?.let { syncAccount(it) }
            }
        }
    }

    fun createCapture(target: VerificationPhotoTarget): Result<Uri> = runCatching {
        discardPendingCapture()
        val capture = photoStore.createCapture(target.filePrefix())
        pendingCapture = capture
        pendingCaptureTarget = target
        capture.uri
    }.onFailure { error ->
        _state.update { it.copy(error = error.userMessage("Camera could not start.")) }
    }

    fun completeCapture(success: Boolean) {
        val capture = pendingCapture
        val target = pendingCaptureTarget
        pendingCapture = null
        pendingCaptureTarget = null
        if (!success || capture == null || target == null) {
            capture?.file?.delete()
            return
        }
        prepare(target, capture.uri, capture.file)
    }

    fun preparePickedPhoto(target: VerificationPhotoTarget, uri: Uri) {
        prepare(target, uri, null)
    }

    fun continueFromId() {
        if (state.value.idCard == null) {
            _state.update { it.copy(error = "Add a clear photo of your student ID first.") }
            return
        }
        _state.update { it.copy(step = VerificationStep.Selfie, error = null) }
    }

    fun backToId() = _state.update {
        if (it.isSubmitting) it else it.copy(step = VerificationStep.StudentId, error = null)
    }

    fun submit(user: UserData) {
        val snapshot = state.value
        if (snapshot.isSubmitting) return
        val idCard = snapshot.idCard
        val selfie = snapshot.selfie
        if (idCard == null || selfie == null) {
            _state.update { it.copy(error = "Add both verification photos before submitting.") }
            return
        }
        _state.update {
            it.copy(
                step = VerificationStep.Status,
                stage = VerificationStage.Uploading,
                outcome = null,
                error = null,
                isSubmitting = true,
            )
        }
        viewModelScope.launch {
            val result = repository.submit(
                user = user,
                idCard = idCard,
                selfie = selfie,
                onStage = { stage -> _state.update { it.copy(stage = stage) } },
            )
            result.fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            stage = VerificationStage.Complete,
                            outcome = outcome,
                            rejectionReason = outcome.reason,
                            isSubmitting = false,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            stage = null,
                            error = error.userMessage("Verification could not be submitted."),
                            isSubmitting = false,
                        )
                    }
                },
            )
        }
    }

    fun retrySubmission() = _state.update(::verificationRetryState)

    fun restartAfterRejection() {
        if (state.value.isSubmitting) return
        state.value.idCard?.delete()
        state.value.selfie?.delete()
        _state.value = VerificationUiState(rejectionReason = state.value.rejectionReason)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun setExternalError(message: String) = _state.update {
        it.copy(error = message.takeIf(String::isNotBlank) ?: "The camera could not be opened.")
    }

    override fun onCleared() {
        submissionJob?.cancel()
        discardPendingCapture()
        state.value.idCard?.delete()
        state.value.selfie?.delete()
        super.onCleared()
    }

    private fun prepare(target: VerificationPhotoTarget, uri: Uri, sourceToDelete: File?) {
        if (state.value.preparingTarget != null || state.value.isSubmitting) return
        _state.update { it.copy(preparingTarget = target, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                photoStore.prepare(uri, target.filePrefix())
            }
            sourceToDelete?.delete()
            result.fold(
                onSuccess = { file ->
                    _state.update { current ->
                        when (target) {
                            VerificationPhotoTarget.StudentId -> {
                                current.idCard?.takeIf { it != file }?.delete()
                                current.copy(idCard = file, preparingTarget = null, error = null)
                            }
                            VerificationPhotoTarget.Selfie -> {
                                current.selfie?.takeIf { it != file }?.delete()
                                current.copy(selfie = file, preparingTarget = null, error = null)
                            }
                        }
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            preparingTarget = null,
                            error = error.userMessage("The photo could not be prepared."),
                        )
                    }
                },
            )
        }
    }

    private fun discardPendingCapture() {
        pendingCapture?.file?.delete()
        pendingCapture = null
        pendingCaptureTarget = null
    }
}

/**
 * Returns to the photo step with the prepared photos intact so a failed review can be resubmitted
 * without recapturing. A submission already in flight is left untouched.
 */
internal fun verificationRetryState(state: VerificationUiState): VerificationUiState =
    if (state.isSubmitting) state else state.copy(step = VerificationStep.Selfie, error = null, stage = null)

/**
 * Derives the account-level verification step.
 *
 * [submitted] comes from the owner-only `users/{uid}/private/verification` pointer, not from a
 * field on the profile document: identity evidence is no longer stored where every signed-in
 * user could read it.
 */
internal fun accountVerificationState(user: UserData, submitted: Boolean): AccountVerificationState {
    val status = VerificationStatus.from(user.verificationStatus)
    return when {
        user.verified || status == VerificationStatus.Approved -> AccountVerificationState.Approved
        AccountType.from(user.accountType) == AccountType.Organization -> AccountVerificationState.OrganizationReview
        status == VerificationStatus.FlaggedManual && submitted -> AccountVerificationState.ManualReview
        status == VerificationStatus.Pending && submitted -> AccountVerificationState.Pending
        status == VerificationStatus.Rejected -> AccountVerificationState.Rejected
        else -> AccountVerificationState.Capture
    }
}

private fun VerificationPhotoTarget.filePrefix(): String = when (this) {
    VerificationPhotoTarget.StudentId -> "student_id"
    VerificationPhotoTarget.Selfie -> "selfie"
}

private fun Throwable.userMessage(fallback: String): String = message
    ?.takeIf(String::isNotBlank)
    ?: fallback
