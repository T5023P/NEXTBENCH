package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.nextbench.data.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class StoryRepositoryContractTest {
    @Test
    fun `website story payload maps timestamps media and privacy`() {
        val story = mapOf<String, Any?>(
            "authorId" to "user-1",
            "authorUsername" to "maya",
            "mediaType" to "video",
            "mediaUrl" to "https://cdn/story.mp4",
            "mediaPath" to "stories/user-1/story-1/media.mp4",
            "durationMs" to 8_000L,
            "layers" to listOf(mapOf("type" to "text", "font" to null)),
            "privacy" to "closeFriends",
            "createdAt" to Timestamp(Date(1_700_000_000_000L)),
            "expiresAt" to Timestamp(Date(1_700_086_400_000L)),
        ).toStory("story-1")

        requireNotNull(story)
        assertEquals("video", story.mediaType)
        assertEquals("closeFriends", story.privacy)
        assertEquals(8_000L, story.durationMs)
        assertEquals(1_700_000_000_000L, story.createdAt)
        assertEquals(mapOf("type" to "text"), story.layers.single())
    }

    @Test
    fun `malformed stories are dropped`() {
        assertNull(mapOf<String, Any?>("authorId" to "user-1").toStory("story-1"))
    }

    @Test
    fun `tray keeps self first then unseen and chronological playback`() {
        val stories = listOf(
            Story(id = "seen", authorId = "seen-user", mediaUrl = "u", createdAt = 30L, expiresAt = Long.MAX_VALUE),
            Story(id = "mine-new", authorId = "me", mediaUrl = "u", createdAt = 20L, expiresAt = Long.MAX_VALUE),
            Story(id = "mine-old", authorId = "me", mediaUrl = "u", createdAt = 10L, expiresAt = Long.MAX_VALUE),
            Story(id = "unseen", authorId = "friend", mediaUrl = "u", createdAt = 25L, expiresAt = Long.MAX_VALUE),
        )
        val tray = assembleStoryTray(
            currentUid = "me",
            stories = stories,
            seen = mapOf("seen-user" to StorySeenEntry(lastSeenAt = 30L, lastSeenStoryId = "seen")),
        )

        assertEquals(listOf("me", "friend", "seen-user"), tray.map { it.authorId })
        assertEquals(listOf("mine-old", "mine-new"), tray.first().stories.map { it.id })
        assertFalse(tray[1].allSeen)
        assertTrue(tray[2].allSeen)
    }
}
