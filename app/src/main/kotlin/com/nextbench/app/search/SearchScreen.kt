package com.nextbench.app.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.common.formatRupees
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.model.Club
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData

@Composable
fun SearchScreen(
    user: UserData?,
    onOpenProfile: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenClub: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(user?.uid, user?.school, user?.city) { viewModel.syncViewer(user) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        NbTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search NextBench",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submitQuery() }),
            leadingIcon = {
                Icon(
                    NbIcons.Search,
                    contentDescription = null,
                    tint = NbTheme.colors.inkMuted,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = if (state.query.isNotBlank()) {
                {
                    IconButton(
                        onClick = viewModel::clearQuery,
                        modifier = Modifier.semantics { contentDescription = "Clear search" },
                    ) {
                        Icon(NbIcons.Close, contentDescription = null, tint = NbTheme.colors.inkMuted)
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
        )

        val error = state.error
        val hasResults = state.people.isNotEmpty() || state.posts.isNotEmpty() || state.listings.isNotEmpty() || state.clubs.isNotEmpty()
        when {
            state.isLoading && !state.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NbTheme.colors.brandTeal)
                }
            }

            error != null && !hasResults -> {
                NbEmptyState(
                    icon = NbIcons.Refresh,
                    title = "Discovery is unavailable",
                    message = error,
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Text(
                            text = "Try again",
                            color = NbTheme.colors.brandTeal,
                            modifier = Modifier
                                .clickable(onClick = viewModel::retry)
                                .padding(NbDimens.space8),
                        )
                    },
                )
            }

            state.query.isBlank() -> {
                DiscoveryResults(
                    state = state,
                    onOpenProfile = onOpenProfile,
                    onOpenPost = onOpenPost,
                    onOpenListing = onOpenListing,
                    onOpenClub = onOpenClub,
                    onUseRecentSearch = viewModel::useRecentSearch,
                    onClearRecentSearches = viewModel::clearRecentSearches,
                )
            }

            else -> {
                SearchTabs(state, viewModel::selectTab)
                SearchResults(
                    state = state,
                    onOpenProfile = { id -> viewModel.recordCurrentQuery(); onOpenProfile(id) },
                    onOpenPost = { id -> viewModel.recordCurrentQuery(); onOpenPost(id) },
                    onOpenListing = { id -> viewModel.recordCurrentQuery(); onOpenListing(id) },
                    onOpenClub = { id -> viewModel.recordCurrentQuery(); onOpenClub(id) },
                )
            }
        }
    }
}

@Composable
private fun DiscoveryResults(
    state: SearchUiState,
    onOpenProfile: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenClub: (String) -> Unit,
    onUseRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
) {
    val trending = state.posts.sortedWith(
        compareByDescending<Post> { it.isHot }
            .thenByDescending { it.upvotesCount + it.repliesCount },
    )
    val books = state.listings.filter(Product::looksLikeBook)
    val featuredListings = (books.ifEmpty { state.listings }).take(10)

    if (trending.isEmpty() && featuredListings.isEmpty() && state.people.isEmpty() && state.clubs.isEmpty() && state.recentSearches.isEmpty()) {
        NbEmptyState(
            icon = NbIcons.Search,
            title = "Nothing to discover yet",
            message = "Fresh campus recommendations will appear here.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NbDimens.space24),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space24),
    ) {
        if (state.recentSearches.isNotEmpty()) {
            item(key = "recent_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space2),
                    ) {
                        Text("Recent searches", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                        Text("Pick up where you left off", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NbTheme.colors.brandPink,
                        modifier = Modifier.clickable(onClick = onClearRecentSearches).padding(NbDimens.space8),
                    )
                }
            }
            item(key = "recent_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NbDimens.space16),
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                ) {
                    state.recentSearches.forEach { query ->
                        item(key = "recent_$query") {
                            NbPill(query, contentColor = NbTheme.colors.ink, modifier = Modifier.clickable { onUseRecentSearch(query) })
                        }
                    }
                }
            }
        }

        if (trending.isNotEmpty()) {
            item(key = "trending_header") {
                DiscoveryHeader(
                    title = "Trending now",
                    subtitle = "What your campus is talking about",
                )
            }
            trending.take(4).forEachIndexed { index, post ->
                item(key = "trending_${post.id}") {
                    TrendingPostRow(
                        post = post,
                        rank = index + 1,
                        onOpen = onOpenPost,
                    )
                }
            }
        }

        if (featuredListings.isNotEmpty()) {
            item(key = "books_header") {
                DiscoveryHeader(
                    title = if (books.isNotEmpty()) "Books for your bench" else "Recommended finds",
                    subtitle = "Useful picks from the NextBench community",
                )
            }
            item(key = "books_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NbDimens.space16),
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                ) {
                    featuredListings.forEach { product ->
                        item(key = product.id) {
                            DiscoveryBookCard(product, onOpenListing)
                        }
                    }
                }
            }
        }

        if (state.people.isNotEmpty()) {
            item(key = "people_header") {
                DiscoveryHeader(
                    title = "People to know",
                    subtitle = "Recommended from your campus and city",
                )
            }
            item(key = "people_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NbDimens.space16),
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                ) {
                    state.people.take(12).forEach { person ->
                        item(key = person.uid) {
                            DiscoveryPerson(person, onOpenProfile)
                        }
                    }
                }
            }
        }

        if (state.clubs.isNotEmpty()) {
            item(key = "clubs_header") {
                DiscoveryHeader(title = "Clubs to explore", subtitle = "Find your next campus community")
            }
            item(key = "clubs_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NbDimens.space16),
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                ) {
                    state.clubs.take(8).forEach { club ->
                        item(key = "club_${club.id}") { DiscoveryClub(club, onOpenClub) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(horizontal = NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space2),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = NbTheme.colors.ink,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NbTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun TrendingPostRow(post: Post, rank: Int, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(post.id) }
            .padding(horizontal = NbDimens.space16),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (rank == 1) NbTheme.colors.brandPink else NbTheme.colors.inkFaint,
            modifier = Modifier.width(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.authorName.ifBlank { "NextBench member" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                post.createdAt?.toDate()?.time?.let { createdAt ->
                    Text(
                        text = formatRelativeTime(createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = NbTheme.colors.inkFaint,
                    )
                }
            }
            Text(
                text = post.title.ifBlank { post.content.ifBlank { "Untitled post" } },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NbTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${post.upvotesCount} upvotes  ·  ${post.repliesCount} replies",
                style = MaterialTheme.typography.labelSmall,
                color = NbTheme.colors.inkMuted,
            )
            HorizontalDivider(
                modifier = Modifier.padding(top = NbDimens.space8),
                color = NbTheme.colors.border,
            )
        }
    }
}

@Composable
private fun DiscoveryBookCard(product: Product, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(164.dp)
            .pressScale(onTap = { onOpen(product.id) }),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        AsyncImage(
            model = product.images.firstOrNull() ?: product.image,
            contentDescription = product.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .background(NbTheme.colors.surfaceSoft),
        )
        Text(
            text = product.title.ifBlank { "Untitled listing" },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = NbTheme.colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatRupees(product.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = NbTheme.colors.brandTeal,
        )
    }
}

@Composable
private fun DiscoveryPerson(user: UserData, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .pressScale(onTap = { onOpen(user.uid) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            NbAvatar(imageUrl = user.profilePicture, name = user.name, size = 72.dp)
            if (user.verified) {
                Surface(
                    color = NbTheme.colors.brandTeal,
                    shape = CircleShape,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = NbIcons.Check,
                        contentDescription = "Verified",
                        tint = Color.White,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        Text(
            text = user.name.ifBlank { "Student" },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = NbTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = user.school.ifBlank { user.city.ifBlank { "NextBench" } },
            style = MaterialTheme.typography.labelSmall,
            color = NbTheme.colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchTabs(state: SearchUiState, onSelect: (SearchTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16),
    ) {
        SearchTab.entries.forEach { tab ->
            val selected = tab == state.selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics {
                        role = Role.Tab
                        contentDescription = tab.label()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.labelWithCount(state),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (selected) NbTheme.colors.ink else NbTheme.colors.inkMuted,
                    modifier = Modifier.padding(vertical = NbDimens.space12),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) NbTheme.colors.brandPink else NbTheme.colors.border),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onOpenProfile: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenClub: (String) -> Unit,
) {
    val isEmpty = when (state.selectedTab) {
        SearchTab.Posts -> state.posts.isEmpty()
        SearchTab.Books -> state.listings.isEmpty()
        SearchTab.People -> state.people.isEmpty()
        SearchTab.Clubs -> state.clubs.isEmpty()
    }
    if (isEmpty) {
        NbEmptyState(
            icon = NbIcons.Search,
            title = "Nothing matched",
            message = "Try another name, title, topic, or campus.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    AnimatedContent(
        targetState = state.selectedTab,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "search_results",
    ) { tab ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space16),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab) {
                SearchTab.Posts -> state.posts.forEach { post ->
                    item(key = "post_${post.id}") { PostRow(post, onOpenPost) }
                }
                SearchTab.Books -> state.listings.forEach { listing ->
                    item(key = "listing_${listing.id}") { ListingRow(listing, onOpenListing) }
                }
                SearchTab.People -> state.people.forEach { person ->
                    item(key = "person_${person.uid}") { PersonRow(person, onOpenProfile) }
                }
                SearchTab.Clubs -> state.clubs.forEach { club ->
                    item(key = "club_${club.id}") { ClubRow(club, onOpenClub) }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryClub(club: Club, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.width(164.dp).pressScale(onTap = { onOpen(club.id) }),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (club.avatar.isNullOrBlank()) Icon(NbIcons.Messages, contentDescription = club.name, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(36.dp))
            else AsyncImage(model = club.avatar, contentDescription = club.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(club.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${club.memberCount} members", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun ClubRow(club: Club, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(club.id) }.padding(vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (club.avatar.isNullOrBlank()) Icon(NbIcons.Messages, contentDescription = club.name, tint = NbTheme.colors.brandTeal)
            else AsyncImage(model = club.avatar, contentDescription = club.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(club.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(club.school.takeIf(String::isNotBlank), "${club.memberCount} members").joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint)
    }
}

@Composable
private fun PersonRow(user: UserData, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(user.uid) }
            .padding(vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NbAvatar(imageUrl = user.profilePicture, name = user.name, size = 52.dp)
        Column(
            modifier = Modifier
                .padding(start = NbDimens.space12)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.name.ifBlank { "Student" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.ink,
                )
                if (user.verified) {
                    Spacer(Modifier.size(NbDimens.space4))
                    NbPill("Verified", contentColor = NbTheme.colors.brandTeal)
                }
            }
            Text(
                text = listOfNotNull(
                    user.username?.let { "@$it" },
                    user.school.takeIf(String::isNotBlank),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint)
    }
}

@Composable
private fun PostRow(post: Post, onOpen: (String) -> Unit) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusSm),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(onTap = { onOpen(post.id) }),
    ) {
        Column(
            modifier = Modifier.padding(NbDimens.space14),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            Row {
                NbPill(
                    post.type.ifBlank { "Post" }.replaceFirstChar(Char::uppercase),
                    contentColor = NbTheme.colors.brandTeal,
                )
                Spacer(Modifier.weight(1f))
                post.createdAt?.toDate()?.time?.let { createdAt ->
                    Text(
                        text = formatRelativeTime(createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = NbTheme.colors.inkFaint,
                    )
                }
            }
            Text(
                text = post.title.ifBlank { post.content.ifBlank { "Untitled post" } },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NbTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    post.school.takeIf(String::isNotBlank),
                    "${post.repliesCount} replies",
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = NbTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun ListingRow(product: Product, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(product.id) }
            .padding(vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = product.images.firstOrNull() ?: product.image,
            contentDescription = product.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .background(NbTheme.colors.surfaceSoft),
        )
        Column(
            modifier = Modifier
                .padding(start = NbDimens.space12)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space4),
        ) {
            Text(
                text = product.title.ifBlank { "Untitled listing" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NbTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatRupees(product.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = NbTheme.colors.brandTeal,
            )
            Text(
                text = product.category.ifBlank { product.condition },
                style = MaterialTheme.typography.labelSmall,
                color = NbTheme.colors.inkMuted,
            )
        }
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint)
    }
}

private fun Product.looksLikeBook(): Boolean {
    val searchable = "$title $category $description".lowercase()
    return BookKeywords.any(searchable::contains)
}

private val BookKeywords = listOf("book", "textbook", "novel", "notes", "guide", "exam", "course")

private fun SearchTab.label() = when (this) {
    SearchTab.Posts -> "Posts"
    SearchTab.Books -> "Books"
    SearchTab.People -> "People"
    SearchTab.Clubs -> "Clubs"
}

private fun SearchTab.labelWithCount(state: SearchUiState) = when (this) {
    SearchTab.Posts -> "Posts ${state.posts.size}"
    SearchTab.Books -> "Books ${state.listings.size}"
    SearchTab.People -> "People ${state.people.size}"
    SearchTab.Clubs -> "Clubs ${state.clubs.size}"
}
