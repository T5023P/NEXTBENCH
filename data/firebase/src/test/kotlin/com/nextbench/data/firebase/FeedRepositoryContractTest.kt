package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRepositoryContractTest {
    @Test
    fun `discovery payload keeps mode and populated cursor fields`() {
        val payload = discoveryPayload(
            FeedMode.Following,
            FeedCursor(postCreatedAt = 123L, cursorIndex = 20),
        )

        assertEquals("following", payload["mode"])
        assertEquals(123L, payload["postCreatedAt"])
        assertEquals(20, payload["cursorIndex"])
        assertFalse(payload.containsKey("productCreatedAt"))
    }

    @Test
    fun `callable page maps serialized post media poll and cursor`() {
        val page = mapOf<String, Any?>(
            "posts" to listOf(
                mapOf(
                    "id" to "post-1",
                    "title" to "Exam notes",
                    "content" to "A concise guide",
                    "authorName" to "Maya",
                    "school" to "Next School",
                    "createdAt" to 1_700_000_000_000L,
                    "imageUrls" to listOf("https://cdn/one.jpg"),
                    "imageWidth" to 1200L,
                    "imageHeight" to 900L,
                    "imagesDetailed" to listOf(mapOf("url" to "https://cdn/one.jpg", "w" to 1200, "h" to 900)),
                    "pdfUrl" to "https://cdn/notes.pdf",
                    "pdfPages" to 4L,
                    "videoUrl" to "https://cdn/clip.mp4",
                    "upvotesCount" to 9L,
                    "poll" to mapOf(
                        "choices" to listOf("Yes", "No"),
                        "votes" to mapOf("0" to 3L, "1" to 1L),
                        "expiresAt" to 1_700_100_000_000L,
                    ),
                ),
            ),
            "products" to listOf(
                mapOf(
                    "id" to "book-1",
                    "title" to "Operating Systems",
                    "price" to 450L,
                    "category" to "Education",
                    "status" to "available",
                ),
            ),
            "order" to listOf(
                mapOf("id" to "post-1", "type" to "post"),
                mapOf("id" to "book-1", "type" to "product"),
                mapOf("id" to "ignored", "type" to "unknown"),
            ),
            "nextCursor" to mapOf("postCreatedAt" to 1_699_000_000_000L),
            "hasMorePosts" to true,
        ).toFeedPage()

        val post = page.posts.single()
        assertEquals("post-1", post.id)
        assertEquals(1_700_000_000_000L, post.createdAt?.toDate()?.time)
        assertEquals(1200, post.imageWidth)
        assertEquals(900, post.imagesDetailed.single().h)
        assertEquals(4, post.pdfPages)
        assertEquals("https://cdn/clip.mp4", post.videoUrl)
        assertEquals(mapOf("0" to 3, "1" to 1), post.poll?.votes)
        assertEquals("book-1", page.products.single().id)
        assertEquals(listOf("post-1", "book-1"), page.order.map(FeedOrderEntry::id))
        assertEquals(1_699_000_000_000L, page.nextCursor.postCreatedAt)
        assertTrue(page.hasMorePosts)
    }

    @Test
    fun `malformed posts are dropped and optional values stay null`() {
        val page = mapOf<String, Any?>(
            "posts" to listOf(mapOf("title" to "Missing id"), mapOf("id" to "valid")),
            "nextCursor" to emptyMap<String, Any?>(),
        ).toFeedPage()

        assertEquals(listOf("valid"), page.posts.map { it.id })
        assertTrue(page.products.isEmpty())
        assertTrue(page.order.isEmpty())
        assertNull(page.posts.single().createdAt)
        assertFalse(page.hasMorePosts)
    }

    @Test
    fun `product pagination keeps the mixed feed open when posts are exhausted`() {
        val page = mapOf<String, Any?>(
            "posts" to emptyList<Any>(),
            "products" to listOf(mapOf("id" to "book-1")),
            "hasMorePosts" to false,
            "hasMoreProducts" to true,
        ).toFeedPage()

        assertTrue(page.hasMorePosts)
    }

    @Test
    fun `interaction document maps preserve legacy generated ids`() {
        val interactions = FeedInteractions(
            upvoteDocumentIds = mapOf("post-1" to "legacy-vote-id"),
            saveDocumentIds = mapOf("post-2" to "legacy-save-id"),
        )

        assertEquals(setOf("post-1"), interactions.upvotedPostIds)
        assertEquals(setOf("post-2"), interactions.savedPostIds)
        assertTrue(interactions.downvotedPostIds.isEmpty())
    }
}
