package com.nextbench.data.firebase

import com.nextbench.data.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductDetailRepositoryContractTest {

    @Test
    fun `product room payload preserves shared marketplace chat contract`() {
        val product = Product(
            id = "book-1",
            title = "Physics notes",
            sellerId = "seller-1",
        )

        val payload = productRoomPayload(
            buyerId = "buyer-1",
            sellerId = "seller-1",
            product = product,
            pending = true,
            initialMessage = "Interested",
        )

        assertEquals(listOf("buyer-1", "seller-1"), payload["participants"])
        assertEquals("product", payload["type"])
        assertEquals("book-1", payload["productId"])
        assertEquals("Physics notes", payload["productTitle"])
        assertEquals("pending", payload["status"])
        assertEquals("buyer-1", payload["requestedBy"])
        assertEquals("Interested", payload["lastMessage"])
        assertEquals(listOf("seller-1"), payload["unreadBy"])
    }

    @Test
    fun `room ids are deterministic and firestore safe`() {
        val first = productRoomId("book & notes", "buyer/1", "seller 2")
        val second = productRoomId("book & notes", "buyer/1", "seller 2")

        assertEquals(first, second)
        assertTrue(first.length <= 120)
        assertTrue(first.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun `inquiry message contains only fields accepted by message rules`() {
        val payload = inquiryMessagePayload("buyer-1", "Interested")

        assertEquals("buyer-1", payload["senderId"])
        assertEquals("Interested", payload["text"])
        assertEquals("text", payload["type"])
        assertTrue(payload.containsKey("createdAt"))
        assertEquals(setOf("senderId", "text", "type", "createdAt"), payload.keys)
    }

    @Test
    fun `review mapper keeps optional fields nullable and numeric values intact`() {
        val review = mapOf<String, Any?>(
            "id" to "review-1",
            "productId" to "book-1",
            "sellerId" to "seller-1",
            "reviewerId" to "buyer-1",
            "reviewerName" to "Maya",
            "rating" to 5L,
            "comment" to "Smooth exchange",
        ).toReview()

        assertEquals("review-1", review?.id)
        assertEquals(5, review?.rating)
        assertEquals("Smooth exchange", review?.comment)
        assertNull(review?.createdAt)
    }

    @Test
    fun `malformed review payloads are ignored`() {
        assertFalse(mapOf<String, Any?>("productId" to "book-1").toReview() != null)
        assertNull(mapOf<String, Any?>().toReview())
    }
}
