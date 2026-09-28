package com.nextbench.app.marketplace

import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.UserData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductDetailViewModelTest {

    @Test
    fun `available listing exposes buyer actions only to verified students`() {
        val product = Product(id = "p", sellerId = "seller", status = ProductStatus.Available.raw)

        val verified = productActionPolicy(product, UserData(uid = "buyer", verified = true))
        val unverified = productActionPolicy(product, UserData(uid = "buyer", verified = false))

        assertTrue(verified.canReserve)
        assertTrue(verified.canContactSeller)
        assertTrue(verified.canWishlist)
        assertFalse(unverified.canReserve)
        assertFalse(unverified.canContactSeller)
        assertFalse(unverified.canWishlist)
    }

    @Test
    fun `seller controls follow firestore status transitions`() {
        val seller = UserData(uid = "seller", verified = true)
        val available = productActionPolicy(
            Product(id = "p", sellerId = "seller", status = ProductStatus.Available.raw),
            seller,
        )
        val reserved = productActionPolicy(
            Product(id = "p", sellerId = "seller", status = ProductStatus.Reserved.raw, reservedById = "buyer"),
            seller,
        )

        assertTrue(available.canEdit)
        assertFalse(available.canMarkSold)
        assertFalse(available.canReserve)
        assertTrue(reserved.canMarkSold)
        assertTrue(reserved.canCancelReservation)
        assertFalse(reserved.canEdit)
    }

    @Test
    fun `only the completed buyer can review a sold listing`() {
        val product = Product(
            id = "p",
            sellerId = "seller",
            reservedById = "buyer",
            status = ProductStatus.Sold.raw,
        )

        assertTrue(productActionPolicy(product, UserData(uid = "buyer", verified = true)).canReview)
        assertFalse(productActionPolicy(product, UserData(uid = "other", verified = true)).canReview)
        assertFalse(productActionPolicy(product, UserData(uid = "seller", verified = true)).canReview)
    }

    @Test
    fun `pending and rejected listings never expose buyer interactions`() {
        listOf(ProductStatus.Pending, ProductStatus.Rejected).forEach { status ->
            val policy = productActionPolicy(
                Product(id = "p", sellerId = "seller", status = status.raw),
                UserData(uid = "buyer", verified = true),
            )

            assertFalse(policy.canReserve)
            assertFalse(policy.canContactSeller)
            assertFalse(policy.canWishlist)
        }
    }

    @Test
    fun `detail errors retain useful validation feedback`() {
        assertTrue(IllegalStateException("Firebase is not configured").productDetailMessage().contains("google-services.json"))
        assertTrue(IllegalArgumentException("Reviews can be up to 500 characters.").productDetailMessage().contains("500"))
    }
}
