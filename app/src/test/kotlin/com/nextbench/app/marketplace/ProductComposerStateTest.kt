package com.nextbench.app.marketplace

import com.nextbench.data.firebase.ProductComposerRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductComposerStateTest {
    @Test
    fun `listing requires valid title price and at least one photo`() {
        assertFalse(canPublishProductDraft("", null, "Books", "Like New", 0, 0))
        assertFalse(canPublishProductDraft("Book", 350, "Books", "Like New", 0, 0))
        assertTrue(canPublishProductDraft("HC Verma Physics", 350, "Books", "Like New", 0, 1))
    }

    @Test
    fun `listing state enforces price and description limits`() {
        assertFalse(canPublishProductDraft("Book", 0, "Books", "Like New", 0, 1))
        assertFalse(canPublishProductDraft("Book", 100_001, "Books", "Like New", 0, 1))
        assertFalse(canPublishProductDraft("Book", 350, "Books", "Like New", ProductComposerRepository.MaxDescriptionLength + 1, 1))
    }

    @Test
    fun `publishing errors are actionable`() {
        assertTrue(IllegalStateException("Cloudinary is not configured").productComposerMessage().contains("Image uploads"))
        assertTrue(IllegalStateException("network unavailable").productComposerMessage().contains("internet"))
        assertTrue(IllegalStateException("Your session expired").productComposerMessage().contains("session expired"))
    }
}
