package com.nextbench.app.clubs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.app.chat.PreparedChatAttachment
import com.nextbench.app.chat.ChatVoicePlaybackState
import com.nextbench.app.chat.VoiceMessageBubble
import com.nextbench.app.chat.ChatLinkPreview
import com.nextbench.app.chat.VoiceRecordingControls
import com.nextbench.data.model.Club
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import com.nextbench.data.firebase.LinkPreview
import com.nextbench.data.firebase.firstMessageUrl

@Composable
fun ClubChatScreen(
    user: UserData?,
    onOpenSettings: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClubChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewerId = user?.uid
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var typingClock by remember { mutableStateOf(System.currentTimeMillis()) }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::prepareAttachment) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::prepareAttachment) }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoiceRecording() else viewModel.onMicrophonePermissionDenied()
    }
    val startVoiceRecording: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.startVoiceRecording()
        else voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex) }
    val activeTypers = state.club?.typingUsers.orEmpty().filter { (uid, timestamp) ->
        uid != viewerId && typingClock - timestamp.toDate().time < 5_000L
    }
    LaunchedEffect(state.club?.typingUsers) {
        if (state.club?.typingUsers.isNullOrEmpty()) return@LaunchedEffect
        while (true) { typingClock = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L) }
    }
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.onScreenDisposed() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.onScreenDisposed() }
    }
    BackHandler(enabled = state.actionMessage != null || showAttachmentPicker || state.isRecordingVoice) {
        when {
            state.isRecordingVoice -> viewModel.cancelVoiceRecording()
            showAttachmentPicker -> showAttachmentPicker = false
            else -> viewModel.closeMessageActions()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        when {
            state.isLoading && state.club == null -> ClubChatLoading()
            state.error != null && state.club == null -> NbEmptyState(icon = NbIcons.Messages, title = "Club unavailable", message = state.error.orEmpty(), modifier = Modifier.fillMaxSize())
            else -> {
                ClubChatHeader(club = state.club, typingCount = activeTypers.size, onSettings = { clubId -> onOpenSettings(clubId) })
                HorizontalDivider(color = NbTheme.colors.border)
                Box(modifier = Modifier.weight(1f)) {
                    if (state.messages.isEmpty()) {
                        NbEmptyState(icon = NbIcons.Messages, title = "Start the conversation", message = "Share something useful, kind, or interesting with the club.", modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                            item { ClubConversationIntro(club = state.club) }
                            itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                                ClubMessageBubble(
                                    message = message,
                                    isViewer = message.senderId == viewerId,
                                    linkPreview = message.text?.let(::firstMessageUrl)?.let(state.linkPreviews::get),
                                    showSender = index == 0 || state.messages[index - 1].senderId != message.senderId,
                                    onOpenProfile = { if (message.senderId.isNotBlank()) onOpenProfile(message.senderId) },
                                    onLongPress = { viewModel.openMessageActions(message) },
                                    onRead = if (message.senderId != viewerId && viewerId != null) {{ viewModel.markMessageRead(message.id) }} else null,
                                    playback = state.voicePlayback,
                                    onToggleVoice = viewModel::toggleVoicePlayback,
                                    onSeekVoice = viewModel::seekVoicePlayback,
                                    onCycleVoiceSpeed = viewModel::cycleVoicePlaybackSpeed,
                                )
                            }
                        }
                    }
                    state.notice?.let { notice -> ClubNoticeBanner(notice, onDismiss = { viewModel.dismissNotice(notice.id) }, modifier = Modifier.align(Alignment.TopCenter)) }
                }
                val club = state.club
                val canPost = state.canPost(viewerId)
                val canSend = state.canSend(viewerId)
                if (club != null && !state.isMember(viewerId)) {
                    Surface(color = NbTheme.colors.surfaceSoft, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Text("Join the club to post messages.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space14))
                    }
                } else if (club?.settings?.onlyLeadsCanPost == true && !canPost) {
                    Surface(color = NbTheme.colors.surfaceSoft, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Text("Only club leads can post in this space.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space14))
                    }
                } else {
                    ClubComposer(
                        value = state.composerText,
                        replyTo = state.replyTo,
                        attachment = state.attachment,
                        preparingAttachment = state.isPreparingAttachment,
                        sendingAttachment = state.isSendingAttachment,
                        isRecordingVoice = state.isRecordingVoice,
                        voiceRecordingDurationSeconds = state.voiceRecordingDurationSeconds,
                        voiceRecordingLevels = state.voiceRecordingLevels,
                        isSendingVoice = state.isSendingVoice,
                        onValueChange = viewModel::setComposerText,
                        onClearReply = { viewModel.setReplyTo(null) },
                        onAddAttachment = { if (canPost) showAttachmentPicker = true },
                        onClearAttachment = viewModel::clearAttachment,
                        onSend = { viewModel.sendText() },
                        onSendAttachment = { viewModel.sendAttachment() },
                        onStartVoiceRecording = startVoiceRecording,
                        onStopVoiceRecording = viewModel::stopVoiceRecording,
                        onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                        enabled = canSend,
                        composerEnabled = canPost && !state.isSending && !state.isSendingAttachment && !state.isSendingVoice && !state.isRecordingVoice,
                        attachmentEnabled = state.canSendAttachment(viewerId),
                        sending = state.isSending,
                    )
                }
            }
        }
    }
    state.actionMessage?.let { message ->
        ClubMessageActionSheet(
            message = message,
            viewerId = viewerId,
            onReply = { viewModel.setReplyTo(message) },
            onReaction = viewModel::toggleReaction,
            onDeleteForMe = viewModel::deleteForMe,
            onDeleteForEveryone = viewModel::deleteForEveryone,
            onDismiss = viewModel::closeMessageActions,
        )
    }
    if (showAttachmentPicker) {
        ClubAttachmentPickerSheet(
            onPickMedia = { showAttachmentPicker = false; visualPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPickFile = { showAttachmentPicker = false; documentPicker.launch(arrayOf("*/*")) },
            onDismiss = { showAttachmentPicker = false },
        )
    }
}

@Composable
private fun ClubChatHeader(club: Club?, typingCount: Int, onSettings: (String) -> Unit) {
    val current = club ?: return
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space12), verticalAlignment = Alignment.CenterVertically) {
        ClubHeaderAvatar(current)
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Text(current.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (typingCount == 1) "Someone is typing..." else if (typingCount > 1) "Several members are typing..." else "${current.memberCount} members  ·  ${if (current.type == "private") "Private" else "Public"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (typingCount > 0) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted,
            )
        }
        IconButton(onClick = { onSettings(current.id) }, modifier = Modifier.semantics { contentDescription = "Open club settings" }) {
            Icon(NbIcons.More, contentDescription = null, tint = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun ClubHeaderAvatar(club: Club) {
    val image = club.avatar?.takeIf(String::isNotBlank)
    Box(modifier = Modifier.size(NbDimens.avatarLg).clip(RoundedCornerShape(NbDimens.radiusMd)).background(NbTheme.colors.brandTeal.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        if (image != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(image).crossfade(220).build(), contentDescription = club.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ClubConversationIntro(club: Club?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = NbDimens.space16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        ClubHeaderAvatar(club ?: return)
        Text(club.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        Text("A focused space for your campus community.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun ClubMessageBubble(
    message: Message,
    isViewer: Boolean,
    linkPreview: LinkPreview?,
    showSender: Boolean,
    onOpenProfile: () -> Unit,
    onLongPress: () -> Unit,
    onRead: (() -> Unit)?,
    playback: ChatVoicePlaybackState,
    onToggleVoice: (Message) -> Unit,
    onSeekVoice: (String, Float) -> Unit,
    onCycleVoiceSpeed: (String) -> Unit,
) {
    LaunchedEffect(message.id, message.readBy) { onRead?.invoke() }
    val context = LocalContext.current
    val shape = if (isViewer) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    val bubbleColor = if (isViewer) NbTheme.colors.brandTeal else NbTheme.colors.surfaceCard
    val textColor = if (isViewer) Color.White else NbTheme.colors.ink
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isViewer) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (isViewer) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            if (showSender && !isViewer) {
                Row(modifier = Modifier.clickable(onClick = onOpenProfile), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    message.senderAvatar?.takeIf(String::isNotBlank)?.let { AsyncImage(it, "${message.senderName ?: "Member"} profile photo", contentScale = ContentScale.Crop, modifier = Modifier.size(18.dp).clip(RoundedCornerShape(NbDimens.radiusFull))) }
                    Text(message.senderName.orEmpty().ifBlank { "Member" }, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.inkMuted)
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 300.dp).clip(shape).background(bubbleColor).pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) }.padding(horizontal = NbDimens.space14, vertical = NbDimens.space12),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space4),
            ) {
                if (message.isDeletedForEveryone) Text("This message was deleted", style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.72f))
                message.forwardedFrom?.takeIf { !message.isDeletedForEveryone }?.let { source ->
                    Text("Forwarded from ${source.senderName?.takeIf(String::isNotBlank) ?: "NextBench member"}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = textColor.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!message.replyToText.isNullOrBlank()) {
                    Text("Replying to ${message.replyToSenderName ?: "message"}", style = MaterialTheme.typography.labelSmall, color = if (isViewer) Color.White.copy(alpha = 0.75f) else NbTheme.colors.brandTeal)
                    Text(message.replyToText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!message.isDeletedForEveryone) when (MessageType.from(message.type)) {
                    MessageType.Image -> message.image?.let { url -> AsyncImage(url, "Photo attachment", contentScale = ContentScale.Crop, modifier = Modifier.size(240.dp, 180.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).clickable { openClubAttachment(context, url) }) }
                    MessageType.Video -> message.video?.let { video ->
                        Row(modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusSm)).background(textColor.copy(alpha = 0.1f)).clickable { openClubAttachment(context, video.url) }.padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                            Icon(NbIcons.Play, contentDescription = null, tint = textColor)
                            Text("Video", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = textColor)
                        }
                    }
                    MessageType.File -> message.file?.let { file ->
                        Row(modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusSm)).background(textColor.copy(alpha = 0.1f)).clickable { openClubAttachment(context, file.url) }.padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                            Icon(NbIcons.FileText, contentDescription = null, tint = textColor)
                            Text(file.name.ifBlank { "Document" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    MessageType.Voice -> VoiceMessageBubble(message, isViewer, playback, onToggleVoice, onSeekVoice, onCycleVoiceSpeed)
                    MessageType.Text -> if (!message.text.isNullOrBlank()) Text(message.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = textColor)
                }
                if (!message.isDeletedForEveryone && linkPreview != null) {
                    ChatLinkPreview(preview = linkPreview, isViewer = isViewer, onOpen = { url -> openClubAttachment(context, url) })
                }
                if (message.reactions.isNotEmpty()) Text(message.reactions.entries.joinToString("  ") { (emoji, users) -> "$emoji ${users.size}" }, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.78f))
                Text(message.createdAt?.toDate()?.time?.let(::formatRelativeTime) ?: "sending", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f))
            }
        }
    }
}

@Composable
private fun ClubComposer(
    value: String,
    replyTo: Message?,
    attachment: PreparedChatAttachment?,
    preparingAttachment: Boolean,
    sendingAttachment: Boolean,
    isRecordingVoice: Boolean,
    voiceRecordingDurationSeconds: Long,
    voiceRecordingLevels: List<Float>,
    isSendingVoice: Boolean,
    onValueChange: (String) -> Unit,
    onClearReply: () -> Unit,
    onAddAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
    onSendAttachment: () -> Unit,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Boolean,
    onCancelVoiceRecording: () -> Boolean,
    enabled: Boolean,
    composerEnabled: Boolean,
    attachmentEnabled: Boolean,
    sending: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = NbDimens.space12, vertical = NbDimens.space8)) {
        replyTo?.let { reply ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = NbDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${reply.senderName ?: "message"}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, modifier = Modifier.weight(1f))
                IconButton(onClick = onClearReply, modifier = Modifier.size(28.dp)) { Icon(NbIcons.Close, contentDescription = "Cancel reply", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(16.dp)) }
            }
        }
        attachment?.let { selected ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = NbDimens.space8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                if (selected.mimeType.startsWith("image/")) AsyncImage(selected.previewUri, "Selected attachment", contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(NbDimens.radiusSm)))
                else Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft), contentAlignment = Alignment.Center) { Icon(if (selected.mimeType.startsWith("video/")) NbIcons.Play else NbIcons.FileText, contentDescription = null, tint = NbTheme.colors.brandTeal) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(selected.displayName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selected.mimeType.startsWith("image/")) "Photo" else if (selected.mimeType.startsWith("video/")) "Video" else "Document", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                }
                IconButton(onClick = onClearAttachment, enabled = !sendingAttachment) { Icon(NbIcons.Close, contentDescription = "Remove attachment", tint = NbTheme.colors.inkMuted) }
            }
        }
        when {
            isRecordingVoice -> VoiceRecordingControls(voiceRecordingDurationSeconds, voiceRecordingLevels, onCancelVoiceRecording, onStopVoiceRecording)
            isSendingVoice -> Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft).padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                Text("Sending voice message", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            }
            else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                IconButton(onClick = onAddAttachment, enabled = composerEnabled && !preparingAttachment && !sendingAttachment, modifier = Modifier.size(44.dp).semantics { contentDescription = "Add club attachment" }) {
                    if (preparingAttachment) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp) else Icon(NbIcons.Plus, contentDescription = null, tint = NbTheme.colors.brandTeal)
                }
                NbTextField(value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f), placeholder = "Write to the club...", enabled = composerEnabled, singleLine = false, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), keyboardActions = KeyboardActions.Default)
                val canSubmit = if (attachment != null) attachmentEnabled else enabled
                val showMic = attachment == null && value.isBlank() && !preparingAttachment && !sendingAttachment
                IconButton(onClick = if (showMic) onStartVoiceRecording else if (attachment != null) onSendAttachment else onSend, enabled = if (showMic) composerEnabled else canSubmit, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (showMic || canSubmit) NbTheme.colors.brandTeal else NbTheme.colors.surfaceSoft).semantics { contentDescription = if (showMic) "Record club voice message" else if (attachment != null) "Send club attachment" else "Send club message" }) {
                    if (sending || sendingAttachment) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(if (showMic) NbIcons.Mic else NbIcons.Send, contentDescription = null, tint = if (showMic || canSubmit) Color.White else NbTheme.colors.inkFaint)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClubAttachmentPickerSheet(onPickMedia: () -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Text("Share with the club", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            ClubActionRow(NbIcons.Camera, "Photo or video", onPickMedia, NbTheme.colors.brandTeal)
            ClubActionRow(NbIcons.FileText, "Document", onPickFile, NbTheme.colors.brandPink)
            NbButton("Cancel", onDismiss, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClubMessageActionSheet(
    message: Message,
    viewerId: String?,
    onReply: () -> Unit,
    onReaction: (String) -> Boolean,
    onDeleteForMe: () -> Boolean,
    onDeleteForEveryone: () -> Boolean,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember(message.id) { mutableStateOf(false) }
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            if (confirmDelete) {
                Text("Delete message?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                NbButton("Delete for me", { onDeleteForMe() }, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Secondary)
                if (message.senderId == viewerId) NbButton("Delete for everyone", { onDeleteForEveryone() }, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Primary)
                NbButton("Cancel", { confirmDelete = false }, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
            } else {
                Text("React", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.inkMuted)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("👍", "❤️", "😂", "😮", "🙏").forEach { emoji -> Text(emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { onReaction(emoji) }.padding(NbDimens.space8)) }
                }
                ClubActionRow(NbIcons.Reply, "Reply", onReply)
                ClubActionRow(NbIcons.Trash, "Delete", { confirmDelete = true }, NbTheme.colors.brandPink)
            }
        }
    }
}

@Composable
private fun ClubActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = NbTheme.colors.ink) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).clickable(onClick = onClick).padding(vertical = NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = tint)
    }
}

private fun openClubAttachment(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun ClubChatLoading() {
    Column(modifier = Modifier.padding(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        Row(verticalAlignment = Alignment.CenterVertically) { NbSkeletonLine(Modifier.size(46.dp), height = 46.dp); Spacer(Modifier.width(NbDimens.space12)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) { NbSkeletonLine(widthFraction = 0.5f); NbSkeletonLine(widthFraction = 0.3f, height = 11.dp) } }
        NbSkeletonLine(widthFraction = 0.7f, height = 52.dp)
        NbSkeletonLine(widthFraction = 0.82f, height = 52.dp, modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ClubNoticeBanner(notice: ClubChatNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (notice.kind) {
        ClubChatNoticeKind.Success -> NbTheme.colors.brandMint
        ClubChatNoticeKind.Error -> NbTheme.colors.brandPink
        ClubChatNoticeKind.Info -> NbTheme.colors.ink
    }
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(top = NbDimens.space12, start = NbDimens.space16, end = NbDimens.space16)) {
        LaunchedEffect(notice.id) { kotlinx.coroutines.delay(3200); onDismiss() }
        Surface(color = color, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss)) { Text(notice.message, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = NbDimens.space14, vertical = NbDimens.space12)) }
    }
}
