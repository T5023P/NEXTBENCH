package com.nextbench.app.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.model.ReportReason

/**
 * Shared report sheet. The caller owns the target (content type + id) and passes them on submit, so
 * the same sheet can report a profile, listing, post, or message without changes here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(
    title: String,
    state: ReportUiState,
    onSelectReason: (ReportReason) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    NbBottomSheet(onDismiss = { if (!state.isSubmitting) onDismiss() }) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space12),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            if (state.submitted) {
                ReportConfirmation(onDone = onDismiss)
                return@Column
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NbTheme.colors.ink,
            )
            Text(
                "Your report is private and goes to the NextBench trust and safety team.",
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
            ReportReason.entries.forEach { reason ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.reason == reason,
                            enabled = !state.isSubmitting,
                            role = Role.RadioButton,
                            onClick = { onSelectReason(reason) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.reason == reason,
                        onClick = null,
                        enabled = !state.isSubmitting,
                    )
                    Text(
                        reason.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NbTheme.colors.ink,
                        modifier = Modifier.padding(start = NbDimens.space8),
                    )
                }
            }
            NbTextField(
                value = state.notes,
                onValueChange = onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = "More details (optional)",
                enabled = !state.isSubmitting,
                singleLine = false,
                maxLines = 4,
            )
            state.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            NbButton(
                text = "Submit report",
                onClick = onSubmit,
                enabled = canSubmitReport(state),
                loading = state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )
            NbButton(
                text = "Cancel",
                onClick = onDismiss,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                variant = NbButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun ReportConfirmation(onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Text(
            ReportConfirmationTitle,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = NbTheme.colors.ink,
        )
        Text(ReportConfirmationBody, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
        NbButton("Done", onDone, modifier = Modifier.fillMaxWidth())
    }
}
