package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceRepositoryContractTest {
    @Test
    fun `marketplace payload keeps populated discovery cursor fields`() {
        val payload = marketplacePayload(
            MarketplaceCursor(productCreatedAt = 123L, cursorIndex = 20),
        )

        assertEquals("for-you", payload["mode"])
        assertEquals(123L, payload["productCreatedAt"])
        assertEquals(20, payload["cursorIndex"])
    }

    @Test
    fun `callable page maps the complete marketplace contract`() {
        val page = mapOf<String, Any?>(
            "products" to listOf(
                mapOf(
                    "id" to "product-1",
                    "title" to "Engineering drawing set",
                    "price" to 750.0,
                    "category" to "Books & Notes",
                    "condition" to "Like New",
                    "description" to "Complete first-year kit",
                    "image" to "https://cdn/cover.jpg",
                    "images" to listOf("https://cdn/cover.jpg", "https://cdn/detail.jpg"),
                    "imagesDetailed" to listOf(mapOf("url" to "https://cdn/cover.jpg", "w" to 1200L, "h" to 900L)),
                    "sellerId" to "seller-1",
                    "sellerName" to "Maya",
                    "sellerSchool" to "Next School",
                    "meetupAvailable" to false,
                    "deliveryAvailable" to true,
                    "sellerReputation" to 4.8,
                    "sellerReviewCount" to 12L,
                    "sellerReputationBadges" to listOf("Fast responder"),
                    "createdAt" to 1_700_000_000_000L,
                ),
            ),
            "nextCursor" to mapOf("productCreatedAt" to 1_699_000_000_000L),
            "hasMoreProducts" to true,
        ).toMarketplacePage()

        val product = page.products.single()
        assertEquals("product-1", product.id)
        assertEquals(750L, product.price)
        assertEquals(2, product.images.size)
        assertEquals(1200, product.imagesDetailed.single().w)
        assertFalse(product.meetupAvailable)
        assertTrue(product.deliveryAvailable)
        assertEquals(4.8, product.sellerReputation ?: 0.0, 0.0)
        assertEquals(12, product.sellerReviewCount)
        assertEquals(1_700_000_000_000L, product.createdAt?.toDate()?.time)
        assertEquals(1_699_000_000_000L, page.nextCursor.productCreatedAt)
        assertTrue(page.hasMore)
    }

    @Test
    fun `malformed products are dropped and defaults remain usable`() {
        val page = mapOf<String, Any?>(
            "products" to listOf(mapOf("title" to "Missing id"), mapOf("id" to "valid")),
            "nextCursor" to emptyMap<String, Any?>(),
        ).toMarketplacePage()

        val product = page.products.single()
        assertEquals("valid", product.id)
        assertEquals("available", product.status)
        assertTrue(product.meetupAvailable)
        assertNull(product.createdAt)
        assertFalse(page.hasMore)
    }

    @Test
    fun `wishlist interactions preserve existing generated document ids`() {
        val interactions = WishlistInteractions(mapOf("product-1" to "legacy-doc"))

        assertEquals(setOf("product-1"), interactions.productIds)
        assertEquals("legacy-doc", interactions.documentIds["product-1"])
    }
}
