package com.nextbench.app.feed

import com.nextbench.data.model.StoryPrivacy
import com.nextbench.data.model.Story
import com.nextbench.data.model.StoryTrayEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryStateTest {
    @Test
    fun `fresh story state is ready for a public story`() {
        val state = StoryUiState()

        assertEquals(StoryPrivacy.Public, state.privacy)
        assertFalse(state.isPreparingMedia)
        assertFalse(state.isPublishing)
        assertTrue(state.tray.isEmpty())
    }

    @Test
    fun `story cursor advances through authors and stops after the final story`() {
        val tray = listOf(
            StoryTrayEntry(authorId = "one", stories = listOf(story("1"), story("2"))),
            StoryTrayEntry(authorId = "two", stories = listOf(story("3"))),
        )

        assertEquals(StoryCursor(0, 1), advanceStoryCursor(StoryCursor(0, 0), tray))
        assertEquals(StoryCursor(1, 0), advanceStoryCursor(StoryCursor(0, 1), tray))
        assertNull(advanceStoryCursor(StoryCursor(1, 0), tray))
    }

    @Test
    fun `story cursor rewinds to the previous authors last story`() {
        val tray = listOf(
            StoryTrayEntry(authorId = "one", stories = listOf(story("1"), story("2"))),
            StoryTrayEntry(authorId = "two", stories = listOf(story("3"))),
        )

        assertEquals(StoryCursor(0, 1), rewindStoryCursor(StoryCursor(1, 0), tray))
        assertEquals(StoryCursor(0, 0), rewindStoryCursor(StoryCursor(0, 0), tray))
    }

    private fun story(id: String) = Story(id = id, mediaUrl = "https://cdn/$id")
}
