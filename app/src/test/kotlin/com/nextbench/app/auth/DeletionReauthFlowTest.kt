package com.nextbench.app.auth

import com.nextbench.app.navigation.NbRoute
import com.nextbench.data.firebase.AuthFailure
import com.nextbench.data.firebase.AuthFailureKind
import com.nextbench.data.firebase.AuthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-deletion flow has one non-obvious requirement: the server only accepts an
 * irreversible deletion from a recent session, and this app signs in with email OTP/custom tokens,
 * so "recent" means the user must sign in again. These tests fix the decisions that keep that safe
 * and honest — nothing but a confirmed success ends the flow, only a stale session signs the user
 * out, the typed confirmation is never bypassed, and the user is returned to their own request
 * afterwards instead of having to start over.
 */
class DeletionReauthFlowTest {

    private val failure = { kind: AuthFailureKind -> AuthResult.Failure(AuthFailure(kind, "message")) }

    @Test
    fun `only a confirmed success ends the deletion flow`() {
        val outcome = deleteAccountOutcome(AuthResult.Success(Unit))

        assertFalse(outcome.state.isLoading)
        assertEquals(null, outcome.state.error)
        assertFalse(outcome.state.reauthRequired)
        assertFalse(outcome.keepDeletionIntent)
        assertFalse(outcome.signOut)
    }

    @Test
    fun `a stale session keeps the intent and signs out so the user can verify again`() {
        val outcome = deleteAccountOutcome(failure(AuthFailureKind.ReauthRequired))

        assertTrue(outcome.state.reauthRequired)
        assertNotNull(outcome.state.error)
        assertTrue(outcome.keepDeletionIntent)
        assertTrue(outcome.signOut)
    }

    @Test
    fun `any other failure is reported without a false success and without signing out`() {
        for (kind in listOf(AuthFailureKind.Network, AuthFailureKind.Unknown, AuthFailureKind.Validation)) {
            val outcome = deleteAccountOutcome(failure(kind))

            assertNotNull("${kind.name} must surface its error", outcome.state.error)
            assertFalse("${kind.name} must not end the flow", outcome.state.isLoading)
            assertFalse("${kind.name} must not claim success", outcome.state == DeleteAccountUiState())
            // A transport or validation failure is retryable in place: the session stays valid.
            assertFalse(outcome.signOut)
            assertFalse(outcome.keepDeletionIntent)
        }
    }

    @Test
    fun `the typed confirmation still gates every request`() {
        assertTrue(deletionConfirmationAccepted("DELETE"))
        assertTrue(deletionConfirmationAccepted(" DELETE "))
        assertFalse(deletionConfirmationAccepted("delete"))
        assertFalse(deletionConfirmationAccepted("DELETE ACCOUNT"))
        assertFalse(deletionConfirmationAccepted(""))
    }

    @Test
    fun `one request at a time and only with the confirmation`() {
        assertTrue(deletionRequestAllowed(isLoading = false, confirmation = "DELETE"))
        // A second tap while the irreversible request is in flight must be refused.
        assertFalse(deletionRequestAllowed(isLoading = true, confirmation = "DELETE"))
        // Programmatic or partial input must not start a deletion.
        assertFalse(deletionRequestAllowed(isLoading = false, confirmation = "delete"))
        assertFalse(deletionRequestAllowed(isLoading = false, confirmation = ""))
    }

    @Test
    fun `signing in again returns to the deletion request`() {
        assertEquals(NbRoute.Profile.path, authResumeDestination(deletionPending = true))
        assertEquals(NbRoute.Feed.path, authResumeDestination(deletionPending = false))
    }
}
