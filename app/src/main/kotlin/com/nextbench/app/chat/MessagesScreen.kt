package com.nextbench.app.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbCountBadge
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonBox
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.NbVerifiedBadge
import com.nextbench.data.firebase.ChatRoomListItem
import com.nextbench.data.model.Club
import com.nextbench.data.model.UserData

@Composable
fun MessagesScreen(
    user: UserData?,
    onOpenRoom: (String) -> Unit,
    onOpenClub: (String) -> Unit = {},
    onOpenClubs: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmBulkDelete by remember { mutableStateOf(false) }
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }
    BackHandler(enabled = state.selectionMode || confirmBulkDelete) {
        if (confirmBulkDelete) confirmBulkDelete = false else viewModel.clearSelection()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            if (state.selectionMode) {
                InboxSelectionToolbar(
                    state = state,
                    onClose = viewModel::clearSelection,
                    onPin = viewModel::bulkTogglePin,
                    onRead = viewModel::bulkToggleRead,
                    onMute = viewModel::bulkToggleMute,
                    onArchive = viewModel::bulkToggleArchive,
                    onDelete = { confirmBulkDelete = true },
                )
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.showArchived) "Archived" else "Messages",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = NbTheme.colors.ink,
                    )
                }
                ArchiveToggle(
                    showArchived = state.showArchived,
                    count = state.archivedCount,
                    onClick = viewModel::toggleArchived,
                )
                IconButton(onClick = onOpenClubs, modifier = Modifier.semantics { contentDescription = "Open clubs" }) {
                    Icon(NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal)
                }
            }
            if (!state.selectionMode) {
                NbTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search people or listings",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = { Icon(NbIcons.Search, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(19.dp)) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(NbIcons.Close, contentDescription = "Clear search", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else null,
                )
            }
        }

        HorizontalDivider(color = NbTheme.colors.border)

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> InboxSkeleton(modifier = Modifier.fillMaxSize())
                state.error != null -> InboxError(message = state.error.orEmpty(), onRetry = viewModel::retry)
                state.visibleItems.isEmpty() -> InboxEmpty(showArchived = state.showArchived, query = state.query)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NbDimens.space24),
                ) {
                    items(state.visibleItems, key = { it.listKey }) { item ->
                        when (item) {
                            is InboxItem.DirectMessage -> {
                                SwipeConversationRow(
                                    item = item.room,
                                    busy = item.room.room.id in state.busyRoomIds,
                                    selectionMode = state.selectionMode,
                                    selected = item.room.room.id in state.selectedRoomIds,
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleSelection(item.room)
                                        else onOpenRoom(item.room.room.id)
                                    },
                                    onLongPress = { viewModel.toggleSelection(item.room) },
                                    onToggleRead = { viewModel.toggleRead(item.room) },
                                    onToggleArchive = { viewModel.toggleArchive(item.room) },
                                )
                            }
                            is InboxItem.ClubItem -> {
                                ClubConversationRow(
                                    item = item,
                                    onClick = { onOpenClub(item.club.id) },
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = NbTheme.colors.border,
                        )
                    }
                }
            }
            state.notice?.let { notice ->
                InboxNoticeBanner(
                    notice = notice,
                    onDismiss = { viewModel.dismissNotice(notice.id) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    if (confirmBulkDelete) {
        BulkDeleteSheet(
            count = state.selectedRoomIds.size,
            busy = state.isBulkActionRunning,
            onConfirm = { if (viewModel.bulkDelete()) confirmBulkDelete = false },
            onDismiss = { if (!state.isBulkActionRunning) confirmBulkDelete = false },
        )
    }
}

@Composable
private fun InboxSelectionToolbar(
    state: MessagesUiState,
    onClose: () -> Unit,
    onPin: () -> Boolean,
    onRead: () -> Boolean,
    onMute: () -> Boolean,
    onArchive: () -> Boolean,
    onDelete: () -> Unit,
) {
    val busy = state.isBulkActionRunning
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.size(40.dp)) { Icon(NbIcons.Close, contentDescription = "Exit conversation selection", tint = NbTheme.colors.ink) }
            Text("${state.selectedRoomIds.size} selected", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            InboxBulkButton(if (state.allSelectedPinned) NbIcons.Bookmark else NbIcons.BookmarkFilled, if (state.allSelectedPinned) "Unpin selected" else "Pin selected", busy) { onPin() }
            InboxBulkButton(if (state.allSelectedRead) NbIcons.Messages else NbIcons.Check, if (state.allSelectedRead) "Mark selected unread" else "Mark selected read", busy) { onRead() }
            InboxBulkButton(if (state.allSelectedMuted) NbIcons.Volume else NbIcons.VolumeOff, if (state.allSelectedMuted) "Unmute selected" else "Mute selected", busy) { onMute() }
            InboxBulkButton(NbIcons.Archive, if (state.showArchived) "Restore selected" else "Archive selected", busy) { onArchive() }
            InboxBulkButton(NbIcons.Trash, "Remove selected", busy, NbTheme.colors.brandPink, onDelete)
        }
    }
}

@Composable
private fun InboxBulkButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    busy: Boolean,
    tint: Color = NbTheme.colors.inkMuted,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(38.dp).semantics { contentDescription = description }) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun ArchiveToggle(showArchived: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .background(if (showArchived) NbTheme.colors.brandTeal.copy(alpha = 0.12f) else NbTheme.colors.surfaceSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = NbDimens.space12, vertical = NbDimens.space8)
            .semantics { role = Role.Button; contentDescription = if (showArchived) "Show active conversations" else "Show archived conversations" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space4),
    ) {
        Icon(NbIcons.Archive, contentDescription = null, tint = if (showArchived) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted, modifier = Modifier.size(17.dp))
        Text(
            text = if (showArchived) "Inbox" else "Archived",
            style = MaterialTheme.typography.labelMedium,
            color = if (showArchived) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted,
        )
        if (!showArchived) NbCountBadge(count = count, color = NbTheme.colors.inkMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeConversationRow(
    item: ChatRoomListItem,
    busy: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (!busy) when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead()
                SwipeToDismissBoxValue.EndToStart -> onToggleArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.24f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !busy && !selectionMode,
        enableDismissFromEndToStart = !busy && !selectionMode,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize().background(NbTheme.colors.surfaceSoft).padding(horizontal = NbDimens.space20),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwipeHint(
                    icon = if (item.hasUnreadActivity) NbIcons.Check else NbIcons.Messages,
                    label = if (item.hasUnreadActivity) "Read" else "Unread",
                    color = NbTheme.colors.brandTeal,
                )
                Spacer(Modifier.weight(1f))
                SwipeHint(
                    icon = NbIcons.Archive,
                    label = if (item.archived) "Restore" else "Archive",
                    color = NbTheme.colors.brandPink,
                )
            }
        },
    ) {
        ConversationRow(item = item, busy = busy, selected = selected, selectionMode = selectionMode, onClick = onClick, onLongPress = onLongPress)
    }
}

@Composable
private fun SwipeHint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ConversationRow(item: ChatRoomListItem, busy: Boolean, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val other = item.otherUser
    val name = other?.name?.ifBlank { null } ?: "NextBench member"
    val preview = item.room.lastMessage?.takeIf(String::isNotBlank)
        ?: if (item.room.status == "pending") "Chat request waiting for your response" else "Start a conversation"
    val timestamp = item.room.updatedAt?.toDate()?.time?.let(::formatRelativeTime)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) NbTheme.colors.brandTeal.copy(alpha = 0.08f) else NbTheme.colors.surfaceBase)
            .pointerInput(item.room.id, busy) {
                detectTapGestures(
                    onTap = { if (!busy) onClick() },
                    onLongPress = { if (!busy) onLongPress() },
                )
            }
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(modifier = Modifier.padding(end = NbDimens.space12).size(24.dp).clip(CircleShape).background(if (selected) NbTheme.colors.brandTeal else NbTheme.colors.surfaceSoft), contentAlignment = Alignment.Center) {
                if (selected) Icon(NbIcons.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Box {
            NbAvatar(imageUrl = other?.profilePicture, name = name, size = NbDimens.avatarLg)
            if (item.unread) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(NbTheme.colors.brandPink)
                        .align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (item.unread) FontWeight.Bold else FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (other?.verified == true) NbVerifiedBadge(size = 14.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (item.unread) FontWeight.Medium else FontWeight.Normal), color = if (item.unread) NbTheme.colors.ink else NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!item.room.productTitle.isNullOrBlank()) NbPill(label = item.room.productTitle.orEmpty(), contentColor = NbTheme.colors.brandTeal, modifier = Modifier)
            }
        }
        Spacer(Modifier.width(NbDimens.space8))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                if (item.pinned) Icon(NbIcons.BookmarkFilled, contentDescription = "Pinned", tint = NbTheme.colors.brandTeal, modifier = Modifier.size(13.dp))
                if (item.muted) Icon(NbIcons.VolumeOff, contentDescription = "Muted", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(13.dp))
                timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (item.unread) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted) }
            }
            if (item.unread) NbCountBadge(count = 1)
        }
    }
}

@Composable
private fun ClubConversationRow(item: InboxItem.ClubItem, onClick: () -> Unit) {
    val club = item.club
    val preview = club.lastMessage?.takeIf(String::isNotBlank) ?: "Start the conversation"
    val timestamp = club.updatedAt?.toDate()?.time?.let(::formatRelativeTime)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.surfaceBase)
            .clickable(onClick = onClick)
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Club avatar — teal rounded square with icon (matches ClubChatHeader style)
        Box(
            modifier = Modifier
                .size(NbDimens.avatarLg)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(NbDimens.radiusMd))
                .background(NbTheme.colors.brandTeal.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!club.avatar.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = club.avatar,
                    contentDescription = club.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    NbIcons.Messages,
                    contentDescription = null,
                    tint = NbTheme.colors.brandTeal,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (item.unread) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(NbTheme.colors.brandPink)
                        .align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Text(
                club.name.ifBlank { "Campus club" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (item.unread) FontWeight.Bold else FontWeight.SemiBold),
                color = NbTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (item.unread) FontWeight.Medium else FontWeight.Normal),
                color = if (item.unread) NbTheme.colors.ink else NbTheme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(NbDimens.space8))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                if (item.isPinned) Icon(NbIcons.BookmarkFilled, contentDescription = "Pinned", tint = NbTheme.colors.brandTeal, modifier = Modifier.size(13.dp))
                if (item.muted) Icon(NbIcons.VolumeOff, contentDescription = "Muted", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(13.dp))
                timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (item.unread) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted) }
            }
            if (item.unread) NbCountBadge(count = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkDeleteSheet(count: Int, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Text("Remove $count conversation${if (count == 1) "" else "s"}?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            Text("Messages are not deleted. A conversation returns if it receives new activity.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbButton("Cancel", onDismiss, enabled = !busy, modifier = Modifier.weight(1f), variant = NbButtonVariant.Secondary)
                NbButton("Remove", onConfirm, enabled = count > 0 && !busy, loading = busy, modifier = Modifier.weight(1f), variant = NbButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun InboxNoticeBanner(notice: ChatNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(notice.id) {
        kotlinx.coroutines.delay(2800)
        onDismiss()
    }
    val color = if (notice.kind == ChatNoticeKind.Error) NbTheme.colors.brandPink else NbTheme.colors.ink
    Text(
        text = notice.message,
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth().background(color).clickable(onClick = onDismiss).padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
    )
}

@Composable
private fun InboxSkeleton(modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space20)) {
        items(6) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NbSkeletonBox(Modifier.size(NbDimens.avatarLg), NbDimens.avatarLg / 2)
                Spacer(Modifier.width(NbDimens.space12))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    NbSkeletonLine(widthFraction = 0.48f, height = 14.dp)
                    NbSkeletonLine(widthFraction = 0.78f, height = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun InboxError(message: String, onRetry: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Refresh,
        title = "Messages are taking a moment",
        message = message,
        action = { Text("Tap to retry", color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = onRetry).padding(NbDimens.space8)) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun InboxEmpty(showArchived: Boolean, query: String) {
    val filtered = query.isNotBlank()
    NbEmptyState(
        icon = if (filtered) NbIcons.Search else if (showArchived) NbIcons.Archive else NbIcons.Messages,
        title = when {
            filtered -> "No conversations found"
            showArchived -> "Nothing archived yet"
            else -> "Your inbox is clear"
        },
        message = when {
            filtered -> "Try a different name or listing title."
            showArchived -> "Archived conversations will stay here until you bring them back."
            else -> "When you connect with a seller or classmate, the conversation will appear here."
        },
        modifier = Modifier.fillMaxSize(),
    )
}
