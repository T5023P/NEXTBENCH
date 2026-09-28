package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WishlistRepositoryContractTest {
    @Test
    fun `wishlist references ignore missing or blank product ids`() {
        assertNull(mapOf<String, Any?>().toWishlistReference("wishlist-1"))
        assertNull(mapOf("productId" to " ").toWishlistReference("wishlist-2"))
    }

    @Test
    fun `wishlist references preserve document id and created timestamp`() {
        val created = Timestamp(Date(1_700_000_000_000L))
        val reference = mapOf<String, Any?>(
            "productId" to "product-1",
            "createdAt" to created,
        ).toWishlistReference("wishlist-1")

        assertEquals("wishlist-1", reference?.id)
        assertEquals("product-1", reference?.productId)
        assertEquals(1_700_000_000_000L, reference?.savedAt?.toDate()?.time)
    }

    @Test
    fun `wishlist query chunks stay within firestore where-in limit`() {
        assertEquals(30, WishlistRepository.FirestoreWhereInLimit)
    }
}
