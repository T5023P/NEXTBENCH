package com.nextbench.app.share

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nextbench.app.chat.PreparedChatAttachment
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.ForwardTargetType
import com.nextbench.data.model.UserData

@Composable
fun ShareTargetScreen(
    user: UserData?,
    incomingIntent: Intent?,
    onIntentConsumed: (Intent) -> Unit,
    onSent: (ForwardTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(user?.uid, incomingIntent) {
        if (incomingIntent == null) viewModel.onMissingIntent() else viewModel.sync(user, incomingIntent, onIntentConsumed)
    }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space16),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text("Choose where to send", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                    Text("Your shared content stays editable until you send it.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                }
            }
            item {
                NbTextField(
                    value = state.text,
                    onValueChange = viewModel::setText,
                    placeholder = "Add a message",
                    singleLine = false,
                    maxLines = 6,
                )
            }
            if (!state.isPreparing && state.text.isBlank() && state.attachments.isEmpty() && state.error == null) {
                item {
                    NbEmptyState(
                        icon = NbIcons.Share,
                        title = "Nothing to share yet",
                        message = "Share text, a link, photo, video, or document from another app.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.isPreparing || state.attachments.isNotEmpty()) {
                item {
                    if (state.isPreparing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                            Text("Preparing shared files", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                        }
                    } else {
                        SharedAttachmentStrip(state.attachments, viewModel::removeAttachment)
                    }
                }
            }
            item {
                NbTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search conversations and clubs",
                    singleLine = true,
                    leadingIcon = { Icon(NbIcons.Search, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(18.dp)) },
                )
            }
            when {
                state.isLoadingTargets -> item { Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = NbTheme.colors.brandTeal) } }
                state.visibleTargets.isEmpty() -> item { NbEmptyState(icon = NbIcons.Messages, title = "No conversations", message = "Start a chat or join a club before sharing into NextBench.", modifier = Modifier.fillMaxWidth()) }
                else -> items(state.visibleTargets, key = { "${it.type}:${it.id}" }) { target ->
                    ShareTargetRow(target, selected = state.selectedTarget == target, onClick = { viewModel.selectTarget(target) })
                }
            }
            state.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.brandPink) } }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space12)) {
            NbButton(
                text = state.selectedTarget?.let { "Send to ${it.name}" } ?: "Choose a conversation",
                onClick = { user?.let { viewModel.send(it, onSent) } },
                enabled = state.canSend && user != null,
                loading = state.isSending,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SharedAttachmentStrip(attachments: List<PreparedChatAttachment>, onRemove: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        items(attachments.indices.toList(), key = { attachments[it].file.path }) { index ->
            val attachment = attachments[index]
            Box(modifier = Modifier.width(132.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft)) {
                if (attachment.mimeType.startsWith("image/")) {
                    AsyncImage(attachment.previewUri, attachment.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().size(132.dp, 96.dp))
                } else {
                    Box(modifier = Modifier.fillMaxWidth().size(132.dp, 96.dp), contentAlignment = Alignment.Center) {
                        Icon(if (attachment.mimeType.startsWith("video/")) NbIcons.Play else NbIcons.FileText, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(34.dp)) {
                    Icon(NbIcons.Close, contentDescription = "Remove ${attachment.displayName}", tint = NbTheme.colors.ink)
                }
                Text(attachment.displayName, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(NbTheme.colors.surfaceCard.copy(alpha = 0.92f)).padding(NbDimens.space8))
            }
        }
    }
}

@Composable
private fun ShareTargetRow(target: ForwardTarget, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).background(if (selected) NbTheme.colors.brandTeal.copy(alpha = 0.1f) else NbTheme.colors.surfaceCard).clickable(onClick = onClick).padding(NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        if (target.type == ForwardTargetType.Direct) {
            NbAvatar(imageUrl = target.avatar, name = target.name, size = 44.dp)
        } else {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft), contentAlignment = Alignment.Center) {
                Icon(NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(target.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (target.type == ForwardTargetType.Direct) "Direct message" else "Club", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
        }
        Icon(if (selected) NbIcons.Check else NbIcons.ArrowRight, contentDescription = null, tint = if (selected) NbTheme.colors.brandTeal else NbTheme.colors.inkFaint)
    }
}
