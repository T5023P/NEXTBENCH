package com.nextbench.app.marketplace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nextbench.core.common.formatRupees
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonBox
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.firebase.SavedListing
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.UserData

@Composable
fun WishlistScreen(
    user: UserData?,
    onOpenProduct: (String) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WishlistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space12), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text("Saved listings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("${state.items.size} ${if (state.items.size == 1) "item" else "items"} kept close for later.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Icon(NbIcons.Heart, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(26.dp))
        }

        when {
            state.isLoading -> WishlistLoading()
            state.error != null -> NbEmptyState(icon = NbIcons.Refresh, title = "Saved listings are taking a moment", message = state.error.orEmpty(), action = { Text("Tap to retry", color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = viewModel::retry).padding(NbDimens.space8)) }, modifier = Modifier.fillMaxSize())
            state.items.isEmpty() -> NbEmptyState(icon = NbIcons.Heart, title = "Nothing saved yet", message = "Browse the marketplace and bookmark listings you want to come back to.", action = { NbButton("Explore marketplace", onBrowse, variant = NbButtonVariant.Secondary) }, modifier = Modifier.fillMaxSize())
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = NbDimens.space12, vertical = NbDimens.space8),
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                items(state.items, key = { it.wishlistId }) { item ->
                    SavedListingCard(
                        item = item,
                        busy = item.wishlistId in state.busyIds,
                        onOpen = { onOpenProduct(item.product.id) },
                        onRemove = { viewModel.remove(item) },
                    )
                }
            }
        }
        state.notice?.let { notice -> WishlistNotice(notice, onDismiss = { viewModel.dismissNotice(notice.id) }) }
    }
}

@Composable
private fun SavedListingCard(item: SavedListing, busy: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val product = item.product
    val image = product.images.firstOrNull() ?: product.image
    val imageRequest = image?.let { ImageRequest.Builder(LocalContext.current).data(it).crossfade(250).build() }
    val status = ProductStatus.from(product.status)
    val unavailable = status != ProductStatus.Available
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(NbTheme.colors.surfaceCard)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(NbTheme.colors.surfaceSoft)) {
            if (imageRequest != null) AsyncImage(model = imageRequest, contentDescription = product.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(NbIcons.Marketplace, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.align(Alignment.Center).size(40.dp))
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.44f)))))
            IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.align(Alignment.TopEnd).padding(NbDimens.space8).size(34.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.surfaceCard.copy(alpha = 0.9f)).semantics { contentDescription = "Remove ${product.title} from saved listings" }) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NbTheme.colors.brandPink, strokeWidth = 2.dp) else Icon(NbIcons.Close, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(17.dp))
            }
            if (unavailable) NbPill(label = statusLabel(status), contentColor = Color.White, containerColor = Color.Black.copy(alpha = 0.58f), modifier = Modifier.align(Alignment.BottomStart).padding(NbDimens.space8))
        }
        Column(modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space12), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Text(product.title.ifBlank { "Untitled listing" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                NbAvatar(imageUrl = product.sellerProfilePicture, name = product.sellerName, size = 22.dp)
                Text(product.sellerName.ifBlank { "Student" }, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatRupees(product.price.toInt()), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
                Text(product.condition.ifBlank { "Used" }, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.brandTeal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun statusLabel(status: ProductStatus): String = when (status) {
    ProductStatus.Reserved -> "Reserved"
    ProductStatus.Sold -> "Sold out"
    ProductStatus.Pending -> "Under review"
    ProductStatus.Rejected -> "Unavailable"
    ProductStatus.Available -> "Available"
}

@Composable
private fun WishlistLoading() {
    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(NbDimens.space12), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), verticalArrangement = Arrangement.spacedBy(NbDimens.space12), userScrollEnabled = false) {
        items(6) {
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbSkeletonBox(Modifier.fillMaxWidth().aspectRatio(4f / 3f), radius = NbDimens.radiusSm)
                NbSkeletonLine(widthFraction = 0.72f, height = 14.dp)
                NbSkeletonLine(widthFraction = 0.48f, height = 12.dp)
            }
        }
    }
}

@Composable
private fun WishlistNotice(notice: WishlistNotice, onDismiss: () -> Unit) {
    val color = if (notice.kind == WishlistNoticeKind.Error) NbTheme.colors.brandPink else NbTheme.colors.ink
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space16)) {
        LaunchedEffect(notice.id) { kotlinx.coroutines.delay(2800); onDismiss() }
        Surface(color = color, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss)) {
            Text(notice.message, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(horizontal = NbDimens.space14, vertical = NbDimens.space12))
        }
    }
}
