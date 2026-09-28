package com.nextbench.app.feed

import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.common.formatRupees
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
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.firebase.FeedMode
import com.nextbench.data.firebase.FeedOrderEntry
import com.nextbench.data.firebase.PostVote
import com.nextbench.data.model.Poll
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class FeedAccessRequest { SignIn, Verify }

private data class ScrollPosition(val index: Int, val offset: Int)

internal sealed interface FeedContent {
    val key: String
    val type: String

    data class PostItem(val post: Post) : FeedContent {
        override val key = "post:${post.id}"
        override val type = "post:${post.type}"
    }

    data class ProductItem(val product: Product) : FeedContent {
        override val key = "product:${product.id}"
        override val type = "product"
    }
}

internal fun buildFeedContent(
    posts: List<Post>,
    products: List<Product>,
    order: List<FeedOrderEntry>,
): List<FeedContent> {
    val postsById = posts.associateBy(Post::id)
    val productsById = products.associateBy(Product::id)
    val ordered = order.mapNotNull { entry ->
        when (entry.type) {
            "post" -> postsById[entry.id]?.let(FeedContent::PostItem)
            "product" -> productsById[entry.id]?.let(FeedContent::ProductItem)
            else -> null
        }
    }
    if (ordered.isNotEmpty()) {
        val orderedKeys = ordered.mapTo(mutableSetOf(), FeedContent::key)
        return ordered + posts.map(FeedContent::PostItem).filterNot { it.key in orderedKeys } +
            products.map(FeedContent::ProductItem).filterNot { it.key in orderedKeys }
    }

    if (posts.isEmpty()) return products.map(FeedContent::ProductItem)

    val mixed = mutableListOf<FeedContent>()
    val productLimit = ((posts.size + ProductInterval - 1) / ProductInterval).coerceAtLeast(1)
    val productIterator = products.take(productLimit).iterator()
    posts.forEachIndexed { index, post ->
        mixed += FeedContent.PostItem(post)
        if ((index + 1) % ProductInterval == 0 && productIterator.hasNext()) {
            mixed += FeedContent.ProductItem(productIterator.next())
        }
    }
    while (productIterator.hasNext()) mixed += FeedContent.ProductItem(productIterator.next())
    return mixed
}

@Composable
fun CommunityScreen(
    user: UserData?,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onChromeVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = hiltViewModel(),
    storyViewModel: StoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storyState by storyViewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var accessRequest by remember { mutableStateOf<FeedAccessRequest?>(null) }
    var storyAuthorIndex by remember { mutableStateOf<Int?>(null) }
    val viewer = remember(user?.uid, user?.verified) {
        FeedViewer(uid = user?.uid, verified = user?.verified == true)
    }
    val storyPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(storyViewModel::prepareMedia)
    }

    LaunchedEffect(viewer) { viewModel.syncViewer(viewer) }
    LaunchedEffect(viewer.uid) { storyViewModel.syncUser(viewer.uid) }
    LaunchedEffect(state.mode) { listState.scrollToItem(0) }
    LaunchedEffect(listState) {
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow {
            ScrollPosition(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { position ->
                val atTop = position.index == 0 && position.offset < ChromeRevealThresholdPx
                val scrollingDown = position.index > previousIndex ||
                    (position.index == previousIndex && position.offset > previousOffset)
                val scrollingUp = position.index < previousIndex ||
                    (position.index == previousIndex && position.offset < previousOffset)
                when {
                    atTop || scrollingUp -> onChromeVisibilityChanged(true)
                    scrollingDown -> onChromeVisibilityChanged(false)
                }
                previousIndex = position.index
                previousOffset = position.offset
            }
    }

    CommunityContent(
        state = state,
        storyState = storyState,
        user = user,
        viewer = viewer,
        listState = listState,
        onSelectMode = viewModel::selectMode,
        onSelectDisplayMode = viewModel::selectDisplayMode,
        onRefresh = {
            viewModel.refresh()
            storyViewModel.refresh()
        },
        onLoadMore = viewModel::loadMore,
        onOpenPost = onOpenPost,
        onOpenProfile = onOpenProfile,
        onOpenProduct = onOpenProduct,
        onVote = { postId, vote, doubleTap ->
            when {
                !viewer.signedIn -> {
                    accessRequest = FeedAccessRequest.SignIn
                    false
                }
                !viewer.verified -> {
                    accessRequest = FeedAccessRequest.Verify
                    false
                }
                doubleTap && postId in state.upvotedPostIds -> false
                else -> viewModel.toggleVote(postId, vote)
            }
        },
        onSave = { postId ->
            if (!viewer.signedIn) {
                accessRequest = FeedAccessRequest.SignIn
            } else {
                viewModel.toggleSave(postId)
            }
        },
        onRequestSignIn = { accessRequest = FeedAccessRequest.SignIn },
        onRetryInteractions = viewModel::retryInteractions,
        onOpenStory = { index -> storyAuthorIndex = index },
        onRetryStories = storyViewModel::refresh,
        onAddStory = {
            when {
                !viewer.signedIn -> accessRequest = FeedAccessRequest.SignIn
                !viewer.verified -> accessRequest = FeedAccessRequest.Verify
                else -> storyPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        },
        modifier = modifier,
    )

    val notice = state.notice
    if (notice != null) {
        FeedToast(
            notice = notice,
            onDismiss = { viewModel.dismissNotice(notice.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    accessRequest?.let { request ->
        FeedAccessSheet(
            request = request,
            onDismiss = { accessRequest = null },
            onContinue = {
                accessRequest = null
                when (request) {
                    FeedAccessRequest.SignIn -> onSignIn()
                    FeedAccessRequest.Verify -> onVerify()
                }
            },
        )
    }

    if (storyState.isPreparingMedia) StoryPreparingDialog()

    storyState.composerMedia?.prepared?.let { media ->
        StoryComposer(
            media = media,
            privacy = storyState.privacy,
            publishing = storyState.isPublishing,
            onPrivacy = storyViewModel::selectPrivacy,
            onDismiss = storyViewModel::dismissComposer,
            onPublish = { user?.let(storyViewModel::publish) },
        )
    }

    storyAuthorIndex?.let { index ->
        if (user != null) {
            StoryViewer(
                user = user,
                state = storyState,
                initialAuthorIndex = index,
                onClose = { storyAuthorIndex = null },
                onOpenProfile = onOpenProfile,
                onMarkSeen = storyViewModel::markSeen,
                onRecordView = storyViewModel::recordView,
                onLoadLiked = storyViewModel::loadLiked,
                onToggleLike = storyViewModel::toggleLike,
                onReply = { storyId, content -> storyViewModel.reply(user, storyId, content) },
                onDelete = storyViewModel::delete,
            )
        }
    }

    storyState.interactionMessage?.let { message ->
        StoryMessage(message = message, onDismiss = storyViewModel::dismissMessage)
    }
}

@Composable
private fun FeedProductCard(
    product: Product,
    compact: Boolean,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageUrl = product.images.firstOrNull() ?: product.image
    val category = product.category.ifBlank { "Campus find" }
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusSm),
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) NbDimens.space16 else NbDimens.space12,
                vertical = NbDimens.space8,
            )
            .pressScale(onTap = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = product.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (compact) 82.dp else 112.dp)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .background(NbTheme.colors.surfaceSoft),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Text(
                    text = if (product.looksLikeBook()) "BOOKS ON NEXTBENCH" else "FROM NEXTBENCH MARKET",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = NbTheme.colors.brandPink,
                )
                Text(
                    text = product.title.ifBlank { "Untitled listing" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.ink,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatRupees(product.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = NbTheme.colors.brandTeal,
                    )
                    Text(
                        text = "  ·  $category",
                        style = MaterialTheme.typography.bodySmall,
                        color = NbTheme.colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!compact && product.sellerName.isNotBlank()) {
                    Text(
                        text = "Listed by ${product.sellerName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NbTheme.colors.inkMuted,
                        modifier = Modifier.clickable(onClick = onOpenProfile),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = NbIcons.ArrowRight,
                contentDescription = null,
                tint = NbTheme.colors.inkFaint,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CommunityContent(
    state: FeedUiState,
    storyState: StoryUiState,
    user: UserData?,
    viewer: FeedViewer,
    listState: LazyListState,
    onSelectMode: (FeedMode) -> Unit,
    onSelectDisplayMode: (FeedDisplayMode) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
    onVote: (String, PostVote, Boolean) -> Boolean,
    onSave: (String) -> Unit,
    onRequestSignIn: () -> Unit,
    onRetryInteractions: () -> Unit,
    onOpenStory: (Int) -> Unit,
    onRetryStories: () -> Unit,
    onAddStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullState = rememberPullToRefreshState()
    val feedItems = remember(state.posts, state.products, state.feedOrder) {
        buildFeedContent(state.posts, state.products, state.feedOrder)
    }
    val shouldLoadMore = remember(
        feedItems.size,
        state.hasMore,
        state.isLoadingMore,
        state.interactionsReady,
        viewer.signedIn,
    ) {
        androidx.compose.runtime.derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            feedItems.isNotEmpty() && state.hasMore && !state.isLoadingMore &&
                lastVisible >= totalItems - PaginationThreshold
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NbDimens.space24),
        ) {
            if (viewer.signedIn && user != null) {
                item(key = "stories", contentType = "stories") {
                    StoriesTray(
                        user = user,
                        state = storyState,
                        onOpen = onOpenStory,
                        onAdd = onAddStory,
                        onRetry = onRetryStories,
                    )
                }
            }
            if (viewer.signedIn) {
                item(key = "feed_controls", contentType = "feed_controls") {
                    FeedModeBar(
                        mode = state.mode,
                        displayMode = state.displayMode,
                        onSelectMode = onSelectMode,
                        onSelectDisplayMode = onSelectDisplayMode,
                    )
                }
            }

            if (viewer.signedIn && !state.interactionsReady) {
                item(key = "interaction_status") {
                    InteractionStatus(
                        loading = state.isLoadingInteractions,
                        onRetry = onRetryInteractions,
                    )
                }
            }

            when {
                state.isInitialLoading -> items(3, key = { "feed_skeleton_$it" }) {
                    FeedSkeletonCard()
                }
                state.initialError != null && feedItems.isEmpty() -> item(key = "feed_error") {
                    FeedError(
                        message = state.initialError,
                        onRetry = onRefresh,
                    )
                }
                feedItems.isEmpty() -> item(key = "feed_empty") {
                    FeedEmpty(
                        following = state.mode == FeedMode.Following,
                        onRefresh = onRefresh,
                    )
                }
                else -> itemsIndexed(
                    items = feedItems,
                    key = { _, item -> item.key },
                    contentType = { _, item -> "${state.displayMode}:${item.type}" },
                ) { index, item ->
                    when (item) {
                        is FeedContent.PostItem -> {
                            val post = item.post
                            val interactionsEnabled = !viewer.signedIn ||
                                (state.interactionsReady && post.id !in state.busyPostIds)
                            val onProfileClick = {
                                if (!post.isAnonymous && post.authorId.isNotBlank()) onOpenProfile(post.authorId)
                            }
                            if (state.displayMode == FeedDisplayMode.List) {
                                PostListRow(
                                    post = post,
                                    upvoted = post.id in state.upvotedPostIds,
                                    saved = post.id in state.savedPostIds,
                                    interactionsEnabled = interactionsEnabled,
                                    onOpen = { onOpenPost(post.id) },
                                    onProfile = onProfileClick,
                                    onVote = { vote, doubleTap -> onVote(post.id, vote, doubleTap) },
                                    onSave = { onSave(post.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            } else {
                                PostCard(
                                    post = post,
                                    upvoted = post.id in state.upvotedPostIds,
                                    downvoted = post.id in state.downvotedPostIds,
                                    saved = post.id in state.savedPostIds,
                                    interactionsEnabled = interactionsEnabled,
                                    onOpen = { onOpenPost(post.id) },
                                    onProfile = onProfileClick,
                                    onVote = { vote, doubleTap -> onVote(post.id, vote, doubleTap) },
                                    onSave = { onSave(post.id) },
                                    modifier = Modifier.animateItem(),
                                    entranceDelay = index.coerceAtMost(4) * 45L,
                                )
                            }
                        }
                        is FeedContent.ProductItem -> FeedProductCard(
                            product = item.product,
                            compact = state.displayMode == FeedDisplayMode.List,
                            onOpen = { onOpenProduct(item.product.id) },
                            onOpenProfile = {
                                item.product.sellerId.takeIf(String::isNotBlank)?.let(onOpenProfile)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            if (!viewer.signedIn && feedItems.isNotEmpty()) {
                item(key = "guest_preview") {
                    GuestPreviewFooter(onSignIn = onRequestSignIn)
                }
            }

            if (state.isLoadingMore) {
                item(key = "load_more") { FeedLoadingMore() }
            } else if (state.paginationError) {
                item(key = "load_more_retry") {
                    FeedPaginationRetry(onRetry = onLoadMore)
                }
            }
        }
    }
}

@Composable
private fun FeedModeBar(
    mode: FeedMode,
    displayMode: FeedDisplayMode,
    onSelectMode: (FeedMode) -> Unit,
    onSelectDisplayMode: (FeedDisplayMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.surfaceBase.copy(alpha = 0.97f))
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NbDimens.radiusMd))
                .background(NbTheme.colors.surfaceSoft)
                .padding(NbDimens.space4),
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
        ) {
            FeedModeButton(
                label = "For you",
                selected = mode == FeedMode.ForYou,
                onClick = { onSelectMode(FeedMode.ForYou) },
                modifier = Modifier.weight(1f),
            )
            FeedModeButton(
                label = "Following",
                selected = mode == FeedMode.Following,
                onClick = { onSelectMode(FeedMode.Following) },
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .background(NbTheme.colors.surfaceBase.copy(alpha = 0.64f))
                    .padding(horizontal = NbDimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedDisplayButton(
                    icon = NbIcons.Layout,
                    description = "Editorial view",
                    selected = displayMode == FeedDisplayMode.Editorial,
                    onClick = { onSelectDisplayMode(FeedDisplayMode.Editorial) },
                )
                FeedDisplayButton(
                    icon = NbIcons.List,
                    description = "List view",
                    selected = displayMode == FeedDisplayMode.List,
                    onClick = { onSelectDisplayMode(FeedDisplayMode.List) },
                )
            }
        }
    }
}

@Composable
private fun FeedDisplayButton(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        if (selected) NbTheme.colors.ink else NbTheme.colors.inkMuted,
        NbMotion.interactionTween(),
        label = "feed_display_tint",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(if (selected) NbTheme.colors.surfaceCard else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun FeedModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (selected) NbTheme.colors.surfaceCard else Color.Transparent,
        NbMotion.interactionTween(),
        label = "feed_mode_container",
    )
    val content by animateColorAsState(
        if (selected) NbTheme.colors.ink else NbTheme.colors.inkMuted,
        NbMotion.interactionTween(),
        label = "feed_mode_content",
    )
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(container)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun InteractionStatus(loading: Boolean, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.brandTeal.copy(alpha = 0.07f))
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = NbTheme.colors.brandTeal,
            )
            Text(
                "Syncing your likes and saved posts",
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
            )
        } else {
            Text(
                "Likes and saves could not be synced.",
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Retry",
                style = MaterialTheme.typography.labelMedium,
                color = NbTheme.colors.brandTeal,
                modifier = Modifier
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
            )
        }
    }
}

@Composable
private fun PostCard(
    post: Post,
    upvoted: Boolean,
    downvoted: Boolean,
    saved: Boolean,
    interactionsEnabled: Boolean,
    onOpen: () -> Unit,
    onProfile: () -> Unit,
    onVote: (PostVote, Boolean) -> Boolean,
    onSave: () -> Unit,
    entranceDelay: Long,
    modifier: Modifier = Modifier,
) {
    var visible by rememberSaveable(post.id) { mutableStateOf(entranceDelay == 0L) }
    LaunchedEffect(post.id) {
        delay(entranceDelay)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(NbMotion.interactionTween()),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NbTheme.colors.surfaceCard),
        ) {
            PostCardBody(
                post = post,
                upvoted = upvoted,
                downvoted = downvoted,
                saved = saved,
                interactionsEnabled = interactionsEnabled,
                onOpen = onOpen,
                onProfile = onProfile,
                onVote = onVote,
                onSave = onSave,
            )
        }
        HorizontalDivider(color = NbTheme.colors.border, thickness = 1.dp)
    }
}

@Composable
private fun PostListRow(
    post: Post,
    upvoted: Boolean,
    saved: Boolean,
    interactionsEnabled: Boolean,
    onOpen: () -> Unit,
    onProfile: () -> Unit,
    onVote: (PostVote, Boolean) -> Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = if (post.isAnonymous) post.personaName ?: "Anonymous" else post.authorName.ifBlank { "Unknown" }
    val avatar = if (post.isAnonymous) null else post.authorProfilePicture
    val previewImage = remember(post.id, post.imageUrl, post.imageUrls) {
        post.imageUrls.firstOrNull() ?: post.imageUrl
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space14),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .then(if (post.isAnonymous) Modifier.background(NbTheme.colors.brandPink.copy(alpha = 0.12f)) else Modifier.clickable(onClick = onProfile)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (post.isAnonymous) {
                        Text(post.personaEmoji?.takeIf(String::isNotBlank) ?: "?", color = NbTheme.colors.brandPink)
                    } else {
                        NbAvatar(imageUrl = avatar, name = displayName, size = 30.dp)
                    }
                }
                Text(
                    displayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    post.createdAt?.toDate()?.time?.let(::formatRelativeTime).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NbTheme.colors.inkMuted,
                )
            }
            if (post.title.isNotBlank()) {
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NbTheme.colors.inkMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space2),
            ) {
                PostAction(
                    icon = if (upvoted) NbIcons.HeartFilled else NbIcons.Heart,
                    label = post.upvotesCount.toString(),
                    description = if (upvoted) "Remove like" else "Like",
                    selected = upvoted,
                    selectedColor = NbTheme.colors.brandPink,
                    enabled = interactionsEnabled,
                    onClick = { onVote(PostVote.Up, false) },
                    horizontalPadding = 0.dp,
                )
                PostAction(
                    icon = NbIcons.Reply,
                    label = post.repliesCount.toString(),
                    description = "Open comments",
                    enabled = true,
                    onClick = onOpen,
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.weight(1f))
                PostAction(
                    icon = if (saved) NbIcons.BookmarkFilled else NbIcons.Bookmark,
                    label = null,
                    description = if (saved) "Remove from saved posts" else "Save post",
                    selected = saved,
                    selectedColor = NbTheme.colors.brandTeal,
                    enabled = interactionsEnabled,
                    onClick = onSave,
                    horizontalPadding = 0.dp,
                )
            }
        }
        previewImage?.takeIf(String::isNotBlank)?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = "Post image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(NbDimens.radiusMd))
                    .background(NbTheme.colors.surfaceSoft),
            )
        }
    }
    HorizontalDivider(color = NbTheme.colors.border)
}

@Composable
internal fun PostCardBody(
    post: Post,
    upvoted: Boolean,
    downvoted: Boolean,
    saved: Boolean,
    interactionsEnabled: Boolean,
    onOpen: () -> Unit,
    onComments: () -> Unit = onOpen,
    onAttachment: (String) -> Unit = { onOpen() },
    onProfile: () -> Unit,
    onVote: (PostVote, Boolean) -> Boolean,
    onSave: () -> Unit,
    expanded: Boolean = false,
) {
    var showHeart by remember(post.id) { mutableStateOf(false) }
    var heartEvent by remember(post.id) { mutableLongStateOf(0L) }
    val displayName = if (post.isAnonymous) post.personaName ?: "Anonymous" else post.authorName.ifBlank { "Unknown" }
    val avatar = if (post.isAnonymous) null else post.authorProfilePicture

    LaunchedEffect(heartEvent) {
        if (heartEvent == 0L) return@LaunchedEffect
        showHeart = true
        delay(650)
        showHeart = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        PostHeader(
            post = post,
            displayName = displayName,
            avatar = avatar,
            onProfile = onProfile,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(post.id, upvoted, interactionsEnabled) {
                    detectTapGestures(
                        onTap = { onOpen() },
                        onDoubleTap = {
                            if (interactionsEnabled && onVote(PostVote.Up, true)) {
                                heartEvent++
                            }
                        },
                    )
                },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                if (post.title.isNotBlank()) {
                    Text(
                        text = post.title,
                        style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = NbTheme.colors.ink,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
                if (post.content.isNotBlank()) {
                    Text(
                        text = post.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NbTheme.colors.inkMuted,
                        maxLines = if (expanded) Int.MAX_VALUE else ContentPreviewLines,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }

                post.poll?.takeIf { it.choices.isNotEmpty() }?.let { poll -> PostPoll(poll) }
                PostMedia(post = post, onAttachment = onAttachment)
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showHeart,
                enter = fadeIn(NbMotion.interactionTween()) + scaleIn(initialScale = 0.3f),
                exit = fadeOut(NbMotion.interactionTween()) + scaleOut(targetScale = 0.85f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    imageVector = NbIcons.HeartFilled,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(92.dp)
                        .drawBehind { drawCircle(Color.Black.copy(alpha = 0.28f)) },
                )
            }
        }

        PostActions(
            post = post,
            upvoted = upvoted,
            downvoted = downvoted,
            saved = saved,
            enabled = interactionsEnabled,
            onVote = { vote -> onVote(vote, false) },
            onComments = onComments,
            onSave = onSave,
        )
    }
}

@Composable
private fun PostHeader(
    post: Post,
    displayName: String,
    avatar: String?,
    onProfile: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(
                    if (post.isAnonymous) {
                        Modifier.background(NbTheme.colors.brandPink.copy(alpha = 0.12f))
                    } else {
                        Modifier.clickable(onClick = onProfile)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (post.isAnonymous) {
                Text(
                    text = post.personaEmoji?.takeIf(String::isNotBlank) ?: displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = NbTheme.colors.brandPink,
                )
            } else {
                NbAvatar(imageUrl = avatar, name = displayName, size = 40.dp)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (!post.isAnonymous) Modifier.clickable(onClick = onProfile) else Modifier),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = NbTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val metadata = listOfNotNull(
                    post.createdAt?.toDate()?.time?.let(::formatRelativeTime),
                    post.school.takeIf(String::isNotBlank),
                ).joinToString("  |  ")
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NbPill(
            label = postTypeLabel(post.type),
            contentColor = if (post.isAnonymous) NbTheme.colors.brandPink else NbTheme.colors.brandTeal,
        )
    }
}

@Composable
private fun PostPoll(poll: Poll) {
    val totalVotes = poll.votes.values.sum().coerceAtLeast(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NbDimens.radiusMd))
            .background(NbTheme.colors.surfaceSoft)
            .padding(NbDimens.space12),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        poll.choices.take(MaxPollChoices).forEachIndexed { index, choice ->
            val votes = poll.votes[index.toString()] ?: 0
            val fraction = if (totalVotes == 0) 0f else votes.toFloat() / totalVotes
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .background(NbTheme.colors.surfaceCard)
                    .drawBehind {
                        drawRect(
                            color = Color(0x210071E3),
                            size = size.copy(width = size.width * fraction),
                        )
                    }
                    .padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = choice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NbTheme.colors.ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = NbTheme.colors.inkMuted,
                    )
                }
            }
        }
        Text(
            text = "$totalVotes ${if (totalVotes == 1) "vote" else "votes"}",
            style = MaterialTheme.typography.bodySmall,
            color = NbTheme.colors.inkMuted,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostMedia(post: Post, onAttachment: (String) -> Unit) {
    val images = remember(post.imageUrl, post.imageUrls) {
        post.imageUrls.ifEmpty { listOfNotNull(post.imageUrl) }.distinct()
    }
    if (images.isNotEmpty()) {
        val pagerState = rememberPagerState(pageCount = images::size)
        val ratio = post.mediaAspectRatio()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(NbDimens.radiusMd))
                .background(NbTheme.colors.surfaceSoft),
        ) {
            HorizontalPager(state = pagerState, beyondViewportPageCount = 1) { page ->
                FeedImage(url = images[page], description = post.title.ifBlank { "Post image" })
            }
            if (images.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${images.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NbDimens.space8)
                        .clip(RoundedCornerShape(NbDimens.radiusSm))
                        .background(NbTheme.colors.overlayHeavy)
                        .padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(NbDimens.space8),
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
                ) {
                    repeat(images.size.coerceAtMost(MaxMediaDots)) { index ->
                        Spacer(
                            modifier = Modifier
                                .width(if (index == pagerState.currentPage) 14.dp else 5.dp)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (index == pagerState.currentPage) 1f else 0.6f)),
                        )
                    }
                }
            }
        }
    }
    post.pdfUrl?.takeIf(String::isNotBlank)?.let { pdfUrl ->
        MediaAttachment(
            icon = NbIcons.FileText,
            title = "PDF attachment",
            detail = post.pdfPages?.let { "$it ${if (it == 1) "page" else "pages"}" } ?: "Open post to read",
            onClick = { onAttachment(pdfUrl) },
        )
    }
    post.videoUrl?.takeIf(String::isNotBlank)?.let { videoUrl ->
        MediaAttachment(
            icon = NbIcons.Play,
            title = "Video",
            detail = "Open post to watch",
            onClick = { onAttachment(videoUrl) },
        )
    }
}

@Composable
private fun FeedImage(url: String, description: String) {
    var painterState by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onState = { painterState = it },
        )
        when (painterState) {
            is AsyncImagePainter.State.Loading, AsyncImagePainter.State.Empty ->
                NbSkeletonBox(modifier = Modifier.fillMaxSize(), radius = 0.dp)
            is AsyncImagePainter.State.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Icon(NbIcons.Camera, contentDescription = null, tint = NbTheme.colors.inkMuted)
                Text("Image unavailable", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

@Composable
private fun MediaAttachment(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NbDimens.radiusMd))
            .background(NbTheme.colors.surfaceSoft)
            .clickable(onClick = onClick)
            .padding(NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = NbTheme.colors.brandTeal)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NbTheme.colors.ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        }
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun PostActions(
    post: Post,
    upvoted: Boolean,
    downvoted: Boolean,
    saved: Boolean,
    enabled: Boolean,
    onVote: (PostVote) -> Unit,
    onComments: () -> Unit,
    onSave: () -> Unit,
) {
    HorizontalDivider(color = NbTheme.colors.border)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PostAction(
            icon = if (upvoted) NbIcons.HeartFilled else NbIcons.Heart,
            label = post.upvotesCount.toString(),
            description = if (upvoted) "Remove like" else "Like",
            selected = upvoted,
            selectedColor = NbTheme.colors.brandPink,
            enabled = enabled,
            onClick = { onVote(PostVote.Up) },
            horizontalPadding = NbDimens.space4,
        )
        PostAction(
            icon = NbIcons.ArrowDown,
            label = post.downvotesCount.takeIf { it > 0 }?.toString(),
            description = if (downvoted) "Remove dislike" else "Dislike",
            selected = downvoted,
            selectedColor = NbTheme.colors.brandTeal,
            enabled = enabled,
            onClick = { onVote(PostVote.Down) },
            horizontalPadding = NbDimens.space4,
        )
        PostAction(
            icon = NbIcons.Reply,
            label = post.repliesCount.toString(),
            description = "Open comments",
            enabled = true,
            onClick = onComments,
            horizontalPadding = NbDimens.space4,
        )
        Spacer(Modifier.weight(1f))
        ShareAction(post)
        PostAction(
            icon = if (saved) NbIcons.BookmarkFilled else NbIcons.Bookmark,
            label = null,
            description = if (saved) "Remove from saved posts" else "Save post",
            selected = saved,
            selectedColor = NbTheme.colors.brandTeal,
            enabled = enabled,
            onClick = onSave,
        )
    }
}

@Composable
private fun ShareAction(post: Post) {
    val context = LocalContext.current
    PostAction(
        icon = NbIcons.Share,
        label = null,
        description = "Share post",
        enabled = true,
        onClick = {
            val url = "https://nextbench.in/post/${post.id}"
            val text = listOf(post.title.takeIf(String::isNotBlank), url).filterNotNull().joinToString("\n")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "Share post"))
        },
    )
}

@Composable
private fun PostAction(
    icon: ImageVector,
    label: String?,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = NbTheme.colors.brandTeal,
    enabled: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp = NbDimens.space8,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = when {
            selected -> selectedColor
            enabled -> NbTheme.colors.inkMuted
            else -> NbTheme.colors.inkMuted.copy(alpha = 0.45f)
        },
        animationSpec = NbMotion.interactionTween(),
        label = "post_action_color",
    )
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                scope.launch {
                    scale.snapTo(0.78f)
                    scale.animateTo(1f, NbMotion.pressSpring())
                }
                onClick()
            }
            .semantics { contentDescription = description }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(22.dp)
                .scale(scale.value),
        )
        if (label != null) {
            AnimatedContent(
                targetState = label,
                label = "post_action_count",
            ) { count ->
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun FeedLoading() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(3) { FeedSkeletonCard() }
    }
}

@Composable
private fun FeedSkeletonCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.surfaceCard)
            .padding(NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            NbSkeletonBox(Modifier.size(40.dp), radius = 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbSkeletonLine(widthFraction = 0.34f)
                NbSkeletonLine(widthFraction = 0.55f, height = 10.dp)
            }
        }
        NbSkeletonLine(widthFraction = 0.72f, height = 18.dp)
        NbSkeletonLine()
        NbSkeletonLine(widthFraction = 0.88f)
        NbSkeletonBox(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        NbSkeletonLine(widthFraction = 0.48f, height = 20.dp)
    }
    HorizontalDivider(color = NbTheme.colors.border)
}

@Composable
private fun FeedError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NbEmptyState(
            icon = NbIcons.Refresh,
            title = "Community unavailable",
            message = message,
            action = { NbButton("Try again", onRetry) },
        )
    }
}

@Composable
private fun FeedEmpty(following: Boolean, onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NbEmptyState(
            icon = NbIcons.Home,
            title = if (following) "Your following feed is quiet" else "No posts yet",
            message = if (following) {
                "Follow people you care about and their newest posts will appear here."
            } else {
                "New campus conversations will appear here as soon as they are published."
            },
            action = { NbButton("Refresh", onRefresh, variant = NbButtonVariant.Secondary) },
        )
    }
}

@Composable
private fun GuestPreviewFooter(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space24, vertical = NbDimens.space40),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.inkMuted)
        Text(
            "End of preview",
            style = MaterialTheme.typography.titleMedium,
            color = NbTheme.colors.ink,
        )
        Text(
            "Sign in to keep exploring and join the conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = NbTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        NbButton(
            text = "Sign in",
            onClick = onSignIn,
            variant = NbButtonVariant.Secondary,
        )
    }
}

@Composable
private fun FeedLoadingMore() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(NbDimens.space24),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = NbTheme.colors.brandTeal,
        )
    }
}

@Composable
private fun FeedPaginationRetry(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space20),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("Could not load more posts.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        Spacer(Modifier.width(NbDimens.space8))
        Text(
            "Retry",
            style = MaterialTheme.typography.labelMedium,
            color = NbTheme.colors.brandTeal,
            modifier = Modifier.clickable(onClick = onRetry).padding(NbDimens.space8),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FeedAccessSheet(
    request: FeedAccessRequest,
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
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (request == FeedAccessRequest.SignIn) NbIcons.Profile else NbIcons.Shield,
                    contentDescription = null,
                    tint = NbTheme.colors.brandTeal,
                )
            }
            Text(
                text = if (request == FeedAccessRequest.SignIn) "Join the conversation" else "Verify your student account",
                style = MaterialTheme.typography.titleLarge,
                color = NbTheme.colors.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (request == FeedAccessRequest.SignIn) {
                    "Sign in to like posts, save ideas, and participate in your campus community."
                } else {
                    "Student verification keeps likes and dislikes accountable while preserving anonymous posting."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
                textAlign = TextAlign.Center,
            )
            NbButton(
                text = if (request == FeedAccessRequest.SignIn) "Sign in" else "Verify now",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                variant = NbButtonVariant.Secondary,
            )
            NbButton(
                text = "Not now",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = NbButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun FeedToast(
    notice: FeedNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(notice.id) {
        delay(3_000)
        onDismiss()
    }
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(NbMotion.interactionTween()),
        exit = fadeOut(NbMotion.interactionTween()),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NbDimens.space16, vertical = NbDimens.space16),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val color = when (notice.kind) {
                FeedNoticeKind.Success -> NbTheme.colors.brandMint
                FeedNoticeKind.Error -> NbTheme.colors.brandPink
                FeedNoticeKind.Info -> NbTheme.colors.ink
            }
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

@Composable
private fun StoryMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(2_800)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space16),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NbDimens.radiusMd))
                .background(NbTheme.colors.ink)
                .clickable(onClick = onDismiss)
                .padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
        )
    }
}

private fun Post.mediaAspectRatio(): Float {
    val width = imageWidth ?: imagesDetailed.firstOrNull()?.w
    val height = imageHeight ?: imagesDetailed.firstOrNull()?.h
    return if (width != null && height != null && width > 0 && height > 0) {
        (width.toFloat() / height).coerceIn(0.8f, 1.8f)
    } else {
        4f / 3f
    }
}

private fun postTypeLabel(type: String): String = when (type) {
    "info" -> "School info"
    "notes" -> "Notes"
    "event" -> "Event"
    "confession" -> "Anonymous"
    else -> "Discussion"
}

private fun Product.looksLikeBook(): Boolean {
    val searchable = "$title $category $description".lowercase()
    return FeedBookKeywords.any(searchable::contains)
}

private const val ContentPreviewLines = 7
private const val MaxPollChoices = 6
private const val MaxMediaDots = 8
private const val PaginationThreshold = 4
private const val ProductInterval = 4
private const val ChromeRevealThresholdPx = 12
private val FeedBookKeywords = listOf("book", "textbook", "novel", "notes", "guide", "exam", "course")
