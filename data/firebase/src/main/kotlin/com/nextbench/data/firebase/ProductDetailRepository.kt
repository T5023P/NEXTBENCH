package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.Review
import java.security.MessageDigest
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Reads and mutates one marketplace listing. The write methods mirror the website's
 * Firestore contract so Android and web clients can safely share listing state.
 */
@Singleton
class ProductDetailRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()

    suspend fun loadProduct(productId: String): Result<Product> = runCatching {
        ensureConfigured()
        require(productId.isNotBlank()) { "Missing listing id." }
        refs.product(productId).get().await().toMarketplaceProduct()
            ?: throw FirebaseFirestoreException(
                "This listing is no longer available.",
                FirebaseFirestoreException.Code.NOT_FOUND,
            )
    }

    suspend fun loadReviews(productId: String): Result<List<Review>> = runCatching {
        ensureConfigured()
        require(productId.isNotBlank()) { "Missing listing id." }
        functions.getProductReviews(productId)
            .mapNotNull(Map<String, Any?>::toReview)
            .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }
    }

    suspend fun wishlistDocument(uid: String, productId: String): Result<String?> = runCatching {
        ensureConfigured()
        require(uid.isNotBlank()) { "Sign in to save listings." }
        require(productId.isNotBlank()) { "Missing listing id." }
        refs.wishlists
            .whereEqualTo("userId", uid)
            .whereEqualTo("productId", productId)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.id
    }

    suspend fun setWishlisted(
        uid: String,
        productId: String,
        wishlisted: Boolean,
        currentDocumentId: String?,
    ): Result<String?> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(productId.isNotBlank()) { "Missing listing id." }
        val wishlistRef = refs.wishlists.document(currentDocumentId ?: "${uid}_$productId")
        if (wishlisted) {
            wishlistRef.set(
                mapOf(
                    "userId" to uid,
                    "productId" to productId,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            wishlistRef.id
        } else {
            wishlistRef.delete().await()
            null
        }
    }

    suspend fun reserve(productId: String, buyerId: String): Result<Product> = runCatching {
        ensureConfigured()
        requireAuthenticated(buyerId)
        val updated = mutateProduct(productId) { current ->
            require(current.sellerId != buyerId) { "This is your listing." }
            require(ProductStatus.from(current.status) == ProductStatus.Available) {
                when (ProductStatus.from(current.status)) {
                    ProductStatus.Pending -> "This listing is still being reviewed."
                    ProductStatus.Reserved -> "This listing is already reserved."
                    ProductStatus.Sold -> "This listing has already been sold."
                    ProductStatus.Rejected -> "This listing is unavailable."
                    ProductStatus.Available -> "This listing cannot be reserved."
                }
            }
            ProductMutation(status = ProductStatus.Reserved, reservedById = buyerId)
        }
        runCatching {
            functions.createNotification(
                mapOf(
                    "userId" to updated.sellerId,
                    "type" to "item_reserved",
                    "title" to "Item reserved",
                    "message" to "A verified student reserved \"${updated.title}\"",
                    "link" to "/product/${updated.id}",
                ),
            )
        }
        updated
    }

    suspend fun releaseReservation(productId: String, actorId: String): Result<Product> = runCatching {
        ensureConfigured()
        requireAuthenticated(actorId)
        mutateProduct(productId) { current ->
            require(ProductStatus.from(current.status) == ProductStatus.Reserved) {
                "This listing is not reserved."
            }
            require(current.sellerId == actorId || current.reservedById == actorId) {
                "You cannot cancel this reservation."
            }
            ProductMutation(status = ProductStatus.Available, reservedById = null)
        }
    }

    suspend fun markSold(productId: String, sellerId: String): Result<Product> = runCatching {
        ensureConfigured()
        requireAuthenticated(sellerId)
        val updated = mutateProduct(productId) { current ->
            require(current.sellerId == sellerId) { "Only the seller can mark this listing sold." }
            require(ProductStatus.from(current.status) == ProductStatus.Reserved) {
                "Reserve the listing before marking it sold."
            }
            ProductMutation(status = ProductStatus.Sold, reservedById = current.reservedById)
        }
        updated.reservedById?.let { buyerId ->
            runCatching {
                functions.createNotification(
                    mapOf(
                        "userId" to buyerId,
                        "type" to "item_sold",
                        "title" to "Transaction complete",
                        "message" to "\"${updated.title}\" was marked as sold. You can now leave a review.",
                        "link" to "/product/${updated.id}",
                    ),
                )
            }
        }
        updated
    }

    suspend fun createReview(productId: String, rating: Int, comment: String): Result<String> = runCatching {
        ensureConfigured()
        requireNotNull(auth.currentUser?.uid) { "Sign in to review this transaction." }
        require(productId.isNotBlank()) { "Missing listing id." }
        require(rating in 1..5) { "Choose a rating from 1 to 5." }
        val normalizedComment = comment.trim()
        require(normalizedComment.length <= ReviewCharacterLimit) {
            "Reviews can be up to $ReviewCharacterLimit characters."
        }
        functions.createProductReview(
            mapOf(
                "productId" to productId,
                "rating" to rating,
                "comment" to normalizedComment,
            ),
        )
    }

    /** Creates (or reuses) a product-aware room and sends the opening inquiry. */
    suspend fun contactSeller(product: Product, buyerId: String): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(buyerId)
        require(product.sellerId.isNotBlank() && product.sellerId != buyerId) {
            "This is your listing."
        }
        require(!hasBlockRelationship(buyerId, product.sellerId)) {
            "Cannot message this seller."
        }

        val seller = refs.user(product.sellerId).get().await()
        val followersOnly = seller.get("chatPrivacy.followersOnly") as? Boolean ?: false
        val pending = followersOnly && !isFollowing(buyerId, product.sellerId)
        val roomId = productRoomId(product.id, buyerId, product.sellerId)
        val roomRef = refs.chatRoom(roomId)
        val roomSnapshot = roomRef.get().await()
        if (roomSnapshot.exists()) return@runCatching roomId

        val interestMessage = "Hey! I'm interested in your listing: \"${product.title}\" (₹${product.price})"
        val messageRef = refs.messages(roomId).document()
        roomRef.set(
            productRoomPayload(
                buyerId = buyerId,
                sellerId = product.sellerId,
                product = product,
                pending = pending,
                initialMessage = interestMessage,
            ),
        ).await()
        messageRef.set(inquiryMessagePayload(senderId = buyerId, text = interestMessage)).await()
        runCatching {
            functions.createNotification(
                mapOf(
                    "userId" to product.sellerId,
                    "type" to "new_message",
                    "title" to "New inquiry",
                    "message" to interestMessage,
                    "link" to "/messages/$roomId",
                ),
            )
        }
        roomId
    }

    private suspend fun mutateProduct(
        productId: String,
        mutation: (Product) -> ProductMutation,
    ): Product {
        require(productId.isNotBlank()) { "Missing listing id." }
        val productRef = refs.product(productId)
        return productRef.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val current = snapshot.toMarketplaceProduct()
                ?: throw FirebaseFirestoreException(
                    "This listing is no longer available.",
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )
            val next = mutation(current)
            val serverTime = FieldValue.serverTimestamp()
            transaction.update(
                productRef,
                mapOf(
                    "status" to next.status.raw,
                    "reservedById" to next.reservedById,
                    "updatedAt" to serverTime,
                ),
            )
            current.copy(
                status = next.status.raw,
                reservedById = next.reservedById,
                updatedAt = Timestamp.now(),
            )
        }.await()
    }

    private suspend fun isFollowing(followerId: String, followingId: String): Boolean =
        refs.follows
            .whereEqualTo("followerId", followerId)
            .whereEqualTo("followingId", followingId)
            .limit(1)
            .get()
            .await()
            .documents
            .isNotEmpty()

    private suspend fun hasBlockRelationship(firstId: String, secondId: String): Boolean {
        val first = refs.blocks.document("${firstId}_$secondId").get().await()
        if (first.exists()) return true
        return refs.blocks.document("${secondId}_$firstId").get().await().exists()
    }

    private fun requireAuthenticated(uid: String) {
        require(auth.currentUser?.uid == uid) { "Your session expired. Sign in and try again." }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ProductDetailConfigurationException()
    }

    companion object {
        const val ReviewCharacterLimit = 500
    }
}

private data class ProductMutation(
    val status: ProductStatus,
    val reservedById: String?,
)

internal fun DocumentSnapshot.toMarketplaceProduct(): Product? =
    data?.plus("id" to id)?.toProduct()

internal fun Map<String, Any?>.toReview(): com.nextbench.data.model.Review? {
    val id = get("id")?.toString().orEmpty()
    val productId = get("productId")?.toString().orEmpty()
    if (id.isBlank() || productId.isBlank()) return null
    return com.nextbench.data.model.Review(
        id = id,
        productId = productId,
        sellerId = get("sellerId")?.toString().orEmpty(),
        reviewerId = get("reviewerId")?.toString().orEmpty(),
        reviewerName = get("reviewerName")?.toString().orEmpty(),
        reviewerProfilePicture = get("reviewerProfilePicture")?.toString()?.takeIf(String::isNotBlank),
        rating = (get("rating") as? Number)?.toInt() ?: 0,
        comment = get("comment")?.toString()?.takeIf(String::isNotBlank),
        createdAt = when (val value = get("createdAt")) {
            is Timestamp -> value
            is Date -> Timestamp(value)
            is Number -> Timestamp(Date(value.toLong()))
            else -> null
        },
    )
}

internal fun productRoomId(productId: String, buyerId: String, sellerId: String): String =
    "product_${sha256("$productId\u0000$buyerId\u0000$sellerId")}"

internal fun productRoomPayload(
    buyerId: String,
    sellerId: String,
    product: Product,
    pending: Boolean,
    initialMessage: String,
): Map<String, Any?> = mapOf(
    "participants" to listOf(buyerId, sellerId),
    "type" to "product",
    "productId" to product.id,
    "productTitle" to product.title,
    "lastMessage" to initialMessage,
    "lastSenderId" to buyerId,
    "status" to if (pending) "pending" else "active",
    "requestedBy" to buyerId.takeIf { pending },
    "unreadBy" to listOf(sellerId),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun inquiryMessagePayload(
    senderId: String,
    text: String,
): Map<String, Any?> = mapOf(
    "senderId" to senderId,
    "text" to text,
    "type" to "text",
    "createdAt" to FieldValue.serverTimestamp(),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

private class ProductDetailConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
