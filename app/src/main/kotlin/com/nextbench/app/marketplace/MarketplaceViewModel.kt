package com.nextbench.app.marketplace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.MarketplaceCursor
import com.nextbench.data.firebase.MarketplaceRepository
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class MarketplaceViewer(
    val uid: String? = null,
    val verified: Boolean = false,
) {
    val signedIn: Boolean get() = !uid.isNullOrBlank()
}

enum class MarketplaceSort {
    Newest,
    PriceLow,
    PriceHigh,
}

enum class MarketplaceNoticeKind { Info, Success, Error }

@Immutable
data class MarketplaceNotice(
    val id: Long,
    val message: String,
    val kind: MarketplaceNoticeKind,
)

@Immutable
data class MarketplaceUiState(
    val products: List<Product> = emptyList(),
    val query: String = "",
    val category: String = AllCategory,
    val condition: String = AllCondition,
    val sort: MarketplaceSort = MarketplaceSort.Newest,
    val wishlistProductIds: Set<String> = emptySet(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingWishlist: Boolean = false,
    val interactionsReady: Boolean = true,
    val hasMore: Boolean = false,
    val initialError: String? = null,
    val paginationError: Boolean = false,
    val busyProductIds: Set<String> = emptySet(),
    val notice: MarketplaceNotice? = null,
) {
    val visibleProducts: List<Product>
        get() = filterAndSortProducts(products, query, category, condition, sort)

    val categories: List<String>
        get() = listOf(AllCategory) + products
            .asSequence()
            .map { it.category.trim() }
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
}

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val repository: MarketplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MarketplaceUiState())
    val state: StateFlow<MarketplaceUiState> = _state.asStateFlow()

    private var viewer = MarketplaceViewer()
    private var viewerSynced = false
    private var wishlistUid: String? = null
    private var cursor: MarketplaceCursor? = null
    private var contentGeneration = 0
    private var wishlistGeneration = 0
    private var noticeId = 0L
    private var pageJob: Job? = null
    private var wishlistJob: Job? = null
    private var wishlistDocumentIds: Map<String, String> = emptyMap()

    fun syncViewer(nextViewer: MarketplaceViewer) {
        val identityChanged = !viewerSynced || viewer.uid != nextViewer.uid
        viewer = nextViewer
        viewerSynced = true

        if (identityChanged) {
            resetWishlist(nextViewer.signedIn)
            loadFirstPage(clearContent = true)
        }
        if (nextViewer.signedIn && wishlistUid != nextViewer.uid) {
            loadWishlist(nextViewer.uid.orEmpty())
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun selectCategory(category: String) {
        _state.update { it.copy(category = category.ifBlank { AllCategory }) }
    }

    fun selectCondition(condition: String) {
        _state.update { it.copy(condition = condition.ifBlank { AllCondition }) }
    }

    fun selectSort(sort: MarketplaceSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun refresh() {
        loadFirstPage(clearContent = false)
    }

    fun retry() {
        loadFirstPage(clearContent = state.value.products.isEmpty())
    }

    fun loadMore() {
        val snapshot = state.value
        val pageCursor = cursor ?: return
        if (snapshot.isInitialLoading || snapshot.isRefreshing || snapshot.isLoadingMore || !snapshot.hasMore) return

        val generation = contentGeneration
        _state.update { it.copy(isLoadingMore = true, paginationError = false) }
        pageJob = viewModelScope.launch {
            repository.loadPage(pageCursor).fold(
                onSuccess = { page ->
                    if (generation != contentGeneration) return@fold
                    val cursorAdvanced = page.nextCursor != pageCursor
                    cursor = page.nextCursor
                    _state.update {
                        it.copy(
                            products = mergeProducts(it.products, page.products),
                            isLoadingMore = false,
                            hasMore = page.hasMore && (cursorAdvanced || page.products.isNotEmpty()),
                            paginationError = false,
                            initialError = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != contentGeneration) return@fold
                    _state.update { it.copy(isLoadingMore = false, paginationError = true) }
                    showNotice(error.marketplaceMessage(), MarketplaceNoticeKind.Error)
                },
            )
        }
    }

    fun toggleWishlist(productId: String): Boolean {
        val snapshot = state.value
        if (!viewer.signedIn || !viewer.verified || !snapshot.interactionsReady || productId in snapshot.busyProductIds) return false
        if (snapshot.products.none { it.id == productId }) return false

        val wasWishlisted = productId in snapshot.wishlistProductIds
        val willWishlist = !wasWishlisted
        val originalDocumentId = wishlistDocumentIds[productId]
        val operationUid = viewer.uid

        _state.update {
            it.copy(
                wishlistProductIds = it.wishlistProductIds.withMembership(productId, willWishlist),
                busyProductIds = it.busyProductIds + productId,
            )
        }

        viewModelScope.launch {
            repository.setWishlisted(productId, willWishlist, originalDocumentId).fold(
                onSuccess = { documentId ->
                    if (viewer.uid != operationUid) return@fold
                    wishlistDocumentIds = wishlistDocumentIds.withEntry(productId, documentId)
                    _state.update { it.copy(busyProductIds = it.busyProductIds - productId) }
                    showNotice(
                        if (willWishlist) "Saved to your wishlist" else "Removed from your wishlist",
                        if (willWishlist) MarketplaceNoticeKind.Success else MarketplaceNoticeKind.Info,
                    )
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid) return@fold
                    _state.update {
                        it.copy(
                            wishlistProductIds = it.wishlistProductIds.rollbackMembership(
                                id = productId,
                                optimistic = willWishlist,
                                original = wasWishlisted,
                            ),
                            busyProductIds = it.busyProductIds - productId,
                        )
                    }
                    showNotice(error.marketplaceMessage(), MarketplaceNoticeKind.Error)
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

    private fun loadFirstPage(clearContent: Boolean) {
        pageJob?.cancel()
        cursor = null
        val generation = ++contentGeneration
        val hasContent = !clearContent && state.value.products.isNotEmpty()
        _state.update {
            it.copy(
                products = if (clearContent) emptyList() else it.products,
                isInitialLoading = !hasContent,
                isRefreshing = hasContent,
                isLoadingMore = false,
                initialError = null,
                paginationError = false,
                hasMore = false,
            )
        }

        pageJob = viewModelScope.launch {
            repository.loadPage().fold(
                onSuccess = { page ->
                    if (generation != contentGeneration) return@fold
                    cursor = page.nextCursor
                    _state.update {
                        it.copy(
                            products = page.products.distinctBy(Product::id),
                            isInitialLoading = false,
                            isRefreshing = false,
                            hasMore = page.hasMore,
                            initialError = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != contentGeneration) return@fold
                    val message = error.marketplaceMessage()
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            hasMore = false,
                            initialError = message.takeUnless { hasContent },
                        )
                    }
                    if (hasContent) showNotice(message, MarketplaceNoticeKind.Error)
                },
            )
        }
    }

    private fun loadWishlist(uid: String) {
        wishlistJob?.cancel()
        val generation = ++wishlistGeneration
        _state.update { it.copy(isLoadingWishlist = true, interactionsReady = false) }
        wishlistJob = viewModelScope.launch {
            repository.loadWishlist(uid).fold(
                onSuccess = { interactions ->
                    if (viewer.uid != uid || generation != wishlistGeneration) return@fold
                    wishlistUid = uid
                    wishlistDocumentIds = interactions.documentIds
                    _state.update {
                        it.copy(
                            wishlistProductIds = interactions.productIds,
                            isLoadingWishlist = false,
                            interactionsReady = true,
                        )
                    }
                },
                onFailure = { error ->
                    if (viewer.uid != uid || generation != wishlistGeneration) return@fold
                    _state.update { it.copy(isLoadingWishlist = false, interactionsReady = false) }
                    showNotice(error.marketplaceMessage(), MarketplaceNoticeKind.Error)
                },
            )
        }
    }

    private fun resetWishlist(signedIn: Boolean) {
        wishlistJob?.cancel()
        wishlistGeneration++
        wishlistUid = null
        wishlistDocumentIds = emptyMap()
        _state.update {
            it.copy(
                wishlistProductIds = emptySet(),
                busyProductIds = emptySet(),
                isLoadingWishlist = false,
                interactionsReady = !signedIn,
            )
        }
    }

    private fun showNotice(message: String, kind: MarketplaceNoticeKind) {
        _state.update { it.copy(notice = MarketplaceNotice(++noticeId, message, kind)) }
    }
}

internal fun filterAndSortProducts(
    products: List<Product>,
    query: String,
    category: String,
    condition: String,
    sort: MarketplaceSort,
): List<Product> {
    val term = query.trim().lowercase()
    val selectedCategory = category.trim().lowercase()
    val selectedCondition = condition.trim().lowercase()
    return products
        .asSequence()
        .filter { product ->
            selectedCategory.isBlank() || selectedCategory == AllCategory.lowercase() ||
                product.category.trim().lowercase() == selectedCategory
        }
        .filter { product ->
            selectedCondition.isBlank() || selectedCondition == AllCondition.lowercase() ||
                product.condition.trim().lowercase() == selectedCondition
        }
        .filter { product ->
            term.isBlank() || listOf(
                product.title,
                product.category,
                product.condition,
                product.description,
                product.sellerName,
                product.sellerSchool,
                product.city.orEmpty(),
            ).any { it.lowercase().contains(term) }
        }
        .let { sequence ->
            when (sort) {
                MarketplaceSort.Newest -> sequence.sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }
                MarketplaceSort.PriceLow -> sequence.sortedWith(compareBy<Product> { it.price }.thenByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE })
                MarketplaceSort.PriceHigh -> sequence.sortedWith(compareByDescending<Product> { it.price }.thenByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE })
            }
        }
        .toList()
}

internal fun mergeProducts(current: List<Product>, incoming: List<Product>): List<Product> =
    (current + incoming).distinctBy(Product::id)

private fun Set<String>.withMembership(id: String, present: Boolean): Set<String> =
    if (present) this + id else this - id

private fun Set<String>.rollbackMembership(id: String, optimistic: Boolean, original: Boolean): Set<String> =
    if ((id in this) == optimistic) withMembership(id, original) else this

private fun Map<String, String>.withEntry(id: String, documentId: String?): Map<String, String> =
    if (documentId == null) this - id else this + (id to documentId)

private fun Throwable.marketplaceMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load listings."
        raw.contains("UNAVAILABLE", ignoreCase = true) || raw.contains("network", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "This listing is not available to your account."
        raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        else -> "Something went wrong. Please try again."
    }
}

const val AllCategory = "All"
const val AllCondition = "Any condition"
