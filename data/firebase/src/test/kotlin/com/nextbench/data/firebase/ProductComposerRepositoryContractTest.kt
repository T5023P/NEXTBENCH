package com.nextbench.data.firebase

import com.nextbench.data.model.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductComposerRepositoryContractTest {
    private val user = UserData(
        uid = "student-1",
        name = "Maya Singh",
        school = "Next School",
        city = "Lucknow",
        profilePicture = "https://cdn/avatar.jpg",
        verified = true,
    )

    @Test
    fun `pending listing payload preserves marketplace schema and media metadata`() {
        val payload = newProductPayload(
            user = user,
            draft = NewProductDraft(
                title = "  HC Verma Physics  ",
                price = 350,
                category = "Books",
                condition = "Like New",
                description = "  Barely used  ",
                meetupAvailable = true,
                deliveryAvailable = false,
                images = emptyList(),
            ),
            uploads = listOf(CloudinaryResult("https://cdn/book.jpg", "products/book", 1200, 900, "jpg")),
        )

        assertEquals("HC Verma Physics", payload["title"])
        assertEquals(350L, payload["price"])
        assertEquals("Books", payload["category"])
        assertEquals("Like New", payload["condition"])
        assertEquals("Barely used", payload["description"])
        assertEquals("student-1", payload["sellerId"])
        assertEquals("Next School", payload["sellerSchool"])
        assertEquals("Lucknow", payload["city"])
        assertEquals("pending", payload["status"])
        assertEquals(listOf("https://cdn/book.jpg"), payload["images"])
        assertEquals("https://cdn/book.jpg", payload["image"])
        assertEquals(1200, payload["imageWidth"])
        assertEquals(900, payload["imageHeight"])
        assertEquals(0, payload["wishlistCount"])
        assertEquals(0, payload["inquiryCount"])
        assertTrue(payload.containsKey("createdAt"))
        assertTrue(payload.containsKey("updatedAt"))
    }

    @Test
    fun `blank city is omitted because product rules require nonblank city values`() {
        val payload = newProductPayload(
            user = user.copy(city = "  "),
            draft = NewProductDraft(
                title = "Calculator",
                price = 800,
                category = "Electronics",
                condition = "Good",
                description = "Works well",
                meetupAvailable = true,
                deliveryAvailable = true,
                images = emptyList(),
            ),
            uploads = emptyList(),
        )

        assertFalse(payload.containsKey("city"))
        assertFalse(payload.containsKey("reservedById"))
    }

    @Test
    fun `repository constants align with website listing limits`() {
        assertEquals(5, ProductComposerRepository.MaxImages)
        assertEquals(100, ProductComposerRepository.MaxTitleLength)
        assertEquals(2_000, ProductComposerRepository.MaxDescriptionLength)
        assertEquals(100_000L, ProductComposerRepository.MaxPrice)
        assertTrue(ProductComposerRepository.Conditions.containsAll(listOf("Brand New", "Like New", "Good", "Used")))
    }

    @Test
    fun `edit payload only changes fields allowed by marketplace rules`() {
        val payload = productUpdatePayload(
            user = user,
            draft = ProductEditDraft(
                title = "Updated calculator",
                price = 900,
                category = "Electronics",
                condition = "Good",
                description = "Includes a case",
                meetupAvailable = true,
                deliveryAvailable = true,
                retainedImageUrls = listOf("https://cdn/old.jpg"),
                newImages = emptyList(),
            ),
            imageUrls = listOf("https://cdn/old.jpg", "https://cdn/new.jpg"),
        )

        assertEquals(
            setOf("title", "price", "condition", "category", "image", "images", "description", "meetupAvailable", "deliveryAvailable", "city", "updatedAt"),
            payload.keys,
        )
        assertEquals("https://cdn/old.jpg", payload["image"])
        assertEquals(listOf("https://cdn/old.jpg", "https://cdn/new.jpg"), payload["images"])
        assertFalse(payload.containsKey("status"))
        assertFalse(payload.containsKey("sellerId"))
    }
}
