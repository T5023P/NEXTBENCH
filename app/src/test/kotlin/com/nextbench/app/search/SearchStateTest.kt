package com.nextbench.app.search

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchStateTest {
    @Test
    fun `search errors explain setup and network failures`() {
        assertTrue(IllegalStateException("Firebase is not configured").searchMessage().contains("google-services.json"))
        assertTrue(IllegalStateException("network unavailable").searchMessage().contains("internet"))
    }

    @Test
    fun `recent searches are normalized, deduplicated, and bounded`() {
        val existing = (1..8).map { "Old $it" }
        val result = buildRecentSearches("  Physics   Notes ", existing)

        assertEquals("Physics Notes", result.first())
        assertEquals(8, result.size)
        assertEquals("Old 7", result.last())
    }

    @Test
    fun `recent search matching is case insensitive`() {
        val result = buildRecentSearches("books", listOf("Books", "Other"))
        assertEquals("books", result.first())
        assertEquals(2, result.size)
        assertTrue(result.none { it == "Books" })
    }
}
