package com.nextbench.app.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ReportRepository
import com.nextbench.data.model.ReportContentType
import com.nextbench.data.model.ReportReason
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reporting is a single-shot, write-only flow: `reports` is admin-readable, so there is no status
 * to poll and no list to show. The state therefore only tracks the sheet, the chosen reason, the
 * optional note, and the one submit attempt.
 */
data class ReportUiState(
    val open: Boolean = false,
    val reason: ReportReason? = null,
    val notes: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val submitted: Boolean = false,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: ReportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    fun open() = _state.update { ReportUiState(open = true) }

    fun dismiss() = _state.update { ReportUiState() }

    fun selectReason(reason: ReportReason) = _state.update {
        if (it.isSubmitting) it else it.copy(reason = reason, error = null)
    }

    fun updateNotes(notes: String) = _state.update {
        if (it.isSubmitting) it else it.copy(notes = notes, error = null)
    }

    fun submit(reporterId: String, contentType: ReportContentType, contentId: String) {
        val snapshot = state.value
        if (snapshot.isSubmitting) return
        val reason = snapshot.reason
        if (reason == null) {
            _state.update { reportFailedState(it, ReportMissingReasonError) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = repository.submitReport(
                reporterId = reporterId,
                contentType = contentType,
                contentId = contentId,
                reason = reason,
                notes = snapshot.notes,
            )
            _state.update { current ->
                result.fold(
                    onSuccess = { reportSubmittedState(current) },
                    onFailure = { error -> reportFailedState(current, error.reportMessage()) },
                )
            }
        }
    }
}

/** A report can only be sent once a reason is chosen, and never twice at the same time. */
internal fun canSubmitReport(state: ReportUiState): Boolean =
    state.reason != null && !state.isSubmitting

/** The report reached Firestore; the sheet switches to its confirmation state. */
internal fun reportSubmittedState(state: ReportUiState): ReportUiState =
    state.copy(isSubmitting = false, error = null, submitted = true)

/** The report failed; the sheet stays open with the reason and note preserved for a retry. */
internal fun reportFailedState(state: ReportUiState, message: String?): ReportUiState =
    state.copy(
        isSubmitting = false,
        error = message?.takeIf(String::isNotBlank) ?: ReportGenericError,
    )

internal const val ReportGenericError = "Your report could not be sent. Please try again."
internal const val ReportMissingReasonError = "Choose a reason for your report."
internal const val ReportOfflineError = "No internet connection. Check your network and try again."
internal const val ReportPermissionError = "Verify your email address before reporting."
internal const val ReportConfigurationError =
    "Firebase is not configured for this build. Add app/google-services.json to send reports."

/**
 * User-facing copy for a failed report.
 *
 * Deliberately never returns the underlying exception text, unlike some older feature mappers that
 * fall through to `message`: Firebase errors carry internal codes such as `PERMISSION_DENIED` and
 * `UNAVAILABLE` that mean nothing to a member.
 */
internal fun Throwable.reportMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) -> ReportConfigurationError
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) ->
            ReportOfflineError
        raw.contains("PERMISSION_DENIED", ignoreCase = true) -> ReportPermissionError
        else -> ReportGenericError
    }
}
internal const val ReportConfirmationTitle = "Report sent"
internal const val ReportConfirmationBody =
    "Thanks. Your report is private and will be reviewed by the NextBench trust and safety team."

/** Display copy for [ReportReason]. Labels stay in the app layer; only [ReportReason.raw] is stored. */
internal val ReportReason.label: String
    get() = when (this) {
        ReportReason.Inappropriate -> "Inappropriate Content"
        ReportReason.Harassment -> "Harassment or Bullying"
        ReportReason.Spam -> "Spam"
        ReportReason.HateSpeech -> "Hate Speech"
        ReportReason.Violence -> "Violence or Threats"
        ReportReason.SelfHarm -> "Self-Harm or Dangerous Activities"
        ReportReason.Misinformation -> "Misinformation"
        ReportReason.Other -> "Other"
    }
