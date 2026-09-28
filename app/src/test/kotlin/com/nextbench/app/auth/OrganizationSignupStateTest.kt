package com.nextbench.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizationSignupStateTest {
    @Test
    fun eachStepRequiresItsOwnedFields() {
        assertEquals(
            "Select your organization type.",
            organizationStepError(OrganizationSignupUiState()),
        )
        assertEquals(
            "Enter the organization name.",
            organizationStepError(
                OrganizationSignupUiState(
                    step = OrganizationSignupStep.Details,
                    type = "school",
                ),
            ),
        )
        assertEquals(
            "Accept the Terms and Privacy Policy to continue.",
            organizationStepError(
                OrganizationSignupUiState(
                    step = OrganizationSignupStep.Review,
                    type = "school",
                    name = "NextBench Academy",
                    city = "Lucknow",
                ),
            ),
        )
    }

    @Test
    fun validDetailsStepCanContinue() {
        assertNull(
            organizationStepError(
                OrganizationSignupUiState(
                    step = OrganizationSignupStep.Details,
                    type = "ngo",
                    name = "Campus Readers Club",
                    city = "Lucknow",
                ),
            ),
        )
    }
}
