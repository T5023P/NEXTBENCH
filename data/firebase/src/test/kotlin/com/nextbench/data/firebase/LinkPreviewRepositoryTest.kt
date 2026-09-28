package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewRepositoryTest {
    @Test
    fun `first message url normalizes www links and trims sentence punctuation`() {
        assertEquals("https://www.nextbench.in/books", firstMessageUrl("Read www.nextbench.in/books."))
        assertEquals("https://example.com/path", firstMessageUrl("Open https://example.com/path) when ready"))
        assertNull(firstMessageUrl("No link in this message"))
    }

    @Test
    fun `callable preview maps useful metadata and falls back to requested url`() {
        val preview = mapOf<String, Any?>(
            "title" to "NextBench",
            "description" to "Student community",
            "image" to "https://cdn.example/preview.jpg",
            "siteName" to "NextBench",
        ).toLinkPreview("https://nextbench.in")

        assertEquals("https://nextbench.in", preview?.url)
        assertEquals("NextBench", preview?.title)
        assertEquals("Student community", preview?.description)
    }

    @Test
    fun `callable preview ignores errors and empty metadata`() {
        assertNull(mapOf<String, Any?>("error" to "blocked").toLinkPreview("https://example.com"))
        assertNull(emptyMap<String, Any?>().toLinkPreview("https://example.com"))
    }
}
