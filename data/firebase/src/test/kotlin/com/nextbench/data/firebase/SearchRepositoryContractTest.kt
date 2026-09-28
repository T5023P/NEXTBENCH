package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryContractTest {
    @Test
    fun `search mapper preserves people posts and listings`() {
        val results = mapOf<String, Any?>(
            "users" to listOf(mapOf<String, Any?>("id" to "user-1", "name" to "Maya", "verified" to true)),
            "posts" to listOf(mapOf<String, Any?>("id" to "post-1", "title" to "Physics notes", "status" to "approved")),
            "products" to listOf(mapOf<String, Any?>("id" to "item-1", "title" to "Calculator", "price" to 850L, "status" to "available")),
        ).toSearchResults()

        assertEquals("user-1", results.people.single().uid)
        assertEquals("post-1", results.posts.single().id)
        assertEquals("item-1", results.listings.single().id)
    }

    @Test
    fun `search mapper tolerates missing result buckets`() {
        val results = emptyMap<String, Any?>().toSearchResults()

        assertTrue(results.people.isEmpty())
        assertTrue(results.posts.isEmpty())
        assertTrue(results.listings.isEmpty())
        assertTrue(results.clubs.isEmpty())
    }

    @Test
    fun `search mapper preserves public club results`() {
        val results = mapOf<String, Any?>(
            "clubs" to listOf(
                mapOf<String, Any?>("id" to "club-1", "name" to "Book Club", "school" to "North High", "memberCount" to 24L),
            ),
        ).toSearchResults()

        assertEquals("club-1", results.clubs.single().id)
        assertEquals("Book Club", results.clubs.single().name)
        assertEquals(24, results.clubs.single().memberCount)
    }
}
