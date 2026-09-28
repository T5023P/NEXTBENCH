package com.nextbench.app.post

import com.google.firebase.Timestamp
import com.nextbench.data.model.PostReply
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDetailViewModelTest {
    @Test
    fun `viewer requires a nonblank uid`() {
        assertFalse(PostDetailViewer().signedIn)
        assertFalse(PostDetailViewer(uid = "").signedIn)
        assertTrue(PostDetailViewer(uid = "student-1").signedIn)
    }

    @Test
    fun `flattened conversation keeps chronological nested context`() {
        val replies = listOf(
            reply("child", parentId = "root", author = "Dev", time = 2),
            reply("root", author = "Maya", time = 1),
            reply("grandchild", parentId = "child", author = "Sam", time = 3),
        )

        val rows = flattenReplies(replies)

        assertEquals(listOf("root", "child", "grandchild"), rows.map { it.reply.id })
        assertEquals(listOf(0, 1, 2), rows.map(ReplyRow::depth))
        assertEquals("Maya", rows[1].parentAuthorName)
        assertEquals("Dev", rows[2].parentAuthorName)
    }

    @Test
    fun `orphaned and cyclic replies remain visible`() {
        val replies = listOf(
            reply("orphan", parentId = "missing", time = 1),
            reply("cycle-a", parentId = "cycle-b", time = 2),
            reply("cycle-b", parentId = "cycle-a", time = 3),
        )

        val rows = flattenReplies(replies)

        assertEquals(setOf("orphan", "cycle-a", "cycle-b"), rows.map { it.reply.id }.toSet())
    }

    @Test
    fun `appending nested reply increments only its direct parent`() {
        val current = listOf(
            reply("root", repliesCount = 2),
            reply("other", repliesCount = 4),
        )
        val created = reply("child", parentId = "root")

        val updated = appendCreatedReply(current, created, parentRepliesCount = 8)

        assertEquals(8, updated.first { it.id == "root" }.repliesCount)
        assertEquals(4, updated.first { it.id == "other" }.repliesCount)
        assertEquals("child", updated.last().id)
    }

    @Test
    fun `detail errors expose useful configuration and validation messages`() {
        assertTrue(
            IllegalStateException("Firebase is not configured").postDetailMessage()
                .contains("google-services.json"),
        )
        assertEquals(
            "Replies can be up to 1000 characters.",
            IllegalArgumentException("Replies can be up to 1000 characters.").postDetailMessage(),
        )
    }

    private fun reply(
        id: String,
        parentId: String? = null,
        author: String = "Student",
        repliesCount: Int = 0,
        time: Long = 0,
    ) = PostReply(
        id = id,
        postId = "post-1",
        parentId = parentId,
        authorName = author,
        repliesCount = repliesCount,
        createdAt = Timestamp(Date(time)),
    )
}
