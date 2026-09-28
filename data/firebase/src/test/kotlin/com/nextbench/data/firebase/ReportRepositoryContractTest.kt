package com.nextbench.data.firebase

import com.google.firebase.firestore.FieldValue
import com.nextbench.data.model.ReportContentType
import com.nextbench.data.model.ReportReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRepositoryContractTest {

    @Test
    fun `report payload matches the website reports document`() {
        val payload = reportPayload(
            reporterId = "user-1",
            contentType = ReportContentType.User,
            contentId = "user-2",
            reason = ReportReason.Harassment,
            notes = "They keep messaging me",
        )

        assertEquals(
            setOf("reporterId", "contentType", "contentId", "reason", "notes", "status", "createdAt"),
            payload.keys,
        )
        assertEquals("user-1", payload["reporterId"])
        assertEquals("user", payload["contentType"])
        assertEquals("user-2", payload["contentId"])
        assertEquals("harassment", payload["reason"])
        assertEquals("They keep messaging me", payload["notes"])
    }

    @Test
    fun `report status is pinned to the only value the rules admit`() {
        val payload = reportPayload("user-1", ReportContentType.Post, "post-1", ReportReason.Spam, null)

        assertEquals("pending", payload["status"])
        assertEquals(ReportPendingStatus, payload["status"])
    }

    @Test
    fun `blank notes are stored as null and never as an empty string`() {
        listOf(null, "", "   ", "\n\t").forEach { notes ->
            val payload = reportPayload("user-1", ReportContentType.Product, "product-1", ReportReason.Other, notes)

            assertNull("Expected blank notes <$notes> to be stored as null", payload["notes"])
        }
    }

    @Test
    fun `notes and content id are trimmed`() {
        val payload = reportPayload(
            reporterId = "user-1",
            contentType = ReportContentType.Message,
            contentId = "  message-9  ",
            reason = ReportReason.Violence,
            notes = "  Threatening language  ",
        )

        assertEquals("message-9", payload["contentId"])
        assertEquals("Threatening language", payload["notes"])
    }

    @Test
    fun `createdAt stays a server owned sentinel`() {
        val payload = reportPayload("user-1", ReportContentType.User, "user-2", ReportReason.Spam, null)

        assertTrue(payload["createdAt"] is FieldValue)
    }

    @Test
    fun `every report reason keeps the website raw value`() {
        assertEquals(
            listOf(
                "inappropriate",
                "harassment",
                "spam",
                "hate_speech",
                "violence",
                "self_harm",
                "misinformation",
                "other",
            ),
            ReportReason.entries.map(ReportReason::raw),
        )
        ReportReason.entries.forEach { reason -> assertEquals(reason, ReportReason.from(reason.raw)) }
        assertNull(ReportReason.from("not_a_reason"))
        assertNull(ReportReason.from(null))
    }

    @Test
    fun `every report content type keeps the website raw value`() {
        assertEquals(
            listOf("post", "product", "user", "message"),
            ReportContentType.entries.map(ReportContentType::raw),
        )
        ReportContentType.entries.forEach { type -> assertEquals(type, ReportContentType.from(type.raw)) }
        // The website also writes support tickets into `reports`; this client deliberately does not.
        assertNull(ReportContentType.from("support_ticket"))
    }

    @Test
    fun `an email unverified reporter is rejected before any write is attempted`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireReportable(
                reporterId = "user-1",
                contentType = ReportContentType.User,
                contentId = "user-2",
                reporterVerified = false,
            )
        }

        assertTrue(error.message.orEmpty().contains("email"))
    }

    @Test
    fun `a member cannot report their own account`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireReportable("user-1", ReportContentType.User, "user-1", reporterVerified = true)
        }

        assertTrue(error.message.orEmpty().contains("your own account"))
    }

    @Test
    fun `the self report guard only applies to member reports`() {
        // A post, listing, or message id may legitimately equal the reporter's own uid.
        assertEquals("user-1", requireReportable("user-1", ReportContentType.Post, "user-1", true))
        assertEquals("user-1", requireReportable("user-1", ReportContentType.Product, "user-1", true))
        assertEquals("user-1", requireReportable("user-1", ReportContentType.Message, "user-1", true))
    }

    @Test
    fun `the self report guard ignores surrounding whitespace`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireReportable("user-1", ReportContentType.User, "  user-1  ", reporterVerified = true)
        }
    }

    @Test
    fun `a blank target is rejected and a valid one is normalised`() {
        listOf("", "   ", "\n\t").forEach { contentId ->
            assertThrows("Expected blank content id <$contentId> to be rejected", IllegalArgumentException::class.java) {
                requireReportable("user-1", ReportContentType.User, contentId, reporterVerified = true)
            }
        }

        assertEquals("user-2", requireReportable("user-1", ReportContentType.User, "  user-2  ", true))
    }

    @Test
    fun `a missing reporter is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireReportable("   ", ReportContentType.User, "user-2", reporterVerified = true)
        }
    }
}
