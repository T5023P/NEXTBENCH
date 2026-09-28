package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.nextbench.data.model.Product
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class SavedListing(
    val wishlistId: String,
    val product: Product,
    val savedAt: Timestamp?,
)

internal data class WishlistReference(
    val id: String,
    val productId: String,
    val savedAt: Timestamp?,
)

@Singleton
class WishlistRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSavedListings(uid: String): Flow<List<SavedListing>> = configuredFlow(uid) {
        refs.wishlists
            .whereEqualTo("userId", uid)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull(DocumentSnapshot::toWishlistReference) }
            .flatMapLatest { wishlist ->
                if (wishlist.isEmpty()) return@flatMapLatest flowOf(emptyList())
                val references = wishlist.associateBy(WishlistReference::productId)
                val productFlows = references.keys.chunked(FirestoreWhereInLimit).map { ids ->
                    refs.products
                        .whereIn(FieldPath.documentId(), ids)
                        .snapshotFlow()
                }
                combine(productFlows) { snapshots ->
                    snapshots
                        .flatMap { it.documents }
                        .mapNotNull(DocumentSnapshot::toMarketplaceProduct)
                        .mapNotNull { product ->
                            references[product.id]?.let { reference ->
                                SavedListing(reference.id, product, reference.savedAt)
                            }
                        }
                        .sortedByDescending { it.savedAt?.toDate()?.time ?: Long.MIN_VALUE }
                }
            }
    }

    suspend fun remove(wishlistId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val reference = refs.wishlists.document(wishlistId)
        val snapshot = reference.get().await()
        require(snapshot.getString("userId") == uid) { "This saved listing is no longer available." }
        reference.delete().await()
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) { "Your session expired. Sign in and try again." }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw WishlistConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }

    companion object {
        internal const val FirestoreWhereInLimit = 30
    }
}

internal fun DocumentSnapshot.toWishlistReference(): WishlistReference? {
    if (!exists()) return null
    return data?.toWishlistReference(id)
}

internal fun Map<String, Any?>.toWishlistReference(id: String): WishlistReference? {
    val productId = get("productId")?.toString()?.takeIf(String::isNotBlank) ?: return null
    return WishlistReference(
        id = id,
        productId = productId,
        savedAt = get("createdAt") as? Timestamp,
    )
}

private class WishlistConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
