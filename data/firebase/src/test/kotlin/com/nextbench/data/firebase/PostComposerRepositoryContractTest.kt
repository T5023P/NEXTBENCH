package com.nextbench.data.firebase

import com.nextbench.data.model.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostComposerRepositoryContractTest {
    private val user = UserData(
        uid = "student-1",
        name = "Maya Singh",
        school = "Next School",
        city = "Lucknow",
        profilePicture = "https://cdn/avatar.jpg",
        anonymousPersonaName = "Late Night Notes",
        verified = true,
    )

    @Test
    fun `public post payload preserves shared schema and media metadata`() {
        val payload = newPostPayload(
            user = user,
            draft = NewPostDraft(
                type = PostType.Notes.raw,
                title = "  Exam notes  ",
                content = "  A concise guide  ",
                privacy = PostPrivacy.Public.raw,
                anonymous = false,
            ),
            uploads = listOf(CloudinaryResult("https://cdn/notes.jpg", "posts/notes", 1200, 900, "jpg")),
        )

        assertEquals("Exam notes", payload["title"])
        assertEquals("A concise guide", payload["content"])
        assertEquals("notes", payload["type"])
        assertEquals("public", payload["privacy"])
        assertEquals("student-1", payload["authorId"])
        assertEquals("Maya Singh", payload["authorName"])
        assertEquals("https://cdn/avatar.jpg", payload["authorProfilePicture"])
        assertFalse(payload["isAnonymous"] as Boolean)
        assertEquals(listOf("https://cdn/notes.jpg"), payload["imageUrls"])
        assertEquals(1200, payload["imageWidth"])
        assertEquals(900, payload["imageHeight"])
        assertEquals(0, payload["upvotesCount"])
        assertEquals(0, payload["downvotesCount"])
        assertEquals(0, payload["repliesCount"])
        assertTrue(payload.containsKey("createdAt"))
        assertTrue(payload.containsKey("updatedAt"))
    }

    @Test
    fun `anonymous confession redacts profile identity and keeps persona`() {
        val payload = newPostPayload(
            user = user,
            draft = NewPostDraft(
                type = PostType.Confession.raw,
                title = "A thought",
                content = "Something honest",
                privacy = PostPrivacy.Private.raw,
                anonymous = true,
            ),
            uploads = emptyList(),
        )

        assertTrue(payload["isAnonymous"] as Boolean)
        assertEquals("Late Night Notes", payload["personaName"])
        assertEquals("Late Night Notes", payload["authorName"])
        assertNull(payload["authorProfilePicture"])
        assertEquals(emptyMap<String, Int>(), payload["reactionsCount"])
        assertEquals("private", payload["privacy"])
    }
}
