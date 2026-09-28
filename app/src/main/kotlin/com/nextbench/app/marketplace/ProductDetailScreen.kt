package com.nextbench.app.marketplace

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbOutlinedButton
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonBox
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.Review
import com.nextbench.data.model.UserData
import kotlinx.coroutines.delay

private enum class ProductAccessRequest { SignIn, Verify }

internal const val ProductDetailPreviewRoute = "marketplace-preview-product"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProductDetailScreen(
    user: UserData?,
    onOpenProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onEdit: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var accessRequest by remember { mutableStateOf<ProductAccessRequest?>(null) }
    var showReviewSheet by rememberSaveable { mutableStateOf(false) }
    var reviewRating by rememberSaveable { mutableStateOf(5) }
    var reviewComment by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(user?.uid, user?.verified) { viewModel.syncViewer(user) }
    LaunchedEffect(state.pendingRoomId) {
        state.pendingRoomId?.let { roomId ->
            viewModel.consumeRoom(roomId)
            onOpenChat(roomId)
        }
    }

    ProductDetailContent(
        state = state,
        user = user,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onRetryReviews = viewModel::retryReviews,
        onOpenProfile = onOpenProfile,
        onShare = { product -> shareProduct(context, product) },
        onWishlist = {
            when {
                user == null -> accessRequest = ProductAccessRequest.SignIn
                !user.verified -> accessRequest = ProductAccessRequest.Verify
                else -> viewModel.toggleWishlist()
            }
        },
        onReserve = {
            when {
                user == null -> accessRequest = ProductAccessRequest.SignIn
                !user.verified -> accessRequest = ProductAccessRequest.Verify
                else -> viewModel.reserve()
            }
        },
        onContact = {
            when {
                user == null -> accessRequest = ProductAccessRequest.SignIn
                !user.verified -> accessRequest = ProductAccessRequest.Verify
                else -> viewModel.contactSeller()
            }
        },
        onCancelReservation = { viewModel.cancelReservation(); Unit },
        onMarkSold = { viewModel.markSold(); Unit },
        onEdit = onEdit,
        onReview = { showReviewSheet = true },
        modifier = modifier,
    )

    state.notice?.let { notice ->
        ProductDetailToast(
            notice = notice,
            onDismiss = { viewModel.dismissNotice(notice.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    accessRequest?.let { request ->
        ProductAccessSheet(
            request = request,
            onDismiss = { accessRequest = null },
            onContinue = {
                accessRequest = null
                if (request == ProductAccessRequest.SignIn) onSignIn() else onVerify()
            },
        )
    }

    if (showReviewSheet) {
        ReviewSheet(
            rating = reviewRating,
            comment = reviewComment,
            submitting = state.isSubmittingReview,
            onRatingChanged = { reviewRating = it },
            onCommentChanged = { reviewComment = it.take(ProductDetailRepositoryReviewLimit) },
            onDismiss = { if (!state.isSubmittingReview) showReviewSheet = false },
            onSubmit = {
                if (viewModel.submitReview(reviewRating, reviewComment)) {
                    showReviewSheet = false
                    reviewComment = ""
                    reviewRating = 5
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ProductDetailPreviewScreen(modifier: Modifier = Modifier) {
    var state by remember {
        mutableStateOf(
            ProductDetailUiState(
                product = PreviewDetailProduct,
                reviews = PreviewReviews,
                wishlisted = false,
                isInitialLoading = false,
                interactionsReady = true,
            ),
        )
    }
    val user = remember { UserData(uid = "preview-buyer", name = "Aarav", verified = true) }

    ProductDetailContent(
        state = state,
        user = user,
        onRefresh = {},
        onRetry = {},
        onRetryReviews = {},
        onOpenProfile = {},
        onShare = {},
        onWishlist = { state = state.copy(wishlisted = !state.wishlisted) },
        onReserve = {
            state = state.copy(
                product = state.product?.copy(status = ProductStatus.Reserved.raw, reservedById = user.uid),
            )
        },
        onContact = {},
        onCancelReservation = {
            state = state.copy(
                product = state.product?.copy(status = ProductStatus.Available.raw, reservedById = null),
            )
        },
        onMarkSold = {
            state = state.copy(product = state.product?.copy(status = ProductStatus.Sold.raw))
        },
        onEdit = {},
        onReview = {},
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProductDetailContent(
    state: ProductDetailUiState,
    user: UserData?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onRetryReviews: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onShare: (Product) -> Unit,
    onWishlist: () -> Unit,
    onReserve: () -> Unit,
    onContact: () -> Unit,
    onCancelReservation: () -> Unit,
    onMarkSold: () -> Unit,
    onEdit: (String) -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val product = state.product
    val policy = state.policy(user)
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isInitialLoading -> ProductDetailLoading()
                    state.initialError != null && product == null -> ProductDetailError(state.initialError, onRetry)
                    product == null -> ProductDetailError("This listing is no longer available.", onRetry)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NbDimens.space24),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space20),
                    ) {
                        item(key = "gallery") {
                            ProductGallery(product = product)
                        }
                        item(key = "overview") {
                            ProductOverview(
                                product = product,
                                onShare = { onShare(product) },
                            )
                        }
                        item(key = "exchange") {
                            ExchangeDetails(product = product)
                        }
                        item(key = "seller") {
                            SellerCard(product = product, onOpen = { onOpenProfile(product.sellerId) })
                        }
                        item(key = "reviews_header") {
                            ReviewsHeader(
                                count = state.reviews.size,
                                loading = state.isLoadingReviews,
                            )
                        }
                        when {
                            state.reviewsError != null && state.reviews.isEmpty() -> item(key = "reviews_error") {
                                ReviewsError(message = state.reviewsError, onRetry = onRetryReviews)
                            }
                            state.isLoadingReviews && state.reviews.isEmpty() -> items(2, key = { "review_skeleton_$it" }) {
                                ReviewSkeleton()
                            }
                            state.reviews.isEmpty() -> item(key = "reviews_empty") {
                                Text(
                                    text = "No reviews yet. Completed exchanges help build trust here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NbTheme.colors.inkMuted,
                                    modifier = Modifier.padding(horizontal = NbDimens.space16),
                                )
                            }
                            else -> items(state.reviews, key = Review::id) { review ->
                                ReviewCard(review = review)
                            }
                        }
                    }
                }
            }
        }

        if (product != null) {
            ProductActionBar(
                product = product,
                policy = policy,
                wishlisted = state.wishlisted,
                busy = state.isMutating || state.isContacting,
                onWishlist = onWishlist,
                onReserve = onReserve,
                onContact = onContact,
                onCancelReservation = onCancelReservation,
                onMarkSold = onMarkSold,
                onEdit = { onEdit(product.id) },
                onReview = onReview,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductGallery(product: Product) {
    val images = remember(product.image, product.images) {
        (product.images.ifEmpty { listOfNotNull(product.image) }).filter(String::isNotBlank).distinct()
    }
    val pagerState = rememberPagerState(pageCount = { images.size.coerceAtLeast(1) })
    val context = LocalContext.current
    val status = ProductStatus.from(product.status)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.06f)
            .background(NbTheme.colors.surfaceSoft),
    ) {
        if (images.isEmpty()) {
            ProductImagePlaceholder()
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = images.size > 1,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val request = remember(images[page]) {
                    ImageRequest.Builder(context)
                        .data(images[page])
                        .crossfade(300)
                        .build()
                }
                var imageLoaded by remember(images[page]) { mutableStateOf(false) }
                var imageFailed by remember(images[page]) { mutableStateOf(false) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!imageLoaded) {
                        ProductImagePlaceholder(
                            message = if (imageFailed) "Photo unavailable" else "Loading photo",
                        )
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = "${product.title} image ${page + 1}",
                        contentScale = ContentScale.Fit,
                        onSuccess = {
                            imageLoaded = true
                            imageFailed = false
                        },
                        onError = {
                            imageLoaded = false
                            imageFailed = true
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(NbDimens.space8),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
                    )
                }
            }
        }

        if (status == ProductStatus.Sold) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                NbPill(label = "Sold", contentColor = Color.White, containerColor = Color.Black.copy(alpha = 0.68f))
            }
        } else if (status == ProductStatus.Reserved) {
            NbPill(
                label = "Reserved",
                contentColor = Color.White,
                containerColor = NbTheme.colors.brandPink,
                modifier = Modifier.align(Alignment.TopStart).padding(NbDimens.space16),
            )
        }

        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NbDimens.space12),
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
            ) {
                repeat(images.size.coerceAtMost(7)) { index ->
                    Spacer(
                        Modifier
                            .width(if (index == pagerState.currentPage) 18.dp else 6.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (index == pagerState.currentPage) 1f else 0.55f)),
                    )
                }
            }
            Text(
                text = "${pagerState.currentPage + 1}/${images.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(NbDimens.space12)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
            )
        }
    }
}

@Composable
private fun ProductImagePlaceholder(message: String = "No photos yet") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NbIcons.Marketplace, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(NbDimens.space8))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun ProductOverview(product: Product, onShare: () -> Unit) {
    val status = ProductStatus.from(product.status)
    Column(
        modifier = Modifier.padding(horizontal = NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            NbPill(label = product.condition.ifBlank { "Used" }, contentColor = NbTheme.colors.brandTeal)
            Text(
                text = product.category.ifBlank { "Marketplace" },
                style = MaterialTheme.typography.labelMedium,
                color = NbTheme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onShare,
                modifier = Modifier.semantics { contentDescription = "Share listing" },
            ) {
                Icon(NbIcons.Share, contentDescription = null, tint = NbTheme.colors.inkMuted)
            }
        }
        Text(
            text = product.title.ifBlank { "Untitled listing" },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = NbTheme.colors.ink,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "\u20B9${product.price}",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = NbTheme.colors.brandPink,
                modifier = Modifier.weight(1f),
            )
            if (status == ProductStatus.Available) {
                Text("Available now", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.brandMint)
            }
        }
        Text(
            text = product.description.ifBlank { "The seller has not added a description yet." },
            style = MaterialTheme.typography.bodyLarge,
            color = NbTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun ExchangeDetails(product: Product) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusLg),
        modifier = Modifier
            .padding(horizontal = NbDimens.space16)
            .fillMaxWidth()
            .border(1.dp, NbTheme.colors.border, RoundedCornerShape(NbDimens.radiusLg)),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            ExchangeDetail(
                icon = NbIcons.Building,
                label = "Meetup",
                value = if (product.meetupAvailable) product.city?.takeIf(String::isNotBlank) ?: "Campus" else "Unavailable",
                modifier = Modifier.weight(1f),
            )
            ExchangeDetail(
                icon = NbIcons.Shield,
                label = "Delivery",
                value = if (product.deliveryAvailable) "Available" else "Meetup only",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExchangeDetail(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(NbTheme.colors.brandTeal.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(18.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
            Text(value, style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SellerCard(product: Product, onOpen: () -> Unit) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusLg),
        modifier = Modifier
            .padding(horizontal = NbDimens.space16)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .border(1.dp, NbTheme.colors.border, RoundedCornerShape(NbDimens.radiusLg)),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            NbAvatar(imageUrl = product.sellerProfilePicture, name = product.sellerName, size = NbDimens.avatarLg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(product.sellerName.ifBlank { "Student seller" }, style = MaterialTheme.typography.titleMedium, color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(NbIcons.Shield, contentDescription = "Verified seller", tint = NbTheme.colors.brandTeal, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = listOfNotNull(product.sellerSchool.takeIf(String::isNotBlank), product.city?.takeIf(String::isNotBlank)).joinToString(" \u2022 ").ifBlank { "Verified campus member" },
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (product.sellerReviewCount > 0) {
                    Text(
                        text = "${product.sellerReputation?.let { "%.1f".format(it) } ?: "New"} rating \u00B7 ${product.sellerReviewCount} reviews",
                        style = MaterialTheme.typography.labelSmall,
                        color = NbTheme.colors.brandTeal,
                    )
                }
            }
            Icon(NbIcons.ArrowRight, contentDescription = "View seller profile", tint = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun ReviewsHeader(count: Int, loading: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = NbDimens.space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Text("Reviews", style = MaterialTheme.typography.titleLarge, color = NbTheme.colors.ink)
        AnimatedContent(targetState = count, label = "review_count") { value ->
            NbPill(label = value.toString(), contentColor = NbTheme.colors.inkMuted)
        }
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NbTheme.colors.brandTeal)
    }
}

@Composable
private fun ReviewCard(review: Review) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusMd),
        modifier = Modifier
            .padding(horizontal = NbDimens.space16)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space12),
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            verticalAlignment = Alignment.Top,
        ) {
            NbAvatar(imageUrl = review.reviewerProfilePicture, name = review.reviewerName, size = NbDimens.avatarSm)
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(review.reviewerName.ifBlank { "Student" }, style = MaterialTheme.typography.labelLarge, color = NbTheme.colors.ink)
                RatingStars(rating = review.rating)
                review.comment?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun RatingStars(rating: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics { contentDescription = "$rating out of 5 stars" }) {
        repeat(5) { index ->
            Text(
                text = "\u2605",
                style = MaterialTheme.typography.labelMedium,
                color = if (index < rating) Color(0xFFFFB020) else NbTheme.colors.inkFaint,
            )
        }
    }
}

@Composable
private fun ProductActionBar(
    product: Product,
    policy: ProductActionPolicy,
    wishlisted: Boolean,
    busy: Boolean,
    onWishlist: () -> Unit,
    onReserve: () -> Unit,
    onContact: () -> Unit,
    onCancelReservation: () -> Unit,
    onMarkSold: () -> Unit,
    onEdit: () -> Unit,
    onReview: () -> Unit,
) {
    Surface(
        color = NbTheme.colors.surfaceCard.copy(alpha = 0.98f),
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .border(1.dp, NbTheme.colors.border, RoundedCornerShape(topStart = NbDimens.radiusLg, topEnd = NbDimens.radiusLg)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            if (policy.isSeller) {
                if (policy.canMarkSold) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                        NbButton("Mark as sold", onMarkSold, modifier = Modifier.weight(1f), enabled = !busy, loading = busy, variant = NbButtonVariant.Secondary)
                        NbOutlinedButton("Cancel reservation", onCancelReservation, modifier = Modifier.weight(1f), enabled = !busy)
                    }
                }
                if (policy.canEdit) NbOutlinedButton("Edit listing", onEdit, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (!policy.canMarkSold && !policy.canCancelReservation && !policy.canEdit) {
                    DisabledStatus(text = when (ProductStatus.from(product.status)) {
                        ProductStatus.Sold -> "This listing is sold"
                        ProductStatus.Reserved -> "Reserved"
                        else -> "Listing is being reviewed"
                    })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onWishlist,
                        enabled = !busy,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(NbDimens.radiusMd))
                            .background(if (wishlisted) NbTheme.colors.brandPink.copy(alpha = 0.12f) else NbTheme.colors.surfaceSoft)
                            .semantics { contentDescription = if (wishlisted) "Remove from wishlist" else "Save listing" },
                    ) {
                        Icon(
                            if (wishlisted) NbIcons.BookmarkFilled else NbIcons.Bookmark,
                            contentDescription = null,
                            tint = if (wishlisted) NbTheme.colors.brandPink else NbTheme.colors.inkMuted,
                        )
                    }
                    when {
                        policy.canReserve -> NbButton("Reserve item", onReserve, modifier = Modifier.weight(1f), enabled = !busy, loading = busy)
                        ProductStatus.from(product.status) == ProductStatus.Reserved && policy.reservedByViewer -> NbButton("Cancel reservation", onCancelReservation, modifier = Modifier.weight(1f), enabled = !busy, loading = busy, variant = NbButtonVariant.Secondary)
                        ProductStatus.from(product.status) == ProductStatus.Sold -> DisabledStatus("Sold out", Modifier.weight(1f))
                        else -> DisabledStatus("Unavailable", Modifier.weight(1f))
                    }
                }
                if (policy.canContactSeller) {
                    NbOutlinedButton("Contact seller", onContact, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                }
                if (policy.canReview) NbButton("Leave a review", onReview, modifier = Modifier.fillMaxWidth(), enabled = !busy, variant = NbButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun DisabledStatus(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NbDimens.radiusMd))
            .background(NbTheme.colors.surfaceSoft)
            .padding(vertical = NbDimens.space14),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = NbTheme.colors.inkMuted) }
}

@Composable
private fun ProductDetailLoading() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NbDimens.space24),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        item { NbSkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.06f), radius = 0.dp) }
        item {
            Column(Modifier.padding(horizontal = NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbSkeletonLine(widthFraction = 0.32f, height = 14.dp)
                NbSkeletonLine(widthFraction = 0.92f, height = 30.dp)
                NbSkeletonLine(widthFraction = 0.34f, height = 34.dp)
                NbSkeletonLine(widthFraction = 0.96f)
                NbSkeletonLine(widthFraction = 0.78f)
            }
        }
        item { NbSkeletonBox(Modifier.padding(horizontal = NbDimens.space16).fillMaxWidth().height(88.dp), radius = NbDimens.radiusLg) }
        item { ReviewSkeleton() }
    }
}

@Composable
private fun ReviewSkeleton() {
    Row(
        modifier = Modifier.padding(horizontal = NbDimens.space16).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        NbSkeletonBox(Modifier.size(NbDimens.avatarSm), radius = NbDimens.radiusFull)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            NbSkeletonLine(widthFraction = 0.38f)
            NbSkeletonLine(widthFraction = 0.96f)
            NbSkeletonLine(widthFraction = 0.72f)
        }
    }
}

@Composable
private fun ProductDetailError(message: String, onRetry: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Marketplace,
        title = "Listing unavailable",
        message = message,
        modifier = Modifier.fillMaxSize(),
        action = { NbButton("Try again", onRetry, variant = NbButtonVariant.Secondary) },
    )
}

@Composable
private fun ReviewsError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        NbButton("Retry reviews", onRetry, variant = NbButtonVariant.Ghost)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductAccessSheet(
    request: ProductAccessRequest,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(if (request == ProductAccessRequest.SignIn) NbIcons.Profile else NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.brandTeal) }
            Text(
                if (request == ProductAccessRequest.SignIn) "Sign in to buy safely" else "Verify your student account",
                style = MaterialTheme.typography.titleLarge,
                color = NbTheme.colors.ink,
            )
            Text(
                if (request == ProductAccessRequest.SignIn) "Join the campus marketplace to save listings, contact sellers, and reserve items." else "Verification keeps every exchange accountable to a real campus identity.",
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
            NbButton(if (request == ProductAccessRequest.SignIn) "Sign in" else "Verify now", onContinue, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Secondary)
            NbButton("Not now", onDismiss, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(
    rating: Int,
    comment: String,
    submitting: Boolean,
    onRatingChanged: (Int) -> Unit,
    onCommentChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            Text("Rate the exchange", style = MaterialTheme.typography.headlineSmall, color = NbTheme.colors.ink)
            Text("A short, honest review helps the next student buy with confidence.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                repeat(5) { index ->
                    Text(
                        text = "\u2605",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (index < rating) Color(0xFFFFB020) else NbTheme.colors.inkFaint,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onRatingChanged(index + 1) }
                            .semantics { contentDescription = "${index + 1} stars" },
                    )
                }
            }
            NbTextField(
                value = comment,
                onValueChange = onCommentChanged,
                placeholder = "Share what went well (optional)",
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
            NbButton("Submit review", onSubmit, modifier = Modifier.fillMaxWidth(), enabled = !submitting, loading = submitting, variant = NbButtonVariant.Secondary)
            NbButton("Not now", onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !submitting, variant = NbButtonVariant.Ghost)
        }
    }
}

@Composable
private fun ProductDetailToast(
    notice: ProductDetailNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = when (notice.kind) {
            ProductDetailNoticeKind.Success -> NbTheme.colors.brandMint
            ProductDetailNoticeKind.Error -> NbTheme.colors.brandPink
            ProductDetailNoticeKind.Info -> NbTheme.colors.ink
        },
        animationSpec = NbMotion.interactionTween(),
        label = "product_detail_toast_color",
    )
    LaunchedEffect(notice.id) {
        delay(3_000)
        onDismiss()
    }
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(NbMotion.interactionTween()) + slideInVertically(NbMotion.interactionTween()) { it },
        exit = fadeOut(NbMotion.interactionTween()),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().padding(NbDimens.space16), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NbDimens.radiusMd))
                    .background(color)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
            )
        }
    }
}

private fun shareProduct(context: Context, product: Product) {
    val title = product.title.ifBlank { "NextBench listing" }
    val shareText = buildString {
        append(title)
        append(" - \u20B9")
        append(product.price)
        append("\nhttps://nextbench.in/product/")
        append(product.id)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(intent, "Share listing"))
}

private const val ProductDetailRepositoryReviewLimit = 500

private val PreviewDetailProduct = Product(
    id = "preview-detail",
    title = "Concepts of Physics, both volumes",
    price = 350,
    category = "Books",
    condition = "Like New",
    description = "Clean, lightly used HC Verma volumes. No highlights, no missing pages, and ready for the next semester.",
    image = "https://images.unsplash.com/photo-1532012197267-da84d127e765?auto=format&fit=crop&q=80&w=1200",
    images = listOf(
        "https://images.unsplash.com/photo-1532012197267-da84d127e765?auto=format&fit=crop&q=80&w=1200",
        "https://images.unsplash.com/photo-1497633762265-9d179a990aa6?auto=format&fit=crop&q=80&w=1200",
        "https://images.unsplash.com/photo-1456513080510-7bf3a84b82f8?auto=format&fit=crop&q=80&w=1200",
    ),
    sellerId = "preview-maya",
    sellerName = "Maya Sharma",
    sellerSchool = "DPS RK Puram",
    city = "Delhi",
    meetupAvailable = true,
    deliveryAvailable = false,
    sellerReputation = 4.9,
    sellerReviewCount = 18,
)

private val PreviewReviews = listOf(
    Review(
        id = "preview-review-1",
        productId = "preview-detail",
        sellerId = "preview-maya",
        reviewerId = "preview-1",
        reviewerName = "Riya",
        rating = 5,
        comment = "Exactly as described and the meetup was effortless.",
    ),
    Review(
        id = "preview-review-2",
        productId = "preview-detail",
        sellerId = "preview-maya",
        reviewerId = "preview-2",
        reviewerName = "Kabir",
        rating = 5,
        comment = "Fast replies, fair price, great condition.",
    ),
)
