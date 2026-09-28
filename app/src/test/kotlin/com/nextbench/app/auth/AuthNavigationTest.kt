package com.nextbench.app.auth

import androidx.credentials.exceptions.NoCredentialException
import com.nextbench.app.navigation.NbRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthNavigationTest {
    @Test
    fun `closing auth preserves a public caller`() {
        assertNull(authExitDestination(NbRoute.PostDetail.path))
    }

    @Test
    fun `closing auth discards a protected caller`() {
        assertEquals(NbRoute.Feed.path, authExitDestination(NbRoute.Profile.path))
        assertEquals(NbRoute.Feed.path, authExitDestination(NbRoute.Messages.path))
    }

    @Test
    fun `closing root auth falls back to feed`() {
        assertEquals(NbRoute.Feed.path, authExitDestination(null))
    }

    @Test
    fun `credential errors retain a useful fallback message`() {
        assertEquals(
            "Google sign-in could not start. Please try again.",
            IllegalStateException().authMessage(),
        )
        assertEquals(
            "No Google account is available on this device. Add one in Android Settings and try again.",
            NoCredentialException().authMessage(),
        )
    }
}
