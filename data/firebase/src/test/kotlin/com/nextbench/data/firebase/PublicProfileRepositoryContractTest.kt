package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicProfileRepositoryContractTest {
    @Test
    fun `public profile mapper keeps only public identity fields and activity`() {
        val content = mapOf<String, Any?>(
            "user" to mapOf(
                "id" to "student-1",
                "name" to "Maya Singh",
                "username" to "maya",
                "school" to "Next School",
                "city" to "Lucknow",
                "verified" to true,
                "reputation" to 4.8,
                "profilePicture" to "https://cdn/avatar.jpg",
                "about" to "Notes and useful finds.",
                "anonymousPersonaName" to "Hidden Persona",
            ),
            "products" to listOf(mapOf<String, Any?>("id" to "item-1", "title" to "Calculator", "status" to "available", "price" to 800L)),
            "posts" to listOf(mapOf<String, Any?>("id" to "post-1", "title" to "Study group", "authorId" to "student-1", "status" to "approved")),
        )

        val mapped = content.toPublicProfileContent()
        assertEquals("student-1", mapped.user?.uid)
        assertEquals("maya", mapped.user?.username)
        assertNull(mapped.user?.anonymousPersonaName)
        assertEquals("item-1", mapped.listings.single().id)
        assertEquals("post-1", mapped.posts.single().id)
        assertTrue(mapped.user?.verified == true)
    }

    @Test
    fun `missing public user remains unavailable`() {
        val mapped = mapOf<String, Any?>("user" to emptyMap<String, Any?>(), "products" to emptyList<Any?>(), "posts" to emptyList<Any?>()).toPublicProfileContent()
        assertNull(mapped.user)
        assertTrue(mapped.listings.isEmpty())
        assertTrue(mapped.posts.isEmpty())
    }

    @Test
    fun `public profile stats retain relationship state`() {
        val stats = PublicProfileStats(
            followersCount = 4,
            followingCount = 2,
            mutualCount = 1,
            isFollowing = true,
            isFollowedBy = false,
        )

        assertTrue(stats.isFollowing)
        assertTrue(!stats.isFollowedBy)
        assertEquals(4, stats.followersCount)
    }

    @Test
    fun `android profile stats mapper keeps counts and public relationship users`() {
        val mapped = mapOf<String, Any?>(
            "followersCount" to 12L,
            "followingCount" to 7,
            "mutualCount" to 2L,
            "isFollowing" to true,
            "isFollowedBy" to false,
            "followers" to listOf(mapOf<String, Any?>("id" to "follower-1", "name" to "Maya")),
            "following" to listOf(mapOf<String, Any?>("id" to "following-1", "name" to "Noah")),
            "mutuals" to listOf(mapOf<String, Any?>("id" to "mutual-1", "name" to "Aarav")),
        ).toPublicProfileStats()

        assertEquals(12, mapped.followersCount)
        assertEquals(7, mapped.followingCount)
        assertEquals(2, mapped.mutualCount)
        assertTrue(mapped.isFollowing)
        assertEquals("mutual-1", mapped.mutuals.single().uid)
    }
}
