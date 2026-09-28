package com.nextbench.app.marketplace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ProductDetailRepository
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.Review
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProductDetailNoticeKind { Info, Success, Error }

@Immutable
data class ProductDetailNotice(
    val id: Long,
    val message: String,
    val kind: ProductDetailNoticeKind,
)

@Immutable
data class ProductActionPolicy(
    val isSeller: Boolean = false,
    val reservedByViewer: Boolean = false,
    val canWishlist: Boolean = false,
    val canReserve: Boolean = false,
    val canCancelReservation: Boolean = false,
    val canMarkSold: Boolean = false,
    val canContactSeller: Boolean = false,
    val canEdit: Boolean = false,
    val canReview: Boolean = false,
)

@Immutable
data class ProductDetailUiState(
    val product: Product? = null,
    val reviews: List<Review> = emptyList(),
    val wishlisted: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingReviews: Boolean = false,
    val isLoadingWishlist: Boolean = false,
    val isMutating: Boolean = false,
    val isContacting: Boolean = false,
    val isSubmittingReview: Boolean = false,
    val interactionsReady: Boolean = false,
    val initialError: String? = null,
    val reviewsError: String? = null,
    val notice: ProductDetailNotice? = null,
    val pendingRoomId: String? = null,
) {
    fun policy(user: UserData?): ProductActionPolicy = productActionPolicy(product, user)
}

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProductDetailRepository,
) : ViewModel() {
    private val productId: String = requireNotNull(savedStateHandle["productId"]) {
        "Product detail requires a productId navigation argument."
    }
    private val _state = MutableStateFlow(ProductDetailUiState())
    val state: StateFlow<ProductDetailUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var viewerSynced = false
    private var wishlistDocumentId: String? = null
    private var loadGeneration = 0
    private var ancillaryGeneration = 0
    private var noticeId = 0L
    private var loadJob: Job? = null
    private var ancillaryJob: Job? = null

    init {
        loadProduct(clearContent = true)
    }

    fun syncViewer(user: UserData?) {
        if (viewerSynced && viewer == user) return
        val identityChanged = !viewerSynced || viewer?.uid != user?.uid
        viewer = user
        viewerSynced = true
        if (!identityChanged) return

        wishlistDocumentId = null
        _state.update {
            it.copy(
                wishlisted = false,
                isLoadingWishlist = user != null,
                interactionsReady = false,
            )
        }
        if (user != null) loadAncillary(user.uid) else ancillaryJob?.cancel()
    }

    fun refresh() {
        loadProduct(clearContent = false)
        viewer?.uid?.let(::loadAncillary)
    }

    fun retry() = loadProduct(clearContent = true)

    fun retryReviews() {
        viewer?.uid?.let(::loadAncillary)
    }

    fun toggleWishlist(): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        val product = snapshot.product ?: return false
        if (!snapshot.policy(user).canWishlist || !snapshot.interactionsReady || snapshot.isMutating) return false
        val original = snapshot.wishlisted
        val desired = !original
        val originalDocumentId = wishlistDocumentId
        _state.update { it.copy(wishlisted = desired, isMutating = true) }
        viewModelScope.launch {
            repository.setWishlisted(user.uid, product.id, desired, originalDocumentId).fold(
                onSuccess = { documentId ->
                    wishlistDocumentId = documentId
                    _state.update { it.copy(isMutating = false) }
                    showNotice(
                        if (desired) "Saved to your wishlist" else "Removed from your wishlist",
                        if (desired) ProductDetailNoticeKind.Success else ProductDetailNoticeKind.Info,
                    )
                },
                onFailure = { error ->
                    _state.update { current ->
                        current.copy(
                            wishlisted = if (current.wishlisted == desired) original else current.wishlisted,
                            isMutating = false,
                        )
                    }
                    showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun reserve(): Boolean = mutateProduct(
        allowed = { policy -> policy.canReserve },
        successMessage = "Item reserved. Message the seller to arrange the exchange.",
    ) { product, user -> repository.reserve(product.id, user.uid) }

    fun cancelReservation(): Boolean = mutateProduct(
        allowed = { policy -> policy.canCancelReservation },
        successMessage = "Reservation cancelled",
    ) { product, user -> repository.releaseReservation(product.id, user.uid) }

    fun markSold(): Boolean = mutateProduct(
        allowed = { policy -> policy.canMarkSold },
        successMessage = "Listing marked as sold",
    ) { product, user -> repository.markSold(product.id, user.uid) }

    fun contactSeller(): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        val product = snapshot.product ?: return false
        if (!snapshot.policy(user).canContactSeller || snapshot.isContacting) return false
        _state.update { it.copy(isContacting = true) }
        viewModelScope.launch {
            repository.contactSeller(product, user.uid).fold(
                onSuccess = { roomId ->
                    _state.update { it.copy(isContacting = false, pendingRoomId = roomId) }
                },
                onFailure = { error ->
                    _state.update { it.copy(isContacting = false) }
                    showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun consumeRoom(roomId: String) {
        _state.update { current ->
            if (current.pendingRoomId == roomId) current.copy(pendingRoomId = null) else current
        }
    }

    fun submitReview(rating: Int, comment: String): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        val product = snapshot.product ?: return false
        if (!snapshot.policy(user).canReview || snapshot.isSubmittingReview) return false
        _state.update { it.copy(isSubmittingReview = true) }
        viewModelScope.launch {
            repository.createReview(product.id, rating, comment).fold(
                onSuccess = {
                    _state.update { it.copy(isSubmittingReview = false) }
                    showNotice("Review submitted", ProductDetailNoticeKind.Success)
                    loadReviews()
                },
                onFailure = { error ->
                    _state.update { it.copy(isSubmittingReview = false) }
                    showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun dismissNotice(id: Long) {
        _state.update { current ->
            if (current.notice?.id == id) current.copy(notice = null) else current
        }
    }

    private fun mutateProduct(
        allowed: (ProductActionPolicy) -> Boolean,
        successMessage: String,
        operation: suspend (Product, UserData) -> Result<Product>,
    ): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        val product = snapshot.product ?: return false
        if (!allowed(snapshot.policy(user)) || snapshot.isMutating) return false
        _state.update { it.copy(isMutating = true) }
        viewModelScope.launch {
            operation(product, user).fold(
                onSuccess = { updated ->
                    _state.update { it.copy(product = updated, isMutating = false) }
                    showNotice(successMessage, ProductDetailNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isMutating = false) }
                    showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    private fun loadProduct(clearContent: Boolean) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        val hasContent = !clearContent && state.value.product != null
        _state.update {
            it.copy(
                product = if (clearContent) null else it.product,
                isInitialLoading = !hasContent,
                isRefreshing = hasContent,
                initialError = null,
            )
        }
        loadJob = viewModelScope.launch {
            repository.loadProduct(productId).fold(
                onSuccess = { product ->
                    if (generation != loadGeneration) return@fold
                    _state.update {
                        it.copy(
                            product = product,
                            isInitialLoading = false,
                            isRefreshing = false,
                            initialError = null,
                        )
                    }
                    viewer?.uid?.let(::loadAncillary)
                },
                onFailure = { error ->
                    if (generation != loadGeneration) return@fold
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            initialError = error.productDetailMessage().takeUnless { hasContent },
                        )
                    }
                    if (hasContent) showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
        }
    }

    private fun loadAncillary(uid: String) {
        ancillaryJob?.cancel()
        val generation = ++ancillaryGeneration
        _state.update {
            it.copy(
                isLoadingReviews = true,
                isLoadingWishlist = true,
                reviewsError = null,
                interactionsReady = false,
            )
        }
        ancillaryJob = viewModelScope.launch {
            val wishlist = repository.wishlistDocument(uid, productId)
            if (viewer?.uid != uid || generation != ancillaryGeneration) return@launch
            wishlist.fold(
                onSuccess = { documentId ->
                    wishlistDocumentId = documentId
                    _state.update {
                        it.copy(
                            wishlisted = documentId != null,
                            isLoadingWishlist = false,
                            interactionsReady = true,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(isLoadingWishlist = false, interactionsReady = false) }
                    showNotice(error.productDetailMessage(), ProductDetailNoticeKind.Error)
                },
            )
            loadReviews(generation, uid)
        }
    }

    private fun loadReviews() {
        val uid = viewer?.uid ?: return
        viewModelScope.launch { loadReviews(ancillaryGeneration, uid) }
    }

    private suspend fun loadReviews(generation: Int, uid: String) {
        _state.update { it.copy(isLoadingReviews = true, reviewsError = null) }
        repository.loadReviews(productId).fold(
            onSuccess = { reviews ->
                if (viewer?.uid != uid || generation != ancillaryGeneration) return@fold
                _state.update { it.copy(reviews = reviews, isLoadingReviews = false, reviewsError = null) }
            },
            onFailure = { error ->
                if (viewer?.uid != uid || generation != ancillaryGeneration) return@fold
                _state.update {
                    it.copy(isLoadingReviews = false, reviewsError = error.productDetailMessage())
                }
            },
        )
    }

    private fun showNotice(message: String, kind: ProductDetailNoticeKind) {
        _state.update { it.copy(notice = ProductDetailNotice(++noticeId, message, kind)) }
    }
}

internal fun productActionPolicy(product: Product?, user: UserData?): ProductActionPolicy {
    if (product == null || user == null || user.uid.isBlank()) return ProductActionPolicy()
    val status = ProductStatus.from(product.status)
    val isSeller = user.uid == product.sellerId
    val reservedByViewer = user.uid == product.reservedById
    val verifiedBuyer = user.verified && !isSeller
    return ProductActionPolicy(
        isSeller = isSeller,
        reservedByViewer = reservedByViewer,
        canWishlist = user.verified && !isSeller && status != ProductStatus.Pending && status != ProductStatus.Rejected,
        canReserve = verifiedBuyer && status == ProductStatus.Available,
        canCancelReservation = user.verified && status == ProductStatus.Reserved && (isSeller || reservedByViewer),
        canMarkSold = user.verified && isSeller && status == ProductStatus.Reserved,
        canContactSeller = verifiedBuyer && status != ProductStatus.Pending && status != ProductStatus.Rejected,
        canEdit = user.verified && isSeller && status in setOf(ProductStatus.Pending, ProductStatus.Available),
        canReview = verifiedBuyer && reservedByViewer && status == ProductStatus.Sold,
    )
}

internal fun Throwable.productDetailMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load this listing."
        raw.contains("UNAVAILABLE", ignoreCase = true) || raw.contains("network", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("PERMISSION_DENIED", ignoreCase = true) || raw.contains("unavailable", ignoreCase = true) ->
            "This listing is not available to your account."
        raw.contains("UNAUTHENTICATED", ignoreCase = true) || raw.contains("session expired", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.contains("NOT_FOUND", ignoreCase = true) || raw.contains("no longer available", ignoreCase = true) ->
            "This listing is no longer available."
        raw.contains("already", ignoreCase = true) || raw.contains("cannot", ignoreCase = true) ||
            raw.contains("only", ignoreCase = true) || raw.contains("Choose", ignoreCase = true) ||
            raw.contains("up to", ignoreCase = true) || raw.contains("review", ignoreCase = true) -> raw
        else -> "Something went wrong. Please try again."
    }
}
