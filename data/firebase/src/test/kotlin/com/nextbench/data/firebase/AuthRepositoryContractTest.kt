package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryContractTest {

    @Test
    fun loginPayloadMatchesCallableContract() {
        val payload = otpVerificationPayload(" ADA@Example.COM ", "12 34 56", null)

        assertEquals("ada@example.com", payload["email"])
        assertEquals("123456", payload["otp"])
        assertFalse(payload.containsKey("isSignup"))
    }

    @Test
    fun signupPayloadMatchesCallableContract() {
        val payload = otpVerificationPayload(
            email = "ada@example.com",
            otp = "123456",
            signupData = StudentSignupData(
                name = " Ada Lovelace ",
                school = " Loreto College ",
                city = " ",
                referralCode = " NB123 ",
            ),
        )

        assertTrue(payload["isSignup"] as Boolean)
        val signup = payload["signupData"] as Map<*, *>
        assertEquals("Ada Lovelace", signup["name"])
        assertEquals("Loreto College", signup["school"])
        assertEquals("Lucknow", signup["city"])
        assertEquals("NB123", signup["referralCode"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun otpMustContainExactlySixDigits() {
        otpVerificationPayload("ada@example.com", "12345", null)
    }

    @Test
    fun deletionRequestCarriesOnlyTheTypedConfirmation() {
        // Everything else about the deletion is server-owned; the client must not be able to
        // influence the cascade, and the server refuses a stale session regardless.
        val payload = accountDeletionRequestPayload("  DELETE ")

        assertEquals(setOf("confirmation"), payload.keys)
        assertEquals("DELETE", payload["confirmation"])
    }

    @Test
    fun deletionRequestConfirmationIsNotSilentlyRepaired() {
        // Trimming tolerates a pasted newline; anything else must still reach the server as
        // typed so the deliberate confirmation cannot be satisfied by accident.
        assertEquals("delete", accountDeletionRequestPayload("delete")["confirmation"])
        assertEquals("", accountDeletionRequestPayload("")["confirmation"])
    }

    @Test
    fun verificationPointerNeverAssertsAServerOwnedVerdict() {
        val payload = verificationPointerPayload(
            idCardPath = "identity/uid-1/id-sub1.jpg",
            selfiePath = "identity/uid-1/selfie-sub1.jpg",
            submissionId = "sub1",
        )

        assertEquals(setOf("idCardPath", "selfiePath", "submissionId", "updatedAt"), payload.keys)
        assertFalse(payload.containsKey("verificationStatus"))
        assertFalse(payload.containsKey("verified"))
        assertFalse(payload.containsKey("verificationRejectionReason"))
        assertEquals("identity/uid-1/id-sub1.jpg", payload["idCardPath"])
        assertEquals("identity/uid-1/selfie-sub1.jpg", payload["selfiePath"])
    }

    @Test
    fun verificationPointerStoresPrivatePathsAndNoPublicUrl() {
        // Identity evidence must never be reachable through a durable public link, and the
        // pointer must not carry one: the public profile document is readable by every
        // signed-in user, so a URL there would be the exposure this contract removes.
        val payload = verificationPointerPayload(
            idCardPath = "identity/uid-1/id-sub1.jpg",
            selfiePath = "identity/uid-1/selfie-sub1.jpg",
            submissionId = "sub1",
        )

        assertFalse(payload.containsKey("idCardUrl"))
        assertFalse(payload.containsKey("selfieUrl"))
        assertTrue((payload["idCardPath"] as String).startsWith("identity/uid-1/"))
        assertFalse((payload["idCardPath"] as String).contains("://"))
    }
}
