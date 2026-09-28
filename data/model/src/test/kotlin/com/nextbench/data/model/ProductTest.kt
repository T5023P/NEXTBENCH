package com.nextbench.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductTest {
    @Test
    fun `product defaults match the live marketplace schema`() {
        val product = Product()

        assertTrue(product.meetupAvailable)
        assertFalse(product.deliveryAvailable)
        assertNull(product.sellerReputation)
        assertEquals(0, product.sellerReviewCount)
        assertTrue(product.sellerReputationBadges.isEmpty())
    }
}
