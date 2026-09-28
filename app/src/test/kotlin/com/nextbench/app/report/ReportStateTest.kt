package com.nextbench.app.report

import com.nextbench.data.model.ReportReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportStateTest {

    @Test
    fun `a report cannot be sent until a reason is chosen`() {
        val empty = ReportUiState(open = true)

        assertFalse(canSubmitReport(empty))
        assertTrue(canSubmitReport(empty.copy(reason = ReportReason.Spam)))
        assertFalse(canSubmitReport(empty.copy(reason = ReportReason.Spam, isSubmitting = true)))
    }

    @Test
    fun `notes alone never make a report sendable`() {
        assertFalse(canSubmitReport(ReportUiState(open = true, notes = "they were rude")))
    }

    @Test
    fun `a sent report switches to the confirmation state and drops any error`() {
        val submitted = reportSubmittedState(
            ReportUiState(
                open = true,
                reason = ReportReason.Spam,
                isSubmitting = true,
                error = "stale failure",
            ),
        )

        assertTrue(submitted.submitted)
        assertFalse(submitted.isSubmitting)
        assertNull(submitted.error)
        assertEquals(ReportReason.Spam, submitted.reason)
    }

    @Test
    fun `a failed report keeps the reason and note so it can be retried`() {
        val failed = reportFailedState(
            ReportUiState(
                open = true,
                reason = ReportReason.Harassment,
                notes = "keeps messaging me",
                isSubmitting = true,
            ),
            "No internet connection",
        )

        assertFalse(failed.submitted)
        assertFalse(failed.isSubmitting)
        assertTrue(failed.open)
        assertEquals("No internet connection", failed.error)
        assertEquals(ReportReason.Harassment, failed.reason)
        assertEquals("keeps messaging me", failed.notes)
        assertTrue(canSubmitReport(failed))
    }

    @Test
    fun `a blank failure message falls back to the generic copy`() {
        listOf(null, "", "   ").forEach { message ->
            assertEquals(
                ReportGenericError,
                reportFailedState(ReportUiState(open = true), message).error,
            )
        }
    }

    @Test
    fun `report failures map to actionable copy by cause`() {
        assertEquals(
            ReportConfigurationError,
            IllegalStateException("Firebase is not configured for this build. Add app/google-services.json and rebuild.")
                .reportMessage(),
        )
        assertEquals(
            ReportOfflineError,
            RuntimeException("UNAVAILABLE: Failed to get document because the client is offline.").reportMessage(),
        )
        assertEquals(ReportOfflineError, RuntimeException("network error").reportMessage())
        assertEquals(
            ReportPermissionError,
            RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions.").reportMessage(),
        )
        assertEquals(ReportGenericError, RuntimeException("INTERNAL: something exploded").reportMessage())
    }

    @Test
    fun `report failures never leak raw backend text`() {
        val raw = "PERMISSION_DENIED: Missing or insufficient permissions."
        val message = RuntimeException(raw).reportMessage()

        assertFalse(message.contains("PERMISSION_DENIED"))
        assertFalse(message.contains(raw))
        assertFalse(message.contains("UNAVAILABLE"))
    }

    @Test
    fun `a report failure without a message uses the generic copy`() {
        assertEquals(ReportGenericError, RuntimeException().reportMessage())
        assertEquals(ReportGenericError, RuntimeException("   ").reportMessage())
    }

    @Test
    fun `every report reason has a distinct non blank label`() {
        val labels = ReportReason.entries.map { it.label }

        assertTrue(labels.all(String::isNotBlank))
        assertEquals(labels.size, labels.distinct().size)
        assertEquals(8, labels.size)
        assertEquals("Hate Speech", ReportReason.HateSpeech.label)
        assertEquals("Self-Harm or Dangerous Activities", ReportReason.SelfHarm.label)
    }
}
