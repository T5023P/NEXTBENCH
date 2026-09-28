package com.nextbench.app.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteStateTest {
    @Test
    fun `invite codes are normalized to eight uppercase alphanumeric characters`() {
        assertEquals("NB12AB34", normalizeInviteCode(" nb-12 ab34-extra "))
    }

    @Test
    fun `redemption is disabled after an invite is applied`() {
        val state = InviteUiState(redemptionCode = "NB123456", referredBy = "referrer")

        assertFalse(state.canRedeem)
        assertTrue(state.hasUsedReferral)
    }

    @Test
    fun `invite errors explain backend constraints`() {
        assertTrue(IllegalStateException("Referral codes can only be applied to new accounts.").inviteMessage().contains("first 24 hours"))
        assertTrue(IllegalStateException("Invalid referral code.").inviteMessage().contains("not valid"))
        assertTrue(IllegalStateException("network unavailable").inviteMessage().contains("internet"))
    }

    @Test
    fun `invite links preserve the web referral contract`() {
        assertEquals("https://www.nextbench.in/?ref=NB123456", inviteLinkForCode("nb123456"))
    }
}
