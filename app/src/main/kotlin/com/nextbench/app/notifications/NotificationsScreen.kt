package com.nextbench.app.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.core.content.ContextCompat
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.designsystem.NbCountBadge
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.model.Notification
import com.nextbench.data.model.NotificationType
import com.nextbench.data.model.UserData

@Composable
fun NotificationsScreen(
    user: UserData?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pushPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val pushPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pushPermissionGranted = granted
    }
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space12), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text("Notifications", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text(if (state.unreadTotal > 0) "${state.unreadTotal} unread" else "You are all caught up", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            if (state.unreadCount > 0) {
                Text("Mark all read", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = { viewModel.markAllRead() }).padding(NbDimens.space8))
            }
        }
        if (!pushPermissionGranted) {
            Row(modifier = Modifier.fillMaxWidth().background(NbTheme.colors.brandTeal.copy(alpha = 0.08f)).padding(horizontal = NbDimens.space16, vertical = NbDimens.space8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                Icon(NbIcons.Bell, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(19.dp))
                Text("Get updates even when NextBench is closed.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
                Text(
                    "Enable",
                    style = MaterialTheme.typography.labelMedium,
                    color = NbTheme.colors.brandTeal,
                    modifier = Modifier
                        .clickable {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pushPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        .padding(NbDimens.space8),
                )
            }
        }
        FilterRow(state = state, onSelect = viewModel::selectFilter)
        HorizontalDivider(color = NbTheme.colors.border)
        when {
            state.isLoading -> NotificationsLoading()
            state.error != null -> NbEmptyState(icon = NbIcons.Refresh, title = "Notifications are taking a moment", message = state.error.orEmpty(), action = { Text("Tap to retry", color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = viewModel::retry).padding(NbDimens.space8)) }, modifier = Modifier.fillMaxSize())
            state.visibleNotifications.isEmpty() -> NbEmptyState(icon = NbIcons.Bell, title = "Nothing new here", message = if (state.filter == NotificationFilter.All) "Approvals, replies, marketplace updates, and mentions will appear here." else "There are no ${state.filter.label.lowercase()} notifications right now.", modifier = Modifier.fillMaxSize())
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NbDimens.space24)) {
                items(state.visibleNotifications, key = { it.id }) { notification ->
                    NotificationRow(notification = notification, busy = notification.id in state.busyIds, onClick = {
                        viewModel.markRead(notification)
                        notification.link?.let(onOpenLink)
                    }, onDelete = { viewModel.delete(notification) })
                    HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = NbTheme.colors.border)
                }
            }
        }
        state.notice?.let { notice -> NoticeBanner(notice, onDismiss = { viewModel.dismissNotice(notice.id) }) }
    }
}

@Composable
private fun FilterRow(state: NotificationsUiState, onSelect: (NotificationFilter) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space4), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        NotificationFilter.entries.forEach { filter ->
            val selected = filter == state.filter
            Row(modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (selected) NbTheme.colors.ink else NbTheme.colors.surfaceSoft).clickable(role = Role.Tab, onClick = { onSelect(filter) }).padding(horizontal = NbDimens.space12, vertical = NbDimens.space8).semantics { role = Role.Tab; contentDescription = "Filter ${filter.label}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(filter.label, style = MaterialTheme.typography.labelMedium, color = if (selected) NbTheme.colors.surfaceBase else NbTheme.colors.inkMuted)
                NbCountBadge(count = state.counts[filter] ?: 0, color = if (selected) NbTheme.colors.brandPink else NbTheme.colors.brandPink.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: Notification, busy: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val accent = notificationAccent(notification.type)
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (!notification.read) accent.copy(alpha = 0.055f) else Color.Transparent).padding(horizontal = NbDimens.space16, vertical = NbDimens.space14), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(notificationIcon(notification.type), contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(notification.title.ifBlank { notificationTypeTitle(notification.type) }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (!notification.read) FontWeight.Bold else FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!notification.read) Box(Modifier.size(8.dp).clip(CircleShape).background(NbTheme.colors.brandPink))
            }
            Text(notification.message.ifBlank { "You have an update from NextBench." }, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(notification.createdAt?.toDate()?.time?.let(::formatRelativeTime) ?: "just now", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint)
        }
        IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Delete notification" }) { Icon(NbIcons.Close, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun NotificationsLoading() {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space20)) {
        items(7) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).clip(CircleShape).background(NbTheme.colors.surfaceSoft)); Spacer(Modifier.width(NbDimens.space12)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) { NbSkeletonLine(widthFraction = 0.48f); NbSkeletonLine(widthFraction = 0.82f, height = 12.dp) } } }
    }
}

@Composable
private fun NoticeBanner(notice: NotificationNotice, onDismiss: () -> Unit) {
    val color = if (notice.kind == NotificationNoticeKind.Error) NbTheme.colors.brandPink else NbTheme.colors.ink
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space16)) {
        LaunchedEffect(notice.id) { kotlinx.coroutines.delay(2800); onDismiss() }
        Text(notice.message, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusMd)).background(color).clickable(onClick = onDismiss).padding(horizontal = NbDimens.space14, vertical = NbDimens.space12))
    }
}

private fun notificationIcon(type: String) = when (NotificationType.from(type)) {
    NotificationType.UserApproved, NotificationType.AdminPromoted -> NbIcons.Shield
    NotificationType.ListingApproved, NotificationType.ListingRejected, NotificationType.ItemReserved, NotificationType.ItemSold -> NbIcons.Marketplace
    NotificationType.NewMessage -> NbIcons.Messages
    NotificationType.NewReview -> NbIcons.Heart
    NotificationType.Mention -> NbIcons.Profile
    NotificationType.NewPost, null -> NbIcons.Bell
}

@Composable
private fun notificationAccent(type: String) = when (NotificationType.from(type)) {
    NotificationType.ListingRejected -> NbTheme.colors.brandPink
    NotificationType.ItemReserved -> Color(0xFFB7791F)
    NotificationType.NewReview -> Color(0xFFE0A800)
    NotificationType.NewMessage, NotificationType.Mention -> NbTheme.colors.brandPink
    NotificationType.UserApproved, NotificationType.AdminPromoted -> NbTheme.colors.brandTeal
    else -> NbTheme.colors.brandMint
}

private fun notificationTypeTitle(type: String) = NotificationType.from(type)?.raw?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "NextBench update"
