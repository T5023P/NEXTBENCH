package com.nextbench.app.profile

import com.nextbench.data.firebase.PublicProfileStats
import com.nextbench.data.model.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBlockStateTest {

    @Test
    fun `an observed block hides follow state so a later unblock is not stale`() {
        val following = PublicProfileUiState(
            user = UserData(uid = "bob", name = "Bob"),
            resolvedId = "bob",
            stats = PublicProfileStats(isFollowing = true, isFollowedBy = true, followersCount = 4),
        )

        val blocked = following.withBlockedByViewer(true)

        assertTrue(blocked.blockedByViewer)
        assertFalse(blocked.stats.isFollowing)
        assertFalse(blocked.stats.isFollowedBy)
        assertEquals(4, blocked.stats.followersCount)
    }

    @Test
    fun `an observed unblock only clears the block flag`() {
        val state = PublicProfileUiState(
            resolvedId = "bob",
            blockedByViewer = true,
            stats = PublicProfileStats(followersCount = 3),
        )

        val unblocked = state.withBlockedByViewer(false)

        assertFalse(unblocked.blockedByViewer)
        assertEquals(state.stats, unblocked.stats)
    }

    @Test
    fun `clearing a block never restores follows on its own`() {
        // Unblocking does not recreate follows (the website contract), so the follow flags must stay
        // as the last observation left them instead of flipping back to true.
        val blocked = PublicProfileUiState(resolvedId = "bob").withBlockedByViewer(true)

        assertFalse(blocked.withBlockedByViewer(false).stats.isFollowing)
    }

    @Test
    fun `applying the same block observation twice is idempotent`() {
        val once = PublicProfileUiState(resolvedId = "bob").withBlockedByViewer(true)

        assertEquals(once, once.withBlockedByViewer(true))
    }

    @Test
    fun `a freshly loaded profile is never assumed blocked`() {
        assertFalse(PublicProfileUiState().blockedByViewer)
        assertFalse(PublicProfileUiState().isBlockBusy)
        assertFalse(PublicProfileUiState().showBlockConfirmation)
    }

    @Test
    fun `the block confirmation explains every consequence before confirming`() {
        val copy = blockConfirmationBody("Bob")

        assertTrue(copy.contains("Bob"))
        assertTrue(copy.contains("removes any follow"))
        assertTrue(copy.contains("messaging"))
        assertTrue(copy.contains("not told"))
    }
}
