package com.nextbench.app.marketplace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonBox
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus

private enum class MarketplaceAccessRequest { SignIn, Verify }

internal const val MarketplacePreviewRoute = "marketplace-preview"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarketplaceScreen(
    user: com.nextbench.data.model.UserData?,
    onOpenProduct: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenWishlist: () -> Unit,
    onSell: () -> Unit,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val viewer = remember(user?.uid, user?.verified) {
        MarketplaceViewer(uid = user?.uid, verified = user?.verified == true)
    }
    var accessRequest by remember { mutableStateOf<MarketplaceAccessRequest?>(null) }

    LaunchedEffect(viewer) { viewModel.syncViewer(viewer) }

    val requestSignIn = { accessRequest = MarketplaceAccessRequest.SignIn }
    val requestVerify = { accessRequest = MarketplaceAccessRequest.Verify }
    val openProduct = { productId: String ->
        if (viewer.signedIn) onOpenProduct(productId) else requestSignIn()
    }
    val toggleWishlist: (String) -> Unit = { productId: String ->
        when {
            !viewer.signedIn -> requestSignIn()
            !viewer.verified -> requestVerify()
            else -> { viewModel.toggleWishlist(productId); Unit }
        }
    }

    MarketplaceContent(
        state = state,
        viewer = viewer,
        gridState = gridState,
        onQueryChanged = viewModel::setQuery,
        onSelectCategory = viewModel::selectCategory,
        onSelectCondition = viewModel::selectCondition,
        onSelectSort = viewModel::selectSort,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onOpenProduct = openProduct,
        onOpenProfile = { sellerId ->
            if (viewer.signedIn) onOpenProfile(sellerId) else requestSignIn()
        },
        onToggleWishlist = toggleWishlist,
        onOpenWishlist = {
            if (viewer.signedIn) onOpenWishlist() else requestSignIn()
        },
        onSell = {
            when {
                !viewer.signedIn -> requestSignIn()
                !viewer.verified -> requestVerify()
                else -> onSell()
            }
        },
        modifier = modifier,
    )

    state.notice?.let { notice ->
        MarketplaceToast(
            notice = notice,
            onDismiss = { viewModel.dismissNotice(notice.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    accessRequest?.let { request ->
        MarketplaceAccessSheet(
            request = request,
            onDismiss = { accessRequest = null },
            onContinue = {
                accessRequest = null
                if (request == MarketplaceAccessRequest.SignIn) onSignIn() else onVerify()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MarketplacePreviewScreen(modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(AllCategory) }
    var condition by rememberSaveable { mutableStateOf(AllCondition) }
    var sort by rememberSaveable { mutableStateOf(MarketplaceSort.Newest) }
    var wishlist by rememberSaveable { mutableStateOf(setOf("preview-book")) }
    val state = MarketplaceUiState(
        products = PreviewProducts,
        query = query,
        category = category,
        condition = condition,
        sort = sort,
        wishlistProductIds = wishlist,
        isInitialLoading = false,
        interactionsReady = true,
    )

    MarketplaceContent(
        state = state,
        viewer = MarketplaceViewer(uid = "preview-student", verified = true),
        gridState = rememberLazyGridState(),
        onQueryChanged = { query = it },
        onSelectCategory = { category = it },
        onSelectCondition = { condition = it },
        onSelectSort = { sort = it },
        onRefresh = {},
        onRetry = {},
        onLoadMore = {},
        onOpenProduct = {},
        onOpenProfile = {},
        onToggleWishlist = { productId ->
            wishlist = if (productId in wishlist) wishlist - productId else wishlist + productId
        },
        onOpenWishlist = {},
        onSell = {},
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MarketplaceContent(
    state: MarketplaceUiState,
    viewer: MarketplaceViewer,
    gridState: LazyGridState,
    onQueryChanged: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectCondition: (String) -> Unit,
    onSelectSort: (MarketplaceSort) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onToggleWishlist: (String) -> Unit,
    onOpenWishlist: () -> Unit,
    onSell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullState = rememberPullToRefreshState()
    var showConditionMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val products = state.visibleProducts
    val shouldLoadMore = remember(
        products.size,
        state.hasMore,
        state.isLoadingMore,
        state.isInitialLoading,
        state.isRefreshing,
    ) {
        androidx.compose.runtime.derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            products.isNotEmpty() && state.hasMore && !state.isLoadingMore &&
                !state.isInitialLoading && !state.isRefreshing && lastVisible >= products.lastIndex - 4
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        MarketplaceHeader(
            query = state.query,
            onQueryChanged = onQueryChanged,
            onOpenWishlist = onOpenWishlist,
            onSell = onSell,
        )
        MarketplaceFilters(
            state = state,
            visibleCount = products.size,
            showConditionMenu = showConditionMenu,
            showSortMenu = showSortMenu,
            onShowConditionMenu = { showConditionMenu = it },
            onShowSortMenu = { showSortMenu = it },
            onSelectCategory = onSelectCategory,
            onSelectCondition = onSelectCondition,
            onSelectSort = onSelectSort,
        )

        Box(modifier = Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isInitialLoading -> MarketplaceLoading()
                    state.initialError != null && state.products.isEmpty() -> MarketplaceError(
                        message = state.initialError,
                        onRetry = onRetry,
                    )
                    products.isEmpty() -> MarketplaceEmpty(
                        hasFilters = state.query.isNotBlank() ||
                            state.category != AllCategory || state.condition != AllCondition,
                        onClear = {
                            onQueryChanged("")
                            onSelectCategory(AllCategory)
                            onSelectCondition(AllCondition)
                        },
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = NbDimens.space12,
                            end = NbDimens.space12,
                            top = NbDimens.space8,
                            bottom = NbDimens.space32,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
                    ) {
                        itemsIndexed(
                            items = products,
                            key = { _, product -> product.id },
                            contentType = { _, _ -> "product" },
                        ) { _, product ->
                            MarketplaceProductCard(
                                product = product,
                                wishlisted = product.id in state.wishlistProductIds,
                                busy = product.id in state.busyProductIds,
                                onOpen = { onOpenProduct(product.id) },
                                onProfile = {
                                    if (product.sellerId.isNotBlank()) onOpenProfile(product.sellerId)
                                },
                                onToggleWishlist = { onToggleWishlist(product.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (state.isLoadingMore) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                MarketplaceLoadingMore()
                            }
                        } else if (state.paginationError) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                MarketplacePaginationRetry(onRetry = onLoadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceHeader(
    query: String,
    onQueryChanged: (String) -> Unit,
    onOpenWishlist: () -> Unit,
    onSell: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Text(
                    text = "Find something useful",
                    style = MaterialTheme.typography.titleLarge,
                    color = NbTheme.colors.ink,
                )
                Text(
                    text = "From your campus, in your budget",
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                )
            }
            IconButton(
                onClick = onOpenWishlist,
                modifier = Modifier
                    .semantics { contentDescription = "Open wishlist" }
                    .pressScale(targetScale = 0.9f),
            ) {
                Icon(NbIcons.Bookmark, contentDescription = null, tint = NbTheme.colors.inkMuted)
            }
            NbButton(
                text = "Sell",
                onClick = onSell,
                variant = NbButtonVariant.Ghost,
                modifier = Modifier,
            )
        }
        NbTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = "Search books, notes, gear...",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions.Default,
            leadingIcon = { Icon(NbIcons.Search, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(19.dp)) },
            trailingIcon = if (query.isNotBlank()) {
                {
                    IconButton(onClick = { onQueryChanged("") }, modifier = Modifier.size(28.dp)) {
                        Icon(NbIcons.Close, contentDescription = "Clear search", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(17.dp))
                    }
                }
            } else null,
        )
    }
}

@Composable
private fun MarketplaceFilters(
    state: MarketplaceUiState,
    visibleCount: Int,
    showConditionMenu: Boolean,
    showSortMenu: Boolean,
    onShowConditionMenu: (Boolean) -> Unit,
    onShowSortMenu: (Boolean) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectCondition: (String) -> Unit,
    onSelectSort: (MarketplaceSort) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NbDimens.space4),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = NbDimens.space16),
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            state.categories.forEach { category ->
                MarketplaceChip(
                    label = category,
                    selected = category.equals(state.category, ignoreCase = true),
                    onClick = { onSelectCategory(category) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NbDimens.space16),
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            Box {
                MarketplaceControl(
                    icon = NbIcons.Filter,
                    label = state.condition,
                    onClick = { onShowConditionMenu(true) },
                )
                DropdownMenu(
                    expanded = showConditionMenu,
                    onDismissRequest = { onShowConditionMenu(false) },
                ) {
                    listOf(AllCondition, "Brand New", "Like New", "Good", "Used").forEach { condition ->
                        DropdownMenuItem(
                            text = { Text(condition) },
                            onClick = {
                                onSelectCondition(condition)
                                onShowConditionMenu(false)
                            },
                        )
                    }
                }
            }
            Box {
                MarketplaceControl(
                    icon = NbIcons.ChevronDown,
                    label = when (state.sort) {
                        MarketplaceSort.Newest -> "Newest"
                        MarketplaceSort.PriceLow -> "Price: low"
                        MarketplaceSort.PriceHigh -> "Price: high"
                    },
                    onClick = { onShowSortMenu(true) },
                )
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { onShowSortMenu(false) },
                ) {
                    listOf(
                        MarketplaceSort.Newest to "Newest",
                        MarketplaceSort.PriceLow to "Price: low",
                        MarketplaceSort.PriceHigh to "Price: high",
                    ).forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelectSort(sort)
                                onShowSortMenu(false)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "$visibleCount ${if (visibleCount == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelMedium,
                color = NbTheme.colors.inkMuted,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun MarketplaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) NbTheme.colors.ink else NbTheme.colors.surfaceCard,
        animationSpec = NbMotion.interactionTween(),
        label = "market_chip_container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) NbTheme.colors.surfaceBase else NbTheme.colors.inkMuted,
        animationSpec = NbMotion.interactionTween(),
        label = "market_chip_content",
    )
    Surface(
        color = container,
        shape = RoundedCornerShape(NbDimens.radiusFull),
        modifier = Modifier
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
        )
    }
}

@Composable
private fun MarketplaceControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .background(NbTheme.colors.surfaceCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
    ) {
        Icon(icon, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, maxLines = 1)
    }
}

@Composable
private fun MarketplaceProductCard(
    product: Product,
    wishlisted: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onProfile: () -> Unit,
    onToggleWishlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = product.images.firstOrNull() ?: product.image
    val context = LocalContext.current
    val imageRequest = remember(image) {
        image?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(250)
                .build()
        }
    }
    val status = ProductStatus.from(product.status)
    val cardScale by animateFloatAsState(
        targetValue = if (busy) 0.985f else 1f,
        animationSpec = NbMotion.interactionTween(),
        label = "product_busy_scale",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(cardScale)
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(NbTheme.colors.surfaceCard)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(NbTheme.colors.surfaceSoft),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(NbIcons.Marketplace, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(42.dp))
            }
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = product.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.46f)),
                        ),
                    ),
            )
            IconButton(
                onClick = onToggleWishlist,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NbDimens.space8)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(NbTheme.colors.surfaceCard.copy(alpha = 0.88f))
                    .semantics { contentDescription = if (wishlisted) "Remove from wishlist" else "Save to wishlist" },
            ) {
                Icon(
                    imageVector = if (wishlisted) NbIcons.BookmarkFilled else NbIcons.Bookmark,
                    contentDescription = null,
                    tint = if (wishlisted) NbTheme.colors.brandPink else NbTheme.colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (status == ProductStatus.Reserved || status == ProductStatus.Sold) {
                NbPill(
                    label = status.raw.replaceFirstChar(Char::uppercase),
                    contentColor = Color.White,
                    containerColor = Color.Black.copy(alpha = 0.56f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(NbDimens.space8),
                )
            }
            if (product.images.size > 1) {
                Text(
                    text = "${product.images.size} photos",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NbDimens.space8),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space12),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                NbAvatar(imageUrl = product.sellerProfilePicture, name = product.sellerName, size = 22.dp, modifier = Modifier.clickable(onClick = onProfile))
                Text(
                    text = product.sellerName.ifBlank { "Student" },
                    style = MaterialTheme.typography.labelSmall,
                    color = NbTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onProfile),
                )
            }
            Text(
                text = product.title.ifBlank { "Untitled listing" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NbTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "\u20B9${product.price}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NbTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = product.condition.ifBlank { "Used" },
                    style = MaterialTheme.typography.labelSmall,
                    color = NbTheme.colors.brandTeal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val location = product.city?.takeIf(String::isNotBlank) ?: product.sellerSchool
            if (location.isNotBlank()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = NbTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MarketplaceLoading() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NbDimens.space12),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        userScrollEnabled = false,
    ) {
        items(6) {
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbSkeletonBox(Modifier.fillMaxWidth().aspectRatio(4f / 3f), radius = NbDimens.radiusSm)
                NbSkeletonLine(widthFraction = 0.55f, height = 12.dp)
                NbSkeletonLine(widthFraction = 0.9f, height = 16.dp)
                NbSkeletonLine(widthFraction = 0.4f, height = 12.dp)
            }
        }
    }
}

@Composable
private fun MarketplaceLoadingMore() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NbDimens.space20),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = NbTheme.colors.brandTeal)
    }
}

@Composable
private fun MarketplacePaginationRetry(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NbDimens.space16),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Could not load more",
            style = MaterialTheme.typography.bodySmall,
            color = NbTheme.colors.inkMuted,
        )
        Spacer(Modifier.width(NbDimens.space8))
        Text(
            text = "Retry",
            style = MaterialTheme.typography.labelMedium,
            color = NbTheme.colors.brandTeal,
            modifier = Modifier
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .clickable(onClick = onRetry)
                .padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
        )
    }
}

@Composable
private fun MarketplaceEmpty(hasFilters: Boolean, onClear: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Marketplace,
        title = if (hasFilters) "No matches" else "Nothing listed yet",
        message = if (hasFilters) "Try a broader search or category." else "New campus finds will show up here.",
        modifier = Modifier.fillMaxSize(),
        action = if (hasFilters) {
            {
                NbButton(text = "Clear filters", onClick = onClear, variant = NbButtonVariant.Ghost)
            }
        } else null,
    )
}

@Composable
private fun MarketplaceError(message: String, onRetry: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Refresh,
        title = "Marketplace unavailable",
        message = message,
        modifier = Modifier.fillMaxSize(),
        action = {
            NbButton(text = "Try again", onClick = onRetry, variant = NbButtonVariant.Secondary)
        },
    )
}

@Composable
private fun MarketplaceToast(
    notice: MarketplaceNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (notice.kind) {
        MarketplaceNoticeKind.Success -> NbTheme.colors.brandMint
        MarketplaceNoticeKind.Error -> NbTheme.colors.brandPink
        MarketplaceNoticeKind.Info -> NbTheme.colors.ink
    }
    Box(modifier = modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space16), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(NbMotion.interactionTween()) + slideInVertically(NbMotion.interactionTween()) { it },
            exit = fadeOut(NbMotion.interactionTween()),
        ) {
            LaunchedEffect(notice.id) {
                kotlinx.coroutines.delay(3000)
                onDismiss()
            }
            Surface(
                color = container,
                shape = RoundedCornerShape(NbDimens.radiusMd),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketplaceAccessSheet(
    request: MarketplaceAccessRequest,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space24),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            Text(
                text = if (request == MarketplaceAccessRequest.SignIn) "Sign in to continue" else "Verify your student account",
                style = MaterialTheme.typography.headlineSmall,
                color = NbTheme.colors.ink,
            )
            Text(
                text = if (request == MarketplaceAccessRequest.SignIn) "Sign in to view listing details and contact the seller." else "Student verification is required to save or list items.",
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
            NbButton(
                text = if (request == MarketplaceAccessRequest.SignIn) "Sign in" else "Start verification",
                onClick = onContinue,
                variant = NbButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            NbButton(text = "Not now", onClick = onDismiss, variant = NbButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

private val PreviewProducts = listOf(
    Product(
        id = "preview-book",
        title = "Concepts of Physics, both volumes",
        price = 350,
        category = "Books",
        condition = "Like New",
        image = "https://images.unsplash.com/photo-1532012197267-da84d127e765?auto=format&fit=crop&q=80&w=800",
        sellerId = "preview-maya",
        sellerName = "Maya",
        sellerSchool = "DPS RK Puram",
        city = "Delhi",
    ),
    Product(
        id = "preview-notes",
        title = "NEET biology handwritten notes",
        price = 799,
        category = "Notes",
        condition = "Good",
        image = "https://images.unsplash.com/photo-1517842645767-c639042777db?auto=format&fit=crop&q=80&w=800",
        sellerId = "preview-dev",
        sellerName = "Dev",
        sellerSchool = "Modern School",
        city = "Delhi",
        images = listOf(
            "https://images.unsplash.com/photo-1517842645767-c639042777db?auto=format&fit=crop&q=80&w=800",
            "https://images.unsplash.com/photo-1456324504439-367cee3b3c32?auto=format&fit=crop&q=80&w=800",
        ),
    ),
    Product(
        id = "preview-calculator",
        title = "Scientific calculator fx-991EX",
        price = 1800,
        category = "Electronics",
        condition = "Good",
        image = "https://images.unsplash.com/photo-1594980596870-8aa52a78d8cd?auto=format&fit=crop&q=80&w=800",
        sellerId = "preview-kabir",
        sellerName = "Kabir",
        sellerSchool = "Welham Boys",
        city = "Dehradun",
    ),
    Product(
        id = "preview-blazer",
        title = "College winter blazer, size 38",
        price = 1200,
        category = "Uniforms",
        condition = "Used",
        image = "https://images.unsplash.com/photo-1594938298603-c8148c4dae35?auto=format&fit=crop&q=80&w=800",
        status = ProductStatus.Sold.raw,
        sellerId = "preview-aryan",
        sellerName = "Aryan",
        sellerSchool = "Heritage School",
        city = "Gurugram",
    ),
    Product(
        id = "preview-hostel",
        title = "Hostel bedsheet set, single bed",
        price = 899,
        category = "Hostel Essentials",
        condition = "Brand New",
        image = "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&q=80&w=800",
        sellerId = "preview-priya",
        sellerName = "Priya",
        sellerSchool = "Amity University",
        city = "Noida",
    ),
    Product(
        id = "preview-racket",
        title = "Badminton racket set",
        price = 850,
        category = "Sports Gear",
        condition = "Like New",
        image = "https://images.unsplash.com/photo-1622279457486-63d7306b1858?auto=format&fit=crop&q=80&w=800",
        sellerId = "preview-rohan",
        sellerName = "Rohan",
        sellerSchool = "Step by Step",
        city = "Noida",
    ),
)
