package com.nextbench.data.firebase

import com.nextbench.data.model.UserData
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRepositoryTest {

    @Test
    fun `accepted review keeps the website verdict fields`() {
        val outcome = verificationReviewOutcome(
            responseCode = 200,
            responseBody = mapOf("success" to true, "verificationStatus" to "approved"),
        )

        assertEquals("approved", outcome.status)
        assertTrue(outcome.automated)
        assertNull(outcome.reason)
    }

    @Test
    fun `unrecognized success response fails closed instead of reporting pending`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(responseCode = 200, responseBody = emptyMap())
        }

        assertEquals(200, error.statusCode)
        assertTrue(error.message.contains("not confirmed"))
        assertTrue(error.message.contains("not submitted"))
        assertFalse(error.message.contains("manual", ignoreCase = true))
        assertFalse(error.message.contains("queued", ignoreCase = true))
    }

    @Test
    fun `accepted review carries the rejection reason and its legacy alias`() {
        assertEquals(
            "Blurry student ID",
            verificationReviewOutcome(
                responseCode = 200,
                responseBody = mapOf("verificationStatus" to "rejected", "rejectionReason" to "Blurry student ID"),
            ).reason,
        )
        assertEquals(
            "Blurry student ID",
            verificationReviewOutcome(
                responseCode = 200,
                responseBody = mapOf("verificationStatus" to "rejected", "reason" to "Blurry student ID"),
            ).reason,
        )
    }

    @Test
    fun `accepted review treats a missing success flag as manual`() {
        val outcome = verificationReviewOutcome(
            responseCode = 200,
            responseBody = mapOf("verificationStatus" to "flagged_manual"),
        )

        assertEquals("flagged_manual", outcome.status)
        assertFalse(outcome.automated)
    }

    @Test
    fun `expired session is reported as a session failure`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(responseCode = 401, responseBody = mapOf("error" to "Unauthorized"))
        }

        assertEquals(401, error.statusCode)
        assertTrue(error.message.contains("Sign in again"))
        assertFalse(error.message.contains("manual", ignoreCase = true))
    }

    @Test
    fun `forbidden review is reported as a permission failure`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(responseCode = 403, responseBody = mapOf("error" to "Forbidden"))
        }

        assertEquals(403, error.statusCode)
        assertTrue(error.message.contains("cannot submit verification"))
    }

    @Test
    fun `server errors are retryable and never claim a manual review queue`() {
        listOf(500, 502, 503).forEach { code ->
            val error = assertThrows(VerificationServiceException::class.java) {
                verificationReviewOutcome(responseCode = code, responseBody = emptyMap())
            }

            assertEquals(code, error.statusCode)
            assertTrue(error.message.contains("temporarily unavailable"))
            assertTrue(error.message.contains("not submitted"))
            assertFalse(error.message.contains("manual", ignoreCase = true))
            assertFalse(error.message.contains("queued", ignoreCase = true))
        }
    }

    @Test
    fun `blank server copy falls back to client retry copy`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(responseCode = 502, responseBody = mapOf("error" to "   "))
        }

        assertEquals("The verification service is temporarily unavailable. Your photos were not submitted. Please try again.", error.message)
    }

    @Test
    fun `validation rejections keep the server message`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(
                responseCode = 400,
                responseBody = mapOf("error" to "Student ID photo is unreadable."),
            )
        }

        assertEquals(400, error.statusCode)
        assertEquals("Student ID photo is unreadable.", error.message)
    }

    @Test
    fun `rate limited review keeps the endpoint quota message`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(
                responseCode = 429,
                responseBody = mapOf("error" to "Too many verification attempts. Limit is 3 per hour."),
            )
        }

        assertEquals(429, error.statusCode)
        assertEquals("Too many verification attempts. Limit is 3 per hour.", error.message)
    }

    @Test
    fun `a non success response never yields an outcome to persist`() {
        val rejectedCodes = listOf(400, 401, 403, 404, 405, 408, 429, 500, 502, 503, 504)

        rejectedCodes.forEach { code ->
            assertThrows(
                "Expected HTTP $code to raise instead of returning an outcome",
                VerificationServiceException::class.java,
            ) {
                verificationReviewOutcome(responseCode = code, responseBody = emptyMap())
            }
        }
    }

    @Test
    fun `timeout is reported as unsubmitted and retryable`() {
        val message = verificationTransportFailureMessage(SocketTimeoutException("timeout"))

        assertTrue(message.contains("timed out"))
        assertTrue(message.contains("not submitted"))
        assertFalse(message.contains("manual", ignoreCase = true))
    }

    @Test
    fun `transport failures are reported as unsubmitted`() {
        val offline = verificationTransportFailureMessage(IOException("no route to host"))
        assertTrue(offline.contains("No internet connection"))
        assertTrue(offline.contains("not submitted"))

        val unexpected = verificationTransportFailureMessage(IllegalStateException("boom"))
        assertTrue(unexpected.contains("could not reach the server"))
        assertTrue(unexpected.contains("not submitted"))
    }

    @Test
    fun `blank verification status fails closed instead of falling back to pending`() {
        assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(
                responseCode = 200,
                responseBody = mapOf("verificationStatus" to "   ", "rejectionReason" to ""),
            )
        }
    }

    @Test
    fun `unknown verification status fails closed`() {
        val error = assertThrows(VerificationServiceException::class.java) {
            verificationReviewOutcome(
                responseCode = 200,
                responseBody = mapOf("verificationStatus" to "under_review"),
            )
        }

        assertEquals(200, error.statusCode)
    }

    @Test
    fun `malformed success bodies never yield an outcome to persist`() {
        val malformedBodies = listOf(
            "",
            "   ",
            "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
            "<html>",
            "not json",
            "null",
            "[1,2,3]",
            "{\"verificationStatus\":",
        )

        malformedBodies.forEach { body ->
            assertThrows(
                "Expected a malformed 2xx body to fail closed: $body",
                VerificationServiceException::class.java,
            ) {
                verificationReviewOutcome(responseCode = 200, responseBody = body.toReviewResponse())
            }
        }
    }

    @Test
    fun `website soft fail queue reason reaches the pending state unchanged`() {
        val outcome = verificationReviewOutcome(
            responseCode = 200,
            responseBody = mapOf(
                "success" to false,
                "verificationStatus" to "pending",
                "reason" to "Automated verification check encountered an issue. Queued for manual verification.",
            ),
        )

        assertEquals("pending", outcome.status)
        assertEquals(
            "Automated verification check encountered an issue. Queued for manual verification.",
            outcome.reason,
        )
        assertFalse(outcome.automated)
    }

    @Test
    fun `blank profile name is rejected before any upload`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verificationReviewIdentity(UserData(uid = "u1", name = "   ", school = "IIT Delhi"))
        }

        assertTrue(error.message.orEmpty().contains("full name"))
    }

    @Test
    fun `blank school is rejected before any upload`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verificationReviewIdentity(UserData(uid = "u1", name = "Akshat Kumar", school = ""))
        }

        assertTrue(error.message.orEmpty().contains("school"))
    }

    @Test
    fun `valid profile identity is submitted unchanged`() {
        assertEquals(
            "Akshat Kumar" to "IIT Delhi",
            verificationReviewIdentity(UserData(uid = "u1", name = "Akshat Kumar", school = "IIT Delhi")),
        )
    }

    @Test
    fun `identity evidence is stored under the submitting user's own private prefix`() {
        assertEquals(
            "identity/u1/id-sub1.jpg",
            identityObjectPath("u1", "id", "sub1", "student_id.jpg"),
        )
        assertEquals(
            "identity/u1/selfie-sub1.jpeg",
            identityObjectPath("u1", "selfie", "sub1", "SELFIE.JPEG"),
        )
    }

    @Test
    fun `a hostile file name cannot escape the identity prefix`() {
        val path = identityObjectPath("u1", "id", "sub1", "../../other/evil.jpg")

        assertEquals("identity/u1/id-sub1.jpg", path)
        assertFalse(path.contains(".."))
        assertTrue(path.startsWith("identity/u1/"))
    }

    @Test
    fun `an unusable extension falls back to jpg`() {
        assertEquals("identity/u1/id-sub1.jpg", identityObjectPath("u1", "id", "sub1", "no-extension"))
    }

    @Test
    fun `each submission id is unique so a resubmission cannot overwrite in-flight evidence`() {
        val first = identitySubmissionId()
        val second = identitySubmissionId()

        assertFalse(first == second)
        assertTrue(first.startsWith("android_"))
        assertTrue(first.all { it.isLetterOrDigit() || it == '_' })
    }
}
