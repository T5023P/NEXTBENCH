package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryContractTest {
    @Test
    fun `profile content keeps newest unique listings and posts first`() {
        val first = Timestamp(Date(1_700_000_000_000L))
        val newest = Timestamp(Date(1_700_100_000_000L))
        val content = buildProfileContent(
            user = UserData(uid = "student-1", name = "Maya"),
            listings = listOf(
                Product(id = "item-1", title = "Old copy", createdAt = first),
                Product(id = "item-1", title = "Updated copy", updatedAt = newest),
                Product(id = "item-2", title = "Newest", createdAt = newest),
            ),
            posts = listOf(
                Post(id = "post-old", createdAt = first),
                Post(id = "post-new", createdAt = newest),
                Post(id = "post-new", title = "Duplicate"),
            ),
        )

        assertEquals(listOf("item-1", "item-2"), content.listings.map(Product::id))
        assertEquals("Updated copy", content.listings.first().title)
        assertEquals(listOf("post-new", "post-old"), content.posts.map(Post::id))
        assertEquals("Maya", content.user?.name)
    }

    @Test
    fun `empty owner profile stays nullable without manufacturing identity`() {
        val content = buildProfileContent(null, emptyList(), emptyList())

        assertSame(null, content.user)
        assertEquals(emptyList<Product>(), content.listings)
        assertEquals(emptyList<Post>(), content.posts)
    }

    @Test
    fun `profile content preserves connection counts`() {
        val content = buildProfileContent(
            user = null,
            listings = emptyList(),
            posts = emptyList(),
            followersCount = 12,
            followingCount = 7,
        )

        assertEquals(12, content.followersCount)
        assertEquals(7, content.followingCount)
    }

    @Test
    fun `username validation mirrors the website contract`() {
        assertTrue(validateUsername("maya.notes").valid)
        assertFalse(validateUsername("2maya").valid)
        assertFalse(validateUsername("ma").valid)
        assertFalse(validateUsername("maya__notes").valid)
        assertFalse(validateUsername("marketplace").valid)
        assertEquals("mayanotes", normalizeUsernameInput("Maya Notes!!"))
    }

    @Test
    fun `profile updates enforce public field limits`() {
        assertEquals(
            "Display name is required.",
            validateProfileUpdate(ProfileUpdateDraft(name = " ", about = "", username = "maya")),
        )
        assertEquals(
            "About can be up to 500 characters.",
            validateProfileUpdate(ProfileUpdateDraft(name = "Maya", about = "a".repeat(501), username = "maya")),
        )
        assertSame(null, validateProfileUpdate(ProfileUpdateDraft(name = "Maya", about = "Reader", username = "maya")))
    }

    @Test
    fun `username cooldown expires exactly after thirty days`() {
        val now = 2_000_000_000_000L
        assertEquals(ProfileRepository.UsernameCooldownMillis, usernameCooldownRemaining(now, now))
        assertEquals(1L, usernameCooldownRemaining(now, now + ProfileRepository.UsernameCooldownMillis - 1L))
        assertEquals(0L, usernameCooldownRemaining(now, now + ProfileRepository.UsernameCooldownMillis))
        assertEquals(0L, usernameCooldownRemaining(null, now))
    }
}
