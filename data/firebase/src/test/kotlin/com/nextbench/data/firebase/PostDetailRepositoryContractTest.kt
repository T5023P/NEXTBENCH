package com.nextbench.data.firebase

import com.nextbench.data.model.Post
import com.nextbench.data.model.PostReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDetailRepositoryContractTest {
    @Test
    fun `callable replies map the complete live schema`() {
        val reply = mapOf<String, Any?>(
            "id" to "reply-1",
            "postId" to "post-1",
            "content" to "This helped",
            "authorId" to "student-1",
            "authorName" to "Maya",
            "authorSchool" to "Next School",
            "authorProfilePicture" to "https://cdn/avatar.jpg",
            "imageUrl" to "https://cdn/reply.jpg",
            "parentId" to "reply-parent",
            "upvotesCount" to 4L,
            "repliesCount" to 2L,
            "edited" to true,
            "createdAt" to 1_700_000_000_000L,
            "updatedAt" to 1_700_000_100_000L,
        ).toPostReply()

        requireNotNull(reply)
        assertEquals("reply-1", reply.id)
        assertEquals("Next School", reply.authorSchool)
        assertEquals("reply-parent", reply.parentId)
        assertEquals(4, reply.upvotesCount)
        assertEquals(2, reply.repliesCount)
        assertTrue(reply.edited)
        assertEquals(1_700_000_000_000L, reply.createdAt?.toDate()?.time)
        assertEquals(1_700_000_100_000L, reply.updatedAt?.toDate()?.time)
    }

    @Test
    fun `malformed callable replies are discarded`() {
        assertNull(mapOf<String, Any?>("postId" to "post-1").toPostReply())
        assertNull(mapOf<String, Any?>("id" to "reply-1").toPostReply())
    }

    @Test
    fun `text reply payload uses production field names and omits empty parent`() {
        val payload = draft(parentId = null).toWriteData("server-time")

        assertEquals("post-1", payload["postId"])
        assertEquals("Next School", payload["authorSchool"])
        assertEquals(0, payload["upvotesCount"])
        assertEquals(0, payload["repliesCount"])
        assertEquals(false, payload["edited"])
        assertEquals("server-time", payload["createdAt"])
        assertEquals("server-time", payload["updatedAt"])
        assertFalse(payload.containsKey("parentId"))
        assertFalse(payload.containsKey("parentReplyId"))
    }

    @Test
    fun `nested reply payload includes parent id`() {
        val payload = draft(parentId = "reply-parent").toWriteData("server-time")

        assertEquals("reply-parent", payload["parentId"])
    }

    @Test
    fun `notifications avoid self sends and duplicate recipients`() {
        val reply = PostReply(
            id = "reply-2",
            postId = "post-1",
            authorId = "student-2",
            authorName = "Maya",
        )
        val requests = replyNotificationPayloads(
            post = Post(id = "post-1", authorId = "student-1"),
            parent = PostReply(id = "reply-1", postId = "post-1", authorId = "student-1"),
            reply = reply,
        )

        assertEquals(1, requests.size)
        assertEquals("student-1", requests.single()["userId"])
        assertEquals("/post/post-1", requests.single()["link"])
    }

    private fun draft(parentId: String?) = NewPostReply(
        id = "reply-1",
        postId = "post-1",
        content = "This helped",
        authorId = "student-1",
        authorName = "Maya",
        authorSchool = "Next School",
        authorProfilePicture = null,
        parentId = parentId,
    )
}
