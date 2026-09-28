package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteRepositoryContractTest {
    @Test
    fun `invite content normalizes referral fields`() {
        val content = mapOf<String, Any?>(
            "referralCode" to "  NB123  ",
            "referralCount" to 4L,
            "referredBy" to "referrer-1",
        ).toInviteContent()

        assertEquals("NB123", content.referralCode)
        assertEquals(4, content.referralCount)
        assertEquals("referrer-1", content.referredBy)
    }

    @Test
    fun `invite content uses safe defaults for malformed fields`() {
        val content = mapOf<String, Any?>(
            "referralCode" to " ",
            "referralCount" to -7,
            "referredBy" to " ",
        ).toInviteContent()

        assertNull(content.referralCode)
        assertEquals(0, content.referralCount)
        assertNull(content.referredBy)
    }
}
