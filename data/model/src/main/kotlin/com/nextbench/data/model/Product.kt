package com.nextbench.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * `products/{id}` — a marketplace listing. Prices are in ₹ (INR). [image] is the primary
 * thumbnail; [images]/[imagesDetailed] hold the full gallery.
 */
data class Product(
    @DocumentId val id: String = "",
    val title: String = "",
    val price: Long = 0,
    val category: String = "",
    val condition: String = "",
    val description: String = "",
    val image: String? = null,
    val images: List<String> = emptyList(),
    val imagesDetailed: List<ImageDetail> = emptyList(),
    val status: String = ProductStatus.Available.raw,
    val sellerId: String = "",
    val sellerName: String = "",
    val sellerSchool: String = "",
    val sellerProfilePicture: String? = null,
    val school: String = "",
    val city: String? = null,
    val meetupAvailable: Boolean = true,
    val deliveryAvailable: Boolean = false,
    val reservedById: String? = null,
    val wishlistCount: Int = 0,
    val inquiryCount: Int = 0,
    val sellerReputation: Double? = null,
    val sellerReviewCount: Int = 0,
    val sellerReputationBadges: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

/** A gallery image with intrinsic pixel dimensions (used to reserve aspect-ratio space). */
data class ImageDetail(
    val url: String = "",
    val w: Int = 0,
    val h: Int = 0,
)

/** `wishlists/{id}` — a user's saved marketplace listing. */
data class Wishlist(
    @DocumentId val id: String = "",
    val userId: String = "",
    val productId: String = "",
    val createdAt: Timestamp? = null,
)

/** `reviews/{id}` — a buyer's review of a seller after a transaction. */
data class Review(
    @DocumentId val id: String = "",
    val productId: String = "",
    val sellerId: String = "",
    val reviewerId: String = "",
    val reviewerName: String = "",
    val reviewerProfilePicture: String? = null,
    val rating: Int = 0,
    val comment: String? = null,
    val createdAt: Timestamp? = null,
)
