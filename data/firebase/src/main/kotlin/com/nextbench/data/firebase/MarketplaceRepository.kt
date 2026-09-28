package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.nextbench.data.model.ImageDetail
import com.nextbench.data.model.Product
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

data class MarketplaceCursor(
    val productCreatedAt: Long? = null,
    val cursorIndex: Int? = null,
)

data class MarketplacePage(
    val products: List<Product>,
    val nextCursor: MarketplaceCursor,
    val hasMore: Boolean,
)

data class WishlistInteractions(
    val documentIds: Map<String, String> = emptyMap(),
) {
    val productIds: Set<String> get() = documentIds.keys
}

@Singleton
class MarketplaceRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()

    suspend fun loadPage(cursor: MarketplaceCursor? = null): Result<MarketplacePage> = runCatching {
        ensureConfigured()
        functions.getDiscoveryFeed(marketplacePayload(cursor)).toMarketplacePage()
    }

    suspend fun loadWishlist(uid: String): Result<WishlistInteractions> = runCatching {
        ensureConfigured()
        require(uid.isNotBlank()) { "Sign in to load saved listings." }
        val documents = refs.wishlists.whereEqualTo("userId", uid).get().await().documents
        WishlistInteractions(
            documentIds = documents.mapNotNull { document ->
                document.getString("productId")
                    ?.takeIf(String::isNotBlank)
                    ?.let { it to document.id }
            }.toMap(),
        )
    }

    suspend fun setWishlisted(
        productId: String,
        wishlisted: Boolean,
        currentDocumentId: String?,
    ): Result<String?> = runCatching {
        ensureConfigured()
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to save listings." }
        val wishlistRef = refs.wishlists.document(currentDocumentId ?: wishlistId(uid, productId))
        if (wishlisted) {
            wishlistRef.set(
                mapOf(
                    "userId" to uid,
                    "productId" to productId,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } else {
            wishlistRef.delete().await()
        }
        wishlistRef.id.takeIf { wishlisted }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw MarketplaceConfigurationException()
    }
}

internal fun marketplacePayload(cursor: MarketplaceCursor?): Map<String, Any> = buildMap {
    put("mode", "for-you")
    cursor?.productCreatedAt?.let { put("productCreatedAt", it) }
    cursor?.cursorIndex?.let { put("cursorIndex", it) }
}

internal fun Map<String, Any?>.toMarketplacePage(): MarketplacePage = MarketplacePage(
    products = mapList("products").mapNotNull(Map<String, Any?>::toProduct),
    nextCursor = this["nextCursor"].asStringMap().let { cursor ->
        MarketplaceCursor(
            productCreatedAt = cursor.marketplaceLong("productCreatedAt"),
            cursorIndex = cursor.marketplaceInt("cursorIndex"),
        )
    },
    hasMore = this["hasMoreProducts"] as? Boolean ?: false,
)

internal fun Map<String, Any?>.toProduct(): Product? {
    val id = marketplaceString("id")
    if (id.isBlank()) return null
    return Product(
        id = id,
        title = marketplaceString("title"),
        price = marketplaceLong("price") ?: 0L,
        category = marketplaceString("category"),
        condition = marketplaceString("condition"),
        description = marketplaceString("description"),
        image = marketplaceNullableString("image"),
        images = marketplaceStringList("images"),
        imagesDetailed = marketplaceMapList("imagesDetailed").map { image ->
            ImageDetail(
                url = image.marketplaceString("url"),
                w = image.marketplaceInt("w") ?: 0,
                h = image.marketplaceInt("h") ?: 0,
            )
        },
        status = marketplaceString("status", "available"),
        sellerId = marketplaceString("sellerId"),
        sellerName = marketplaceString("sellerName"),
        sellerSchool = marketplaceString("sellerSchool"),
        sellerProfilePicture = marketplaceNullableString("sellerProfilePicture"),
        school = marketplaceString("school"),
        city = marketplaceNullableString("city"),
        meetupAvailable = marketplaceBoolean("meetupAvailable", true),
        deliveryAvailable = marketplaceBoolean("deliveryAvailable"),
        reservedById = marketplaceNullableString("reservedById"),
        wishlistCount = marketplaceInt("wishlistCount") ?: 0,
        inquiryCount = marketplaceInt("inquiryCount") ?: 0,
        sellerReputation = marketplaceDouble("sellerReputation"),
        sellerReviewCount = marketplaceInt("sellerReviewCount") ?: 0,
        sellerReputationBadges = marketplaceStringList("sellerReputationBadges"),
        createdAt = marketplaceTimestamp("createdAt"),
        updatedAt = marketplaceTimestamp("updatedAt"),
    )
}

private fun wishlistId(uid: String, productId: String): String = "${uid}_$productId"

private fun Map<String, Any?>.marketplaceString(key: String, fallback: String = ""): String =
    get(key)?.toString()?.takeUnless { it == "null" } ?: fallback

private fun Map<String, Any?>.marketplaceNullableString(key: String): String? =
    get(key)?.toString()?.takeUnless { it == "null" || it.isBlank() }

private fun Map<String, Any?>.marketplaceBoolean(key: String, fallback: Boolean = false): Boolean =
    get(key) as? Boolean ?: fallback

private fun Map<String, Any?>.marketplaceInt(key: String): Int? = (get(key) as? Number)?.toInt()

private fun Map<String, Any?>.marketplaceLong(key: String): Long? = (get(key) as? Number)?.toLong()

private fun Map<String, Any?>.marketplaceDouble(key: String): Double? = (get(key) as? Number)?.toDouble()

private fun Map<String, Any?>.marketplaceStringList(key: String): List<String> =
    (get(key) as? List<*>)?.mapNotNull { it as? String }.orEmpty()

private fun Map<String, Any?>.marketplaceMapList(key: String): List<Map<String, Any?>> =
    (get(key) as? List<*>)?.mapNotNull { it.asStringMap().takeIf(Map<*, *>::isNotEmpty) }.orEmpty()

private fun Map<String, Any?>.marketplaceTimestamp(key: String): Timestamp? = when (val value = get(key)) {
    is Timestamp -> value
    is Date -> Timestamp(value)
    is Number -> Timestamp(Date(value.toLong()))
    else -> null
}

private class MarketplaceConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
