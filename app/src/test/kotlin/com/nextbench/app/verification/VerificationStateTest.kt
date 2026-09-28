package com.nextbench.app.verification

import com.nextbench.data.firebase.VerificationStage
import com.nextbench.data.model.UserData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VerificationStateTest {

    @Test
    fun newStudentStartsCaptureUntilPhotosWereSubmitted() {
        assertEquals(
            AccountVerificationState.Capture,
            accountVerificationState(
                UserData(accountType = "student", verificationStatus = "pending"),
                submitted = false,
            ),
        )
        assertEquals(
            AccountVerificationState.Pending,
            accountVerificationState(
                UserData(accountType = "student", verificationStatus = "pending"),
                submitted = true,
            ),
        )
    }

    @Test
    fun submittedIsReadFromThePrivatePointerNotTheProfileDocument() {
        // Identity evidence no longer lives on `users/{uid}` (readable by every signed-in
        // user), so the resume decision must not depend on any URL field there. A pending
        // account with no pointer stays at capture; a pointer alone is enough to resume.
        val profileWithoutEvidence = UserData(accountType = "student", verificationStatus = "pending")

        assertEquals(AccountVerificationState.Capture, accountVerificationState(profileWithoutEvidence, false))
        assertEquals(AccountVerificationState.Pending, accountVerificationState(profileWithoutEvidence, true))
    }

    @Test
    fun manualRejectedAndApprovedStatesResumeCorrectly() {
        assertEquals(
            AccountVerificationState.ManualReview,
            accountVerificationState(UserData(verificationStatus = "flagged_manual"), submitted = true),
        )
        assertEquals(
            AccountVerificationState.Rejected,
            accountVerificationState(UserData(verificationStatus = "rejected"), submitted = false),
        )
        assertEquals(
            AccountVerificationState.Approved,
            accountVerificationState(UserData(verified = true), submitted = false),
        )
    }

    @Test
    fun organizationDocumentsAlwaysResumeAtAdminReview() {
        assertEquals(
            AccountVerificationState.OrganizationReview,
            accountVerificationState(
                UserData(accountType = "organization", verificationStatus = "pending"),
                submitted = false,
            ),
        )
    }

    @Test
    fun failedReviewCanBeRetriedWithoutLosingPreparedPhotos() {
        val idCard = File("student_id.jpg")
        val selfie = File("selfie.jpg")
        val failed = VerificationUiState(
            step = VerificationStep.Status,
            idCard = idCard,
            selfie = selfie,
            error = "The verification service is temporarily unavailable.",
            stage = VerificationStage.Reviewing,
            isSubmitting = false,
        )

        val retried = verificationRetryState(failed)

        assertEquals(VerificationStep.Selfie, retried.step)
        assertNull(retried.error)
        assertNull(retried.stage)
        assertSame(idCard, retried.idCard)
        assertSame(selfie, retried.selfie)
        assertNull(retried.outcome)
    }

    @Test
    fun retryIsIgnoredWhileASubmissionIsInFlight() {
        val submitting = VerificationUiState(
            step = VerificationStep.Status,
            stage = VerificationStage.Uploading,
            isSubmitting = true,
        )

        assertSame(submitting, verificationRetryState(submitting))
    }
}
