package com.nextbench.app.marketplace

import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistViewModelTest {
    @Test
    fun `wishlist errors stay actionable`() {
        assertTrue(IllegalStateException("Firebase is not configured").wishlistMessage().contains("google-services.json"))
        assertTrue(IllegalStateException("network unavailable").wishlistMessage().contains("internet"))
        assertTrue(IllegalStateException("Your session expired").wishlistMessage().contains("session expired"))
    }
}
