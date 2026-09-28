package com.nextbench.app.feed

import com.nextbench.data.firebase.PostVote
import com.nextbench.data.firebase.FeedOrderEntry
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedViewModelTest {
    @Test
    fun `display mode starts editorial and can be switched to list`() {
        var state = FeedUiState()
        assertEquals(FeedDisplayMode.Editorial, state.displayMode)

        state = state.copy(displayMode = FeedDisplayMode.List)

        assertEquals(FeedDisplayMode.List, state.displayMode)
    }

    @Test
    fun `signed out viewers are never considered authenticated`() {
        assertFalse(FeedViewer().signedIn)
        assertTrue(FeedViewer(uid = "student-1").signedIn)
    }

    @Test
    fun `upvote from neutral increments once`() {
        val preview = previewVote(Post(id = "p", upvotesCount = 4), false, false, PostVote.Up)

        assertEquals(PostVote.Up, preview.vote)
        assertEquals(5, preview.post.upvotesCount)
        assertTrue(preview.upvoted)
        assertFalse(preview.downvoted)
    }

    @Test
    fun `upvote replaces a downvote without allowing negative counts`() {
        val preview = previewVote(
            Post(id = "p", upvotesCount = 2, downvotesCount = 0),
            wasUpvoted = false,
            wasDownvoted = true,
            requestedVote = PostVote.Up,
        )

        assertEquals(3, preview.post.upvotesCount)
        assertEquals(0, preview.post.downvotesCount)
        assertTrue(preview.upvoted)
        assertFalse(preview.downvoted)
    }

    @Test
    fun `tapping the active vote removes it`() {
        val preview = previewVote(Post(id = "p", downvotesCount = 3), false, true, PostVote.Down)

        assertNull(preview.vote)
        assertEquals(2, preview.post.downvotesCount)
        assertFalse(preview.upvoted)
        assertFalse(preview.downvoted)
    }

    @Test
    fun `pagination merge preserves order and removes duplicate ids`() {
        val merged = mergePosts(
            current = listOf(Post(id = "one"), Post(id = "two", title = "old")),
            incoming = listOf(Post(id = "two", title = "new"), Post(id = "three")),
        )

        assertEquals(listOf("one", "two", "three"), merged.map(Post::id))
        assertEquals("old", merged[1].title)
    }

    @Test
    fun `product and mixed order merges preserve server sequence`() {
        val products = mergeProducts(
            current = listOf(Product(id = "book-1", title = "Old")),
            incoming = listOf(Product(id = "book-1", title = "New"), Product(id = "book-2")),
        )
        val order = mergeFeedOrder(
            current = listOf(FeedOrderEntry("post-1", "post")),
            incoming = listOf(
                FeedOrderEntry("book-1", "product"),
                FeedOrderEntry("post-1", "post"),
            ),
        )

        assertEquals(listOf("book-1", "book-2"), products.map(Product::id))
        assertEquals("Old", products.first().title)
        assertEquals(listOf("post-1", "book-1"), order.map(FeedOrderEntry::id))
    }

    @Test
    fun `feed content follows callable order and appends hydrated extras`() {
        val content = buildFeedContent(
            posts = listOf(Post(id = "post-1"), Post(id = "post-2")),
            products = listOf(Product(id = "book-1")),
            order = listOf(
                FeedOrderEntry("book-1", "product"),
                FeedOrderEntry("post-1", "post"),
            ),
        )

        assertEquals(
            listOf("product:book-1", "post:post-1", "post:post-2"),
            content.map(FeedContent::key),
        )
    }

    @Test
    fun `feed content spaces products through chronological fallback`() {
        val content = buildFeedContent(
            posts = (1..5).map { Post(id = "post-$it") },
            products = listOf(Product(id = "book-1"), Product(id = "book-2")),
            order = emptyList(),
        )

        assertEquals(
            listOf(
                "post:post-1",
                "post:post-2",
                "post:post-3",
                "post:post-4",
                "product:book-1",
                "post:post-5",
                "product:book-2",
            ),
            content.map(FeedContent::key),
        )
    }

    @Test
    fun `fallback does not let listings overwhelm a short social feed`() {
        val content = buildFeedContent(
            posts = listOf(Post(id = "post-1")),
            products = (1..5).map { Product(id = "book-$it") },
            order = emptyList(),
        )

        assertEquals(listOf("post:post-1", "product:book-1"), content.map(FeedContent::key))
    }

    @Test
    fun `downvote replaces an upvote and updates both counters`() {
        val preview = previewVote(
            Post(id = "p", upvotesCount = 7, downvotesCount = 2),
            wasUpvoted = true,
            wasDownvoted = false,
            requestedVote = PostVote.Down,
        )

        assertEquals(6, preview.post.upvotesCount)
        assertEquals(3, preview.post.downvotesCount)
        assertFalse(preview.upvoted)
        assertTrue(preview.downvoted)
    }
}
