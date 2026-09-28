package com.nextbench.app.marketplace

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.NewProductDraft
import com.nextbench.data.firebase.ProductComposerRepository
import com.nextbench.data.firebase.ProductDetailRepository
import com.nextbench.data.firebase.ProductEditDraft
import com.nextbench.data.firebase.ProductUploadProgress
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ProductComposerImage(
    val id: String,
    val uri: Uri? = null,
    val file: File? = null,
    val remoteUrl: String? = null,
)

@Immutable
data class ProductComposerState(
    val title: String = "",
    val price: String = "",
    val category: String = ProductComposerCategories.first(),
    val condition: String = ProductComposerRepository.Conditions[1],
    val description: String = "",
    val meetupAvailable: Boolean = true,
    val deliveryAvailable: Boolean = false,
    val images: List<ProductComposerImage> = emptyList(),
    val isPreparingMedia: Boolean = false,
    val isPublishing: Boolean = false,
    val progress: ProductUploadProgress? = null,
    val error: String? = null,
    val isLoading: Boolean = false,
    val editingProductId: String? = null,
    val publishedProductId: String? = null,
) {
    val hasDraft: Boolean get() = title.isNotBlank() || price.isNotBlank() || description.isNotBlank() || images.isNotEmpty()
    val parsedPrice: Long? get() = price.trim().toLongOrNull()
    val canPublish: Boolean
        get() = canPublishProductDraft(
            title = title,
            price = parsedPrice,
            category = category,
            condition = condition,
            descriptionLength = description.length,
            imageCount = images.size,
            isPreparingMedia = isPreparingMedia,
            isPublishing = isPublishing,
        )
}

internal fun canPublishProductDraft(
    title: String,
    price: Long?,
    category: String,
    condition: String,
    descriptionLength: Int,
    imageCount: Int,
    isPreparingMedia: Boolean = false,
    isPublishing: Boolean = false,
): Boolean =
    !isPreparingMedia &&
        !isPublishing &&
        imageCount > 0 &&
        title.trim().length in 3..ProductComposerRepository.MaxTitleLength &&
        price in 1..ProductComposerRepository.MaxPrice &&
        category.isNotBlank() &&
        condition in ProductComposerRepository.Conditions &&
        descriptionLength <= ProductComposerRepository.MaxDescriptionLength

val ProductComposerCategories = listOf(
    "Books",
    "JEE/NEET Modules",
    "Notes",
    "Electronics",
    "Uniforms",
    "Sports Gear",
    "Hostel Essentials",
    "Cycles",
    "Miscellaneous",
)

@HiltViewModel
class ProductComposerViewModel @Inject constructor(
    private val repository: ProductComposerRepository,
    private val mediaStore: ProductMediaStore,
    private val detailRepository: ProductDetailRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductComposerState())
    val state: StateFlow<ProductComposerState> = _state.asStateFlow()

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(ProductComposerRepository.MaxTitleLength), error = null) }
    fun setPrice(value: String) = _state.update { it.copy(price = value.filter(Char::isDigit).take(6), error = null) }
    fun setCategory(value: String) = _state.update { it.copy(category = value, error = null) }
    fun setCondition(value: String) = _state.update { it.copy(condition = value, error = null) }
    fun setDescription(value: String) = _state.update { it.copy(description = value.take(ProductComposerRepository.MaxDescriptionLength), error = null) }
    fun setMeetup(value: Boolean) = _state.update { it.copy(meetupAvailable = value, error = null) }
    fun setDelivery(value: Boolean) = _state.update { it.copy(deliveryAvailable = value, error = null) }

    fun loadForEdit(productId: String, user: UserData?) {
        if (productId.isBlank() || state.value.isLoading || state.value.editingProductId == productId) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            detailRepository.loadProduct(productId).fold(
                onSuccess = { product ->
                    if (user == null || product.sellerId != user.uid) {
                        _state.update { it.copy(isLoading = false, error = "You can only edit your own listing.") }
                    } else if (ProductStatus.from(product.status) !in setOf(ProductStatus.Pending, ProductStatus.Available)) {
                        _state.update { it.copy(isLoading = false, error = "This listing cannot be edited right now.") }
                    } else {
                        applyProduct(product)
                    }
                },
                onFailure = { error -> _state.update { it.copy(isLoading = false, error = error.productComposerMessage()) } },
            )
        }
    }

    private fun applyProduct(product: Product) {
        val urls = product.images.filter(String::isNotBlank).ifEmpty { listOfNotNull(product.image?.takeIf(String::isNotBlank)) }
        _state.update {
            it.copy(
                title = product.title,
                price = product.price.toString(),
                category = product.category.ifBlank { ProductComposerCategories.first() },
                condition = product.condition.ifBlank { ProductComposerRepository.Conditions[1] },
                description = product.description,
                meetupAvailable = product.meetupAvailable,
                deliveryAvailable = product.deliveryAvailable,
                images = urls.mapIndexed { index, url -> ProductComposerImage("remote_$index", remoteUrl = url) },
                isLoading = false,
                editingProductId = product.id,
                error = null,
            )
        }
    }

    fun prepareImages(uris: List<Uri>) {
        val remaining = ProductComposerRepository.MaxImages - state.value.images.size
        if (remaining <= 0 || state.value.isPreparingMedia || state.value.isPublishing) return
        val selected = uris.take(remaining)
        if (selected.isEmpty()) return
        _state.update { it.copy(isPreparingMedia = true, error = null) }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) { selected.map(mediaStore::prepare) }
            val failure = prepared.firstOrNull(Result<PreparedProductImage>::isFailure)?.exceptionOrNull()
            if (failure != null) {
                prepared.mapNotNull(Result<PreparedProductImage>::getOrNull).forEach { it.file.delete() }
                _state.update { it.copy(isPreparingMedia = false, error = failure.productComposerMessage()) }
                return@launch
            }
            val images = prepared.mapNotNull(Result<PreparedProductImage>::getOrNull).map { image ->
                ProductComposerImage(image.file.name, image.uri, image.file)
            }
            _state.update { it.copy(images = it.images + images, isPreparingMedia = false, error = null) }
        }
    }

    fun removeImage(id: String) {
        if (state.value.isPublishing) return
        state.value.images.firstOrNull { it.id == id }?.file?.delete()
        _state.update { it.copy(images = it.images.filterNot { image -> image.id == id }, error = null) }
    }

    fun publish(user: UserData) {
        val snapshot = state.value
        if (!snapshot.canPublish) return
        _state.update { it.copy(isPublishing = true, progress = null, error = null) }
        viewModelScope.launch {
            val result = if (snapshot.editingProductId != null) {
                repository.update(
                    user = user,
                    productId = snapshot.editingProductId,
                    draft = ProductEditDraft(
                        title = snapshot.title,
                        price = requireNotNull(snapshot.parsedPrice),
                        category = snapshot.category,
                        condition = snapshot.condition,
                        description = snapshot.description,
                        meetupAvailable = snapshot.meetupAvailable,
                        deliveryAvailable = snapshot.deliveryAvailable,
                        retainedImageUrls = snapshot.images.mapNotNull(ProductComposerImage::remoteUrl),
                        newImages = snapshot.images.mapNotNull(ProductComposerImage::file),
                    ),
                    onProgress = { progress -> _state.update { it.copy(progress = progress) } },
                )
            } else {
                repository.publish(
                    user = user,
                    draft = NewProductDraft(
                        title = snapshot.title,
                        price = requireNotNull(snapshot.parsedPrice),
                        category = snapshot.category,
                        condition = snapshot.condition,
                        description = snapshot.description,
                        meetupAvailable = snapshot.meetupAvailable,
                        deliveryAvailable = snapshot.deliveryAvailable,
                        images = snapshot.images.mapNotNull(ProductComposerImage::file),
                    ),
                    onProgress = { progress -> _state.update { it.copy(progress = progress) } },
                )
            }
            result.fold(
                onSuccess = { productId ->
                    snapshot.images.forEach { it.file?.delete() }
                    _state.value = ProductComposerState(publishedProductId = productId)
                },
                onFailure = { error -> _state.update { it.copy(isPublishing = false, progress = null, error = error.productComposerMessage()) } },
            )
        }
    }

    fun consumePublishedProduct() = _state.update { it.copy(publishedProductId = null) }

    fun discardDraft() {
        if (state.value.isPublishing) return
        state.value.images.forEach { it.file?.delete() }
        _state.value = ProductComposerState()
    }

    override fun onCleared() {
        state.value.images.forEach { it.file?.delete() }
        super.onCleared()
    }
}

internal fun Throwable.productComposerMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("Cloudinary", ignoreCase = true) -> "Image uploads are not configured for this build."
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to publish."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) -> "Your session expired. Sign in and try again."
        raw.isNotBlank() -> raw
        else -> "Your listing could not be published. Please try again."
    }
}
