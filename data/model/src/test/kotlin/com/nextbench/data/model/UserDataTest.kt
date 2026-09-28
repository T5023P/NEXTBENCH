package com.nextbench.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataTest {

    @Test
    fun defaultsRemainFirestoreSafe() {
        val user = UserData()

        assertEquals(AccountType.Student.raw, user.accountType)
        assertEquals(VerificationStatus.Pending.raw, user.verificationStatus)
        assertEquals(0.0, user.reputation, 0.0)
        assertEquals(0, user.referralCount)
        assertFalse(user.online)
        assertNull(user.chatPrivacy)
        assertNull(user.orgDocumentUrl)
    }

    @Test
    fun chatPrivacyMatchesWebsiteMapShape() {
        val privacy = ChatPrivacy(followersOnly = true)

        assertTrue(privacy.followersOnly)
    }
}
