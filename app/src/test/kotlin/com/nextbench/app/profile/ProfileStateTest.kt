package com.nextbench.app.profile

import com.google.firebase.Timestamp
import com.nextbench.data.firebase.ProfileRepository
import com.nextbench.data.model.UserData
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStateTest {
    @Test
    fun `profile errors remain actionable`() {
        assertTrue(IllegalStateException("Firebase is not configured").profileMessage().contains("google-services.json"))
        assertTrue(IllegalStateException("network unavailable").profileMessage().contains("internet"))
        assertTrue(IllegalStateException("Your session expired").profileMessage().contains("session expired"))
    }

    @Test
    fun `profile tab defaults to listings and switches explicitly`() {
        val state = ProfileUiState()

        assertEquals(ProfileTab.Listings, state.tab)
        assertEquals(ProfileTab.Posts, ProfileTab.entries.first { it != state.tab })
    }

    @Test
    fun `profile editor only saves a validated settled draft`() {
        assertTrue(
            ProfileEditorState(
                open = true,
                name = "Maya",
                username = "maya.notes",
                usernameAvailable = true,
            ).canSave,
        )
        assertTrue(
            !ProfileEditorState(
                open = true,
                name = "Maya",
                username = "maya.notes",
                usernameAvailable = true,
                usernameError = "Username is already taken.",
            ).canSave,
        )
        assertTrue(
            !ProfileEditorState(
                open = true,
                name = "Maya",
                username = "maya.notes",
                isCheckingUsername = true,
            ).canSave,
        )
        assertTrue(
            ProfileEditorState(
                open = true,
                name = "Maya",
                username = "maya.notes",
                usernameAvailable = true,
                error = "No internet connection.",
            ).canSave,
        )
    }

    @Test
    fun `username cooldown gives a useful rounded day count`() {
        val now = 2_000_000_000_000L
        val user = UserData(
            username = "maya",
            lastUsernameChange = Timestamp(Date(now - ProfileRepository.UsernameCooldownMillis + 1L)),
        )

        assertEquals("You can change your username again in 1 day.", usernameCooldownMessage(user, now))
        assertEquals(null, usernameCooldownMessage(user, now + 1L))
    }
}
