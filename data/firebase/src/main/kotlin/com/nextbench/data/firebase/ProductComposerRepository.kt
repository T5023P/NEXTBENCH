package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.UserData
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class NewProductDraft(
    val title: String,
    val price: Long,
    val category: String,
    val condition: String,
    val description: String,
    val meetupAvailable: Boolean,
    val deliveryAvailable: Boolean,
    val images: List<File>,
)

data class ProductEditDraft(
    val title: String,
    val price: Long,
    val category: String,
    val condition: String,
    val description: String,
    val meetupAvailable: Boolean,
    val deliveryAvailable: Boolean,
    val retainedImageUrls: List<String>,
    val newImages: List<File>,
)

data class ProductUploadProgress(
    val completed: Int,
    val total: Int,
    val label: String,
) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

@Singleton
class ProductComposerRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val uploader: CloudinaryUploader,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    suspend fun publish(
        user: UserData,
        draft: NewProductDraft,
        onProgress: suspend (ProductUploadProgress) -> Unit = {},
    ): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(user.uid)
        validateDraft(user, draft)

        val uploads = draft.images.mapIndexed { index, file ->
            onProgress(ProductUploadProgress(index, draft.images.size, "Uploading photo ${index + 1} of ${draft.images.size}"))
            withContext(Dispatchers.IO) {
                uploader.upload(file, "nextbench/products/${user.uid}", CloudinaryResourceType.Image)
            }.also {
                onProgress(ProductUploadProgress(index + 1, draft.images.size, "Uploading photo ${index + 1} of ${draft.images.size}"))
            }
        }
        val productRef = refs.products.document()
        productRef.set(newProductPayload(user, draft, uploads)).await()
        productRef.id
    }

    suspend fun update(
        user: UserData,
        productId: String,
        draft: ProductEditDraft,
        onProgress: suspend (ProductUploadProgress) -> Unit = {},
    ): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(user.uid)
        validateDraft(user, draft)
        val uploads = draft.newImages.mapIndexed { index, file ->
            onProgress(ProductUploadProgress(index, draft.newImages.size, "Uploading photo ${index + 1} of ${draft.newImages.size}"))
            withContext(Dispatchers.IO) {
                uploader.upload(file, "nextbench/products/${user.uid}", CloudinaryResourceType.Image)
            }.also {
                onProgress(ProductUploadProgress(index + 1, draft.newImages.size, "Uploading photo ${index + 1} of ${draft.newImages.size}"))
            }
        }
        val imageUrls = draft.retainedImageUrls + uploads.map(CloudinaryResult::url)
        refs.product(productId).update(
            productUpdatePayload(user, draft, imageUrls),
        ).await()
        productId
    }

    private fun validateDraft(user: UserData, draft: NewProductDraft) {
        validateIdentity(user)
        require(draft.title.trim().length in 3..MaxTitleLength) { "Titles must be 3-$MaxTitleLength characters." }
        require(draft.price in 1..MaxPrice) { "Price must be between ₹1 and ₹100,000." }
        require(draft.category.trim().isNotEmpty() && draft.category.trim().length <= MaxCategoryLength) { "Choose a valid category." }
        require(draft.condition in Conditions) { "Choose a valid item condition." }
        require(draft.description.trim().length <= MaxDescriptionLength) { "Descriptions can be up to $MaxDescriptionLength characters." }
        require(draft.images.size in 1..MaxImages) { "Add between 1 and $MaxImages photos." }
        require(draft.images.all { it.isFile && it.length() > 0L }) { "One of the selected images is unavailable." }
    }

    private fun validateDraft(user: UserData, draft: ProductEditDraft) {
        validateIdentity(user)
        require(draft.title.trim().length in 3..MaxTitleLength) { "Titles must be 3-$MaxTitleLength characters." }
        require(draft.price in 1..MaxPrice) { "Price must be between ₹1 and ₹100,000." }
        require(draft.category.trim().isNotEmpty() && draft.category.trim().length <= MaxCategoryLength) { "Choose a valid category." }
        require(draft.condition in Conditions) { "Choose a valid item condition." }
        require(draft.description.trim().length <= MaxDescriptionLength) { "Descriptions can be up to $MaxDescriptionLength characters." }
        require(draft.retainedImageUrls.size + draft.newImages.size in 1..MaxImages) { "Add between 1 and $MaxImages photos." }
        require(draft.retainedImageUrls.all { it.startsWith("http://") || it.startsWith("https://") }) { "One of the retained images is unavailable." }
        require(draft.newImages.all { it.isFile && it.length() > 0L }) { "One of the selected images is unavailable." }
    }

    private fun validateIdentity(user: UserData) {
        require(user.verified) { "Your account must be verified before listing an item." }
        require(user.name.isNotBlank() && user.name.trim().length <= 100) { "Add your name before listing an item." }
        require(user.school.isNotBlank() && user.school.trim().length <= 100) { "Add your school before listing an item." }
        require(user.city.isBlank() || user.city.trim().length <= 100) { "Your city name is too long." }
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) { "Your session expired. Sign in and try again." }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ProductComposerConfigurationException()
    }

    companion object {
        const val MaxImages = 5
        const val MaxTitleLength = 100
        const val MaxCategoryLength = 50
        const val MaxDescriptionLength = 2_000
        const val MaxPrice = 100_000L
        val Conditions = listOf("Brand New", "Like New", "Good", "Used")
    }
}

internal fun productUpdatePayload(
    user: UserData,
    draft: ProductEditDraft,
    imageUrls: List<String>,
): Map<String, Any?> = buildMap {
    put("title", draft.title.trim())
    put("price", draft.price)
    put("condition", draft.condition)
    put("category", draft.category.trim())
    put("image", imageUrls.firstOrNull().orEmpty())
    put("images", imageUrls)
    put("description", draft.description.trim())
    put("meetupAvailable", draft.meetupAvailable)
    put("deliveryAvailable", draft.deliveryAvailable)
    user.city.trim().takeIf(String::isNotBlank)?.let { put("city", it) }
    put("updatedAt", FieldValue.serverTimestamp())
}

internal fun newProductPayload(
    user: UserData,
    draft: NewProductDraft,
    uploads: List<CloudinaryResult>,
): Map<String, Any?> {
    val images = uploads.map(CloudinaryResult::url)
    val detailedImages = uploads.map { upload -> mapOf("url" to upload.url, "w" to upload.width, "h" to upload.height) }
    return buildMap {
        put("sellerId", user.uid)
        put("sellerName", user.name.ifBlank { "Student" })
        put("sellerSchool", user.school.trim())
        put("sellerProfilePicture", user.profilePicture)
        put("school", user.school.trim())
        user.city.trim().takeIf(String::isNotBlank)?.let { put("city", it) }
        put("title", draft.title.trim())
        put("price", draft.price)
        put("condition", draft.condition)
        put("category", draft.category.trim())
        put("image", images.firstOrNull().orEmpty())
        put("images", images)
        put("imageWidth", uploads.firstOrNull()?.width)
        put("imageHeight", uploads.firstOrNull()?.height)
        put("imagesDetailed", detailedImages)
        put("description", draft.description.trim())
        put("meetupAvailable", draft.meetupAvailable)
        put("deliveryAvailable", draft.deliveryAvailable)
        put("status", ProductStatus.Pending.raw)
        put("wishlistCount", 0)
        put("inquiryCount", 0)
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }
}

private class ProductComposerConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
