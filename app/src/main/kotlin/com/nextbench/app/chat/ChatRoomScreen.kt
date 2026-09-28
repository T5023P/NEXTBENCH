package com.nextbench.app.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.NbVerifiedBadge
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageStatus
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.ForwardTargetType
import com.nextbench.data.firebase.LinkPreview
import com.nextbench.data.firebase.firstMessageUrl
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatRoomScreen(
    user: UserData?,
    onOpenProduct: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatRoomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewerId = user?.uid
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var initialScrollComplete by remember { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var typingClock by remember { mutableStateOf(System.currentTimeMillis()) }
    val scrollScope = rememberCoroutineScope()
    val showJumpToLatest by remember {
        derivedStateOf {
            shouldShowJumpToLatest(
                totalItems = listState.layoutInfo.totalItemsCount,
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            )
        }
    }

    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onScreenDisposed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenDisposed()
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val lastMessageIndex = state.messages.size
        if (!initialScrollComplete) {
            listState.scrollToItem(lastMessageIndex)
            initialScrollComplete = true
        } else {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: lastMessageIndex
            if (lastVisibleIndex >= lastMessageIndex - 2) listState.animateScrollToItem(lastMessageIndex)
        }
    }

    val other = state.room?.otherUser
    val otherName = other?.name?.ifBlank { null } ?: "NextBench member"
    val room = state.room?.room
    val otherTyping = room?.typingUsers
        ?.filterKeys { it != viewerId }
        ?.values
        ?.any { timestamp -> isUserTyping(timestamp.toDate().time, typingClock) } == true
    LaunchedEffect(room?.typingUsers) {
        if (room?.typingUsers.isNullOrEmpty()) return@LaunchedEffect
        while (true) {
            typingClock = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::prepareAttachment)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::prepareAttachment)
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoiceRecording() else viewModel.onMicrophonePermissionDenied()
    }
    val startVoiceRecording: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVoiceRecording()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(
        enabled = state.forwardSourceIds.isNotEmpty() ||
            state.actionMessage != null ||
            showAttachmentPicker ||
            state.selectionMode,
    ) {
        when {
            state.forwardSourceIds.isNotEmpty() -> viewModel.closeForwarding()
            state.actionMessage != null -> viewModel.closeMessageActions()
            showAttachmentPicker -> showAttachmentPicker = false
            state.selectionMode -> viewModel.clearMessageSelection()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        if (state.isLoading && state.room == null) {
            ChatLoadingHeader()
        } else if (state.error != null && state.room == null) {
            NbEmptyState(
                icon = NbIcons.Messages,
                title = "Conversation unavailable",
                message = state.error.orEmpty(),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ChatHeader(
                name = otherName,
                avatar = other?.profilePicture,
                verified = other?.verified == true,
                school = other?.school,
                online = visibleOnline(other, typingClock),
                lastSeenMillis = visibleLastSeenMillis(other),
                typing = otherTyping,
                productTitle = room?.productTitle,
                onOpenProduct = room?.productId?.let { { onOpenProduct(it) } },
                onOpenProfile = other?.uid?.let { { onOpenProfile(it) } },
            )
            HorizontalDivider(color = NbTheme.colors.border)

            Box(modifier = Modifier.weight(1f)) {
                if (state.messages.isEmpty()) {
                    EmptyConversation(name = otherName)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space20),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
                    ) {
                        item { ConversationIntro(name = otherName, avatar = other?.profilePicture, verified = other?.verified == true) }
                        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                            val previous = state.messages.getOrNull(index - 1)
                            if (messageStartsNewDay(previous, message)) {
                                DaySeparator(messageDayLabel(message.createdAt?.toDate()?.time))
                            }
                            MessageBubble(
                                message = message,
                                isViewer = message.senderId == viewerId,
                                highlighted = highlightedMessageId == message.id,
                                linkPreview = message.text?.let(::firstMessageUrl)?.let(state.linkPreviews::get),
                                showSender = previous == null || previous.senderId != message.senderId || messageStartsNewDay(previous, message),
                                onLongPress = viewModel::openMessageActions,
                                selectionMode = state.selectionMode,
                                selected = message.id in state.selectedMessageIds,
                                onToggleSelection = { viewModel.selectMessage(message) },
                                onReply = { viewModel.setReplyTo(message) },
                                onOpenReply = { replyId ->
                                    val targetIndex = replyTargetListIndex(state.messages, replyId)
                                    if (targetIndex == null) {
                                        viewModel.notifyReplyTargetUnavailable()
                                    } else {
                                        scrollScope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                            highlightedMessageId = state.messages[targetIndex - 1].id
                                            delay(1_200L)
                                            highlightedMessageId = null
                                        }
                                    }
                                },
                                onRetry = { viewModel.retryText(message) },
                                onRemoveFailed = { viewModel.removeFailedText(message) },
                                onOpenAttachment = { url -> openAttachment(context, url) },
                                playback = state.voicePlayback,
                                onToggleVoice = viewModel::toggleVoicePlayback,
                                onSeekVoice = viewModel::seekVoicePlayback,
                                onCycleVoiceSpeed = viewModel::cycleVoicePlaybackSpeed,
                                onRead = if (message.senderId != viewerId && viewerId != null) {
                                    { viewModel.markMessageRead(message.id) }
                                } else null,
                            )
                        }
                    }
                    if (showJumpToLatest) {
                        Surface(
                            color = NbTheme.colors.surfaceCard,
                            shape = RoundedCornerShape(NbDimens.radiusFull),
                            shadowElevation = 3.dp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(NbDimens.space16),
                        ) {
                            IconButton(
                                onClick = {
                                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                    if (lastIndex >= 0) scrollScope.launch { listState.animateScrollToItem(lastIndex) }
                                },
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "Jump to latest message" },
                            ) {
                                Icon(NbIcons.ArrowDown, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                state.notice?.let { notice ->
                    NoticeBanner(notice = notice, onDismiss = { viewModel.dismissNotice(notice.id) }, modifier = Modifier.align(Alignment.TopCenter))
                }
            }

            if (state.selectionMode) {
                MessageSelectionToolbar(
                    selected = state.selectedMessages,
                    viewerId = viewerId,
                    busy = state.isBulkActionRunning,
                    onClose = viewModel::clearMessageSelection,
                    onForward = viewModel::openForwardingFromSelection,
                    onDeleteForMe = viewModel::deleteSelectionForMe,
                    onDeleteForEveryone = viewModel::deleteSelectionForEveryone,
                )
            } else if (state.blocked) {
                BlockedComposer(otherName)
            } else if (state.pendingRequest) {
                PendingRequestBar(
                    name = otherName,
                    isRecipient = state.canRespondToRequest(viewerId),
                    busy = state.isActingOnRequest,
                    onAccept = viewModel::acceptRequest,
                    onDecline = viewModel::declineRequest,
                )
            } else {
                Composer(
                    value = state.composerText,
                    onValueChange = viewModel::setComposerText,
                    onSend = viewModel::sendText,
                    enabled = state.canSend(viewerId),
                    sending = state.isSending,
                    attachment = state.attachment,
                    preparingAttachment = state.isPreparingAttachment,
                    sendingAttachment = state.isSendingAttachment,
                    replyTo = state.replyTo,
                    onAddAttachment = { showAttachmentPicker = true },
                    onClearAttachment = viewModel::clearAttachment,
                    onClearReply = { viewModel.setReplyTo(null) },
                    onSendAttachment = viewModel::sendAttachment,
                    isRecordingVoice = state.isRecordingVoice,
                    voiceRecordingDurationSeconds = state.voiceRecordingDurationSeconds,
                    voiceRecordingLevels = state.voiceRecordingLevels,
                    isSendingVoice = state.isSendingVoice,
                    voiceUploadProgress = state.voiceUploadProgress,
                    onStartVoiceRecording = startVoiceRecording,
                    onStopVoiceRecording = viewModel::stopVoiceRecording,
                    onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                )
            }
        }
    }
    state.actionMessage?.let { message ->
        MessageActionSheet(
            message = message,
            viewerId = viewerId,
            onReply = { viewModel.setReplyTo(message) },
            onForward = viewModel::openForwardingFromAction,
            onSelect = viewModel::selectActionMessage,
            onReaction = viewModel::toggleReaction,
            onDeleteForMe = viewModel::deleteForMe,
            onDeleteForEveryone = viewModel::deleteForEveryone,
            onDismiss = viewModel::closeMessageActions,
        )
    }
    if (showAttachmentPicker) {
        AttachmentPickerSheet(
            onPickMedia = {
                showAttachmentPicker = false
                visualPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFile = {
                showAttachmentPicker = false
                documentPicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showAttachmentPicker = false },
        )
    }
    if (state.forwardSourceIds.isNotEmpty()) {
        ForwardMessageSheet(
            sourceCount = state.forwardSourceIds.size,
            targets = state.visibleForwardTargets,
            selectedKeys = state.selectedForwardTargetKeys,
            query = state.forwardQuery,
            loading = state.isLoadingForwardTargets,
            sending = state.isForwarding,
            onQueryChange = viewModel::setForwardQuery,
            onToggle = viewModel::toggleForwardTarget,
            onForward = viewModel::forwardSelectedMessages,
            onDismiss = viewModel::closeForwarding,
        )
    }
}

@Composable
private fun ChatLoadingHeader() {
    Column(modifier = Modifier.padding(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NbSkeletonLine(Modifier.size(46.dp), height = 46.dp)
            Spacer(Modifier.width(NbDimens.space12))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbSkeletonLine(widthFraction = 0.42f)
                NbSkeletonLine(widthFraction = 0.28f, height = 11.dp)
            }
        }
        Spacer(Modifier.size(24.dp))
        NbSkeletonLine(widthFraction = 0.62f, height = 48.dp)
        NbSkeletonLine(widthFraction = 0.78f, height = 48.dp, modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ChatHeader(
    name: String,
    avatar: String?,
    verified: Boolean,
    school: String?,
    online: Boolean,
    lastSeenMillis: Long?,
    typing: Boolean,
    productTitle: String?,
    onOpenProduct: (() -> Unit)?,
    onOpenProfile: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space12), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(NbDimens.radiusMd)).clickable(enabled = onOpenProfile != null) { onOpenProfile?.invoke() }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NbAvatar(imageUrl = avatar, name = name, size = NbDimens.avatarLg)
            Spacer(Modifier.width(NbDimens.space12))
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (verified) NbVerifiedBadge(size = 15.dp)
                }
                if (typing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        TypingDots()
                        Text("typing", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.brandTeal)
                    }
                } else {
                    Text(
                        text = chatPresenceLabel(online, lastSeenMillis, school, verified),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (online) NbTheme.colors.brandMint else NbTheme.colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onOpenProduct != null && !productTitle.isNullOrBlank()) {
            IconButton(onClick = onOpenProduct, modifier = Modifier.semantics { contentDescription = "Open listing" }) {
                Icon(NbIcons.Marketplace, contentDescription = null, tint = NbTheme.colors.brandTeal)
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.32f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, delayMillis = index * 110),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typingDot$index",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(NbDimens.radiusFull))
                    .background(NbTheme.colors.brandTeal),
            )
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = NbTheme.colors.border)
        Text(label, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
        HorizontalDivider(modifier = Modifier.weight(1f), color = NbTheme.colors.border)
    }
}

internal fun messageStartsNewDay(previous: Message?, current: Message, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
    val currentDay = current.createdAt?.toDate()?.time?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
        ?: return previous == null
    val previousDay = previous?.createdAt?.toDate()?.time?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
    return previousDay != currentDay
}

internal fun messageDayLabel(
    timestampMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    timestampMillis ?: return "Today"
    val date = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

internal fun chatPresenceLabel(
    online: Boolean,
    lastSeenMillis: Long?,
    school: String?,
    verified: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): String = when {
    isUserOnline(online, lastSeenMillis, nowMillis) -> "Online"
    lastSeenMillis != null -> lastSeenLabel(lastSeenMillis, nowMillis)
    else -> listOfNotNull(
        school?.takeIf(String::isNotBlank),
        "Verified member".takeIf { verified },
    ).joinToString("  · ").ifBlank { "Campus conversation" }
}

internal fun isUserTyping(timestampMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean =
    timestampMillis != null && nowMillis - timestampMillis < TypingStaleMillis

/**
 * Presence shown for another member. A member who hides last seen is never rendered as online or
 * "last seen": the preference is a display-time contract (see
 * [com.nextbench.data.firebase.ProfileRepository.updateHideLastSeen]), so it is honoured here
 * instead of by withholding the shared presence write that other clients also read.
 */
internal fun visibleOnline(user: UserData?, nowMillis: Long = System.currentTimeMillis()): Boolean =
    user != null && !user.hideLastSeen &&
        isUserOnline(user.online, user.lastSeen?.toDate()?.time, nowMillis)

/** The other member's last-seen timestamp, or null when that member hides it. */
internal fun visibleLastSeenMillis(user: UserData?): Long? =
    user?.takeIf { !it.hideLastSeen }?.lastSeen?.toDate()?.time

internal fun isUserOnline(
    online: Boolean,
    lastSeenMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean = online && (lastSeenMillis == null || nowMillis - lastSeenMillis < OnlineThresholdMillis)

internal fun lastSeenLabel(
    lastSeenMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val elapsed = (nowMillis - lastSeenMillis).coerceAtLeast(0L)
    if (elapsed < 60_000L) return "Active just now"
    if (elapsed < RecentThresholdMillis) return "Active ${elapsed / 60_000L}m ago"

    val lastSeen = Instant.ofEpochMilli(lastSeenMillis).atZone(zoneId)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(lastSeen.toLocalDate(), today)
    return when {
        days == 0L -> "Last seen ${lastSeen.format(DateTimeFormatter.ofPattern("h:mm a"))}"
        days == 1L -> "Last seen yesterday"
        days in 2..6 -> "Last seen ${days}d ago"
        else -> "Last seen ${lastSeen.format(DateTimeFormatter.ofPattern("MMM d"))}"
    }
}

private const val TypingStaleMillis = 5_000L
private const val OnlineThresholdMillis = 90_000L
private const val RecentThresholdMillis = 5 * 60_000L

@Composable
private fun ConversationIntro(name: String, avatar: String?, verified: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = NbDimens.space16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        NbAvatar(imageUrl = avatar, name = name, size = NbDimens.avatarXl)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            if (verified) NbVerifiedBadge(size = 15.dp)
        }
        Text("Keep it kind, clear, and campus-safe.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isViewer: Boolean,
    highlighted: Boolean,
    linkPreview: LinkPreview?,
    showSender: Boolean,
    onLongPress: (Message) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onReply: () -> Unit,
    onOpenReply: (String) -> Unit,
    onRetry: () -> Unit,
    onRemoveFailed: () -> Unit,
    onOpenAttachment: (String) -> Unit,
    playback: ChatVoicePlaybackState,
    onToggleVoice: (Message) -> Unit,
    onSeekVoice: (String, Float) -> Unit,
    onCycleVoiceSpeed: (String) -> Unit,
    onRead: (() -> Unit)?,
) {
    LaunchedEffect(message.id, message.readBy) { onRead?.invoke() }
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()
    var swipeOffset by remember(message.id) { mutableFloatStateOf(0f) }
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val maxSwipePx = with(density) { 84.dp.toPx() }
    val deliveryStatus = MessageStatus.from(message.status)
    val replyDisabled = message.isDeletedForEveryone || deliveryStatus != MessageStatus.Sent
    val shape = if (isViewer) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    val rowHighlightColor by animateColorAsState(
        targetValue = when {
            highlighted -> NbTheme.colors.brandTeal.copy(alpha = 0.14f)
            selected -> NbTheme.colors.brandTeal.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "replyTargetHighlight",
    )
    val bubbleColor = if (isViewer) NbTheme.colors.brandTeal else NbTheme.colors.surfaceCard
    val textColor = if (isViewer) Color.White else NbTheme.colors.ink
    Box(modifier = Modifier.fillMaxWidth()) {
        if (!selectionMode && !replyDisabled) {
            Icon(
                NbIcons.Reply,
                contentDescription = null,
                tint = NbTheme.colors.brandTeal.copy(alpha = (swipeOffset / swipeThresholdPx).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = NbDimens.space12).size(20.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .pointerInput(message.id, selectionMode, replyDisabled) {
                    if (selectionMode || replyDisabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (shouldTriggerSwipeReply(swipeOffset, swipeThresholdPx, selectionMode, replyDisabled)) onReply()
                            val start = swipeOffset
                            gestureScope.launch { animate(start, 0f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f)) { value, _ -> swipeOffset = value } }
                        },
                        onDragCancel = {
                            val start = swipeOffset
                            gestureScope.launch { animate(start, 0f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f)) { value, _ -> swipeOffset = value } }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val next = (swipeOffset + dragAmount).coerceIn(0f, maxSwipePx)
                            if (next != swipeOffset) change.consume()
                            swipeOffset = next
                        },
                    )
                }
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .background(rowHighlightColor)
                .padding(
                    horizontal = if (selectionMode) NbDimens.space4 else 0.dp,
                    vertical = if (selectionMode) NbDimens.space2 else 0.dp,
                ),
            horizontalArrangement = if (isViewer) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode && !isViewer) SelectionIndicator(selected)
            Column(horizontalAlignment = if (isViewer) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                if (showSender && !isViewer && !message.senderName.isNullOrBlank()) {
                    Text(message.senderName.orEmpty(), style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                }
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(bubbleColor)
                        .pointerInput(message.id) {
                            detectTapGestures(onLongPress = { if (deliveryStatus == MessageStatus.Sent) onLongPress(message) })
                        }
                        .animateContentSize()
                        .padding(horizontal = NbDimens.space14, vertical = NbDimens.space12),
                    verticalArrangement = Arrangement.spacedBy(NbDimens.space4),
                ) {
                if (message.isDeletedForEveryone) {
                    Text("This message was deleted", style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.75f))
                }
                message.forwardedFrom?.takeIf { !message.isDeletedForEveryone }?.let { source ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Icon(NbIcons.Forward, contentDescription = null, tint = textColor.copy(alpha = 0.72f), modifier = Modifier.size(13.dp))
                        Text(
                            text = "Forwarded from ${source.senderName?.takeIf(String::isNotBlank) ?: "NextBench member"}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!message.replyToText.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isViewer) Color.White.copy(alpha = 0.1f) else NbTheme.colors.surfaceSoft)
                            .clickable(
                                enabled = !message.replyToMessageId.isNullOrBlank(),
                                onClick = { message.replyToMessageId?.let(onOpenReply) },
                            )
                            .semantics { contentDescription = "Open replied-to message" }
                            .padding(horizontal = NbDimens.space8, vertical = NbDimens.space8),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space2),
                    ) {
                        Text("Replying to ${message.replyToSenderName ?: "message"}", style = MaterialTheme.typography.labelSmall, color = if (isViewer) Color.White.copy(alpha = 0.8f) else NbTheme.colors.brandTeal)
                        Text(message.replyToText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.76f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!message.isDeletedForEveryone) when (MessageType.from(message.type)) {
                    MessageType.Image -> message.image?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Photo attachment",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(244.dp, 184.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).clickable { onOpenAttachment(url) },
                        )
                    }
                    MessageType.Video -> message.video?.let { video ->
                        Box(
                            modifier = Modifier.size(244.dp, 148.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(if (isViewer) Color.White.copy(alpha = 0.14f) else NbTheme.colors.surfaceSoft).clickable { onOpenAttachment(video.url) },
                            contentAlignment = Alignment.Center,
                        ) {
                            video.poster?.takeIf(String::isNotBlank)?.let { poster ->
                                AsyncImage(poster, "Video preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Box(modifier = Modifier.size(46.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(Color.Black.copy(alpha = 0.58f)), contentAlignment = Alignment.Center) {
                                Icon(NbIcons.Play, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    MessageType.File -> message.file?.let { file ->
                        Row(
                            modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusSm)).background(if (isViewer) Color.White.copy(alpha = 0.14f) else NbTheme.colors.surfaceSoft).clickable { onOpenAttachment(file.url) }.padding(NbDimens.space12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                        ) {
                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(if (isViewer) Color.White.copy(alpha = 0.14f) else NbTheme.colors.surfaceBase), contentAlignment = Alignment.Center) {
                                Icon(NbIcons.FileText, contentDescription = null, tint = textColor, modifier = Modifier.size(21.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                                Text(file.name.ifBlank { "Document" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(documentMetadata(file.size, file.mime, file.pages), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(NbIcons.ArrowRight, contentDescription = "Open document", tint = textColor.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
                        }
                    }
                    MessageType.Voice -> VoiceMessageBubble(
                        message = message,
                        isViewer = isViewer,
                        playback = playback,
                        onToggle = onToggleVoice,
                        onSeek = onSeekVoice,
                        onCycleSpeed = onCycleVoiceSpeed,
                    )
                    MessageType.Text -> if (!message.text.isNullOrBlank()) Text(message.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = textColor)
                }
                if (!message.isDeletedForEveryone && deliveryStatus == MessageStatus.Sent && linkPreview != null) {
                    ChatLinkPreview(preview = linkPreview, isViewer = isViewer, onOpen = { url -> onOpenAttachment(url) })
                }
                if (message.reactions.isNotEmpty()) {
                    Text(message.reactions.entries.joinToString("  ") { (emoji, users) -> "$emoji ${users.size}" }, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.78f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(message.createdAt?.toDate()?.time?.let(::formatRelativeTime) ?: "sending", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f))
                    when (deliveryStatus) {
                        MessageStatus.Pending -> Text("Sending...", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f))
                        MessageStatus.Failed -> {
                            Text("Failed", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.brandPink)
                            Text("Retry", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = if (isViewer) Color.White else NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = onRetry))
                            Icon(NbIcons.Close, contentDescription = "Remove failed message", tint = textColor.copy(alpha = 0.72f), modifier = Modifier.size(15.dp).clickable(onClick = onRemoveFailed))
                        }
                        MessageStatus.Sent -> if (isViewer && message.readBy.isNotEmpty()) Text("Seen", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f))
                    }
                }
                }
            }
            if (selectionMode && isViewer) SelectionIndicator(selected)
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onToggleSelection)
                    .semantics { contentDescription = if (selected) "Deselect message" else "Select message" },
            )
        }
    }
}

internal fun shouldTriggerSwipeReply(offsetPx: Float, thresholdPx: Float, selectionMode: Boolean, deleted: Boolean): Boolean =
    !selectionMode && !deleted && thresholdPx > 0f && offsetPx >= thresholdPx

internal fun shouldShowJumpToLatest(totalItems: Int, lastVisibleIndex: Int?): Boolean =
    totalItems > 1 && (lastVisibleIndex ?: -1) < totalItems - 2

internal fun replyTargetListIndex(messages: List<Message>, replyToMessageId: String): Int? {
    val messageIndex = messages.indexOfFirst { message ->
        message.id == replyToMessageId || message.clientMessageId == replyToMessageId
    }
    return messageIndex.takeIf { it >= 0 }?.plus(1)
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier.padding(horizontal = NbDimens.space8).size(24.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (selected) NbTheme.colors.brandTeal else NbTheme.colors.surfaceSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(NbIcons.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun VoiceMessageBubble(
    message: Message,
    isViewer: Boolean,
    playback: ChatVoicePlaybackState,
    onToggle: (Message) -> Unit,
    onSeek: (String, Float) -> Unit,
    onCycleSpeed: (String) -> Unit,
) {
    val active = playback.messageId == message.id
    val durationMillis = playback.durationMillis.takeIf { it > 0L } ?: (message.duration ?: 0L) * 1_000L
    val positionMillis = if (active) playback.positionMillis else 0L
    val progress = if (durationMillis > 0L) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f
    val foreground = if (isViewer) Color.White else NbTheme.colors.ink
    val muted = foreground.copy(alpha = 0.68f)
    val bars = listOf(0.42f, 0.7f, 0.54f, 0.86f, 0.48f, 0.76f, 0.62f, 0.94f, 0.55f, 0.8f, 0.46f, 0.67f, 0.9f, 0.58f, 0.74f, 0.5f, 0.83f, 0.63f, 0.92f, 0.52f, 0.77f, 0.45f, 0.69f, 0.88f, 0.57f, 0.73f, 0.49f, 0.84f)
    var waveformWidth by remember(message.id) { mutableStateOf(1) }
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4), modifier = Modifier.widthIn(min = 216.dp, max = 260.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            IconButton(
                onClick = { onToggle(message) },
                enabled = !active || !playback.isLoading,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (isViewer) Color.White.copy(alpha = 0.16f) else NbTheme.colors.brandTeal.copy(alpha = 0.12f)).semantics { contentDescription = if (playback.isPlaying && active) "Pause voice message" else "Play voice message" },
            ) {
                if (active && playback.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), color = foreground, strokeWidth = 2.dp)
                } else {
                    Icon(if (active && playback.isPlaying) NbIcons.Pause else NbIcons.Play, contentDescription = null, tint = foreground, modifier = Modifier.size(17.dp))
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .onSizeChanged { waveformWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(message.id, durationMillis, waveformWidth) {
                        detectTapGestures { point -> onSeek(message.id, point.x / waveformWidth.toFloat()) }
                    }
                    .semantics { contentDescription = "Voice message playback progress" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bars.forEachIndexed { index, height ->
                    Box(modifier = Modifier.weight(1f).height((8f + 18f * height).dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (index.toFloat() / bars.size <= progress) (if (isViewer) Color.White else NbTheme.colors.brandTeal) else muted))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatVoiceTime(if (active && (playback.isPlaying || positionMillis > 0L)) positionMillis else durationMillis), style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            if (active && playback.error != null) Text("Try again", style = MaterialTheme.typography.labelSmall, color = if (isViewer) Color.White else NbTheme.colors.brandTeal, modifier = Modifier.clickable { onToggle(message) })
            else {
                Text(formatPlaybackSpeed(playback.speed.takeIf { active } ?: 1f), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = muted, modifier = Modifier.clickable { onCycleSpeed(message.id) }.semantics { contentDescription = "Playback speed" })
            }
        }
    }
}

internal fun formatVoiceTime(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun formatPlaybackSpeed(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

@Composable
private fun EmptyConversation(name: String) {
    NbEmptyState(icon = NbIcons.Messages, title = "Say hello to $name", message = "A thoughtful first message goes a long way.", modifier = Modifier.fillMaxSize())
}

@Composable
private fun PendingRequestBar(name: String, isRecipient: Boolean, busy: Boolean, onAccept: () -> Boolean, onDecline: () -> Boolean) {
    Surface(color = NbTheme.colors.surfaceCard, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space12), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Text(if (isRecipient) "$name wants to message you" else "Waiting for $name to accept your request", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            Text(if (isRecipient) "Accept to start the conversation, or decline to remove it." else "You can reply once the request is accepted.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            if (isRecipient) Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbButton("Decline", { onDecline() }, modifier = Modifier.weight(1f), enabled = !busy, variant = NbButtonVariant.Ghost)
                NbButton("Accept", { onAccept() }, modifier = Modifier.weight(1f), enabled = !busy, loading = busy, variant = NbButtonVariant.Secondary)
            }
        }
    }
}

@Composable
private fun BlockedComposer(name: String) {
    Surface(color = NbTheme.colors.surfaceSoft, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space14), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
            Text("Messaging is unavailable with $name.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Boolean,
    enabled: Boolean,
    sending: Boolean,
    attachment: PreparedChatAttachment?,
    preparingAttachment: Boolean,
    sendingAttachment: Boolean,
    replyTo: Message?,
    onAddAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onClearReply: () -> Unit,
    onSendAttachment: () -> Boolean,
    isRecordingVoice: Boolean,
    voiceRecordingDurationSeconds: Long,
    voiceRecordingLevels: List<Float>,
    isSendingVoice: Boolean,
    voiceUploadProgress: Int,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Boolean,
    onCancelVoiceRecording: () -> Boolean,
) {
    Surface(color = NbTheme.colors.surfaceCard, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space8), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            replyTo?.let { reply ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(NbIcons.Reply, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(16.dp))
                    Text("Replying to ${reply.senderName ?: "message"}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    IconButton(onClick = onClearReply, modifier = Modifier.size(28.dp)) { Icon(NbIcons.Close, contentDescription = "Cancel reply", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(16.dp)) }
                }
            }
            attachment?.let { selected ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft).padding(NbDimens.space8)) {
                    if (selected.mimeType.startsWith("image/")) {
                        AsyncImage(selected.previewUri, "Selected photo", contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(NbDimens.radiusSm)))
                    } else {
                        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceBase), contentAlignment = Alignment.Center) {
                            Icon(if (selected.mimeType.startsWith("video/")) NbIcons.Play else NbIcons.FileText, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = NbDimens.space8).weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Text(selected.displayName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatBytes(selected.file.length())}  ·  ${attachmentLabel(selected.mimeType)}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, maxLines = 1)
                    }
                    IconButton(onClick = onClearAttachment, modifier = Modifier.size(28.dp)) { Icon(NbIcons.Close, contentDescription = "Remove attachment", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(16.dp)) }
                }
            }
            when {
                isRecordingVoice -> VoiceRecordingControls(
                    durationSeconds = voiceRecordingDurationSeconds,
                    levels = voiceRecordingLevels,
                    onCancel = onCancelVoiceRecording,
                    onSend = onStopVoiceRecording,
                )
                isSendingVoice -> Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft).padding(horizontal = NbDimens.space12, vertical = NbDimens.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Text("Sending voice message", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                        Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.border)) {
                            Box(modifier = Modifier.fillMaxWidth((voiceUploadProgress / 100f).coerceIn(0f, 1f)).height(3.dp).background(NbTheme.colors.brandTeal))
                        }
                    }
                    Text("$voiceUploadProgress%", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                }
                else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    IconButton(onClick = onAddAttachment, enabled = enabled && !preparingAttachment && !sendingAttachment, modifier = Modifier.size(42.dp).semantics { contentDescription = "Add attachment" }) {
                        Icon(NbIcons.Plus, contentDescription = null, tint = NbTheme.colors.brandTeal)
                    }
                    NbTextField(value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f), placeholder = "Write a message...", singleLine = false, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), keyboardActions = KeyboardActions.Default)
                    val canSend = enabled && !preparingAttachment && (attachment != null || value.isNotBlank())
                    val showMic = attachment == null && value.isBlank() && !preparingAttachment && !sendingAttachment
                    IconButton(
                        onClick = {
                            if (showMic) onStartVoiceRecording()
                            else if (attachment != null) onSendAttachment()
                            else onSend()
                            Unit
                        },
                        enabled = if (showMic) enabled else canSend,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (showMic || canSend) NbTheme.colors.brandTeal else NbTheme.colors.surfaceSoft).semantics { contentDescription = if (showMic) "Record voice message" else "Send message" },
                    ) {
                        if (sending || sendingAttachment || preparingAttachment) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(if (showMic) NbIcons.Mic else NbIcons.Send, contentDescription = null, tint = if (showMic || canSend) Color.White else NbTheme.colors.inkFaint)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoiceRecordingControls(
    durationSeconds: Long,
    levels: List<Float>,
    onCancel: () -> Boolean,
    onSend: () -> Boolean,
) {
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "recordingPulseAlpha",
    )
    Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Box(modifier = Modifier.size(9.dp).alpha(pulse).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.brandPink))
        Text(formatVoiceTime(durationSeconds * 1_000L), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        Row(modifier = Modifier.weight(1f).height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val samples = if (levels.isEmpty()) List(20) { 0.08f } else levels.takeLast(28)
            samples.forEach { level ->
                Box(modifier = Modifier.weight(1f).height((6f + 20f * level.coerceIn(0f, 1f)).dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.brandTeal.copy(alpha = 0.38f + level * 0.62f)))
            }
        }
        IconButton(onClick = { onCancel() }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Cancel voice recording" }) {
            Icon(NbIcons.Trash, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { onSend() }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.brandTeal).semantics { contentDescription = "Stop and send voice message" }) {
            Icon(NbIcons.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSelectionToolbar(
    selected: List<Message>,
    viewerId: String?,
    busy: Boolean,
    onClose: () -> Unit,
    onForward: () -> Boolean,
    onDeleteForMe: () -> Boolean,
    onDeleteForEveryone: () -> Boolean,
) {
    var deleteMode by remember { mutableStateOf<MessageDeleteMode?>(null) }
    val allOwn = selected.isNotEmpty() && selected.all { it.senderId == viewerId }
    Surface(color = NbTheme.colors.surfaceCard, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = NbDimens.space8), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !busy) { Icon(NbIcons.Close, contentDescription = "Exit message selection", tint = NbTheme.colors.ink) }
            Text("${selected.size} selected", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { onForward() }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.semantics { contentDescription = "Forward selected messages" }) {
                Icon(NbIcons.Forward, contentDescription = null, tint = NbTheme.colors.brandTeal)
            }
            IconButton(onClick = { deleteMode = MessageDeleteMode.ForMe }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.semantics { contentDescription = "Delete selected messages" }) {
                Icon(NbIcons.Trash, contentDescription = null, tint = NbTheme.colors.brandPink)
            }
        }
    }
    if (deleteMode != null) {
        NbBottomSheet(onDismiss = { if (!busy) deleteMode = null }) {
            Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                Text("Delete ${selected.size} message${if (selected.size == 1) "" else "s"}?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Choose whether to remove them only from your view or from the entire conversation.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton("Delete for me", { onDeleteForMe(); deleteMode = null }, enabled = !busy, loading = busy, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Secondary)
                if (allOwn) NbButton("Delete for everyone", { onDeleteForEveryone(); deleteMode = null }, enabled = !busy, loading = busy, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Primary)
                NbButton("Cancel", { deleteMode = null }, enabled = !busy, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardMessageSheet(
    sourceCount: Int,
    targets: List<ForwardTarget>,
    selectedKeys: Set<String>,
    query: String,
    loading: Boolean,
    sending: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (ForwardTarget) -> Unit,
    onForward: () -> Boolean,
    onDismiss: () -> Unit,
) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                    Text("Forward to", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                    Text("$sourceCount message${if (sourceCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                }
                IconButton(onClick = onDismiss, enabled = !sending) { Icon(NbIcons.Close, contentDescription = "Close forwarding", tint = NbTheme.colors.inkMuted) }
            }
            NbTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search conversations",
                singleLine = true,
                leadingIcon = { Icon(NbIcons.Search, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(18.dp)) },
            )
            Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(24.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                    targets.isEmpty() -> NbEmptyState(icon = NbIcons.Messages, title = "No conversations", message = if (query.isBlank()) "Start a conversation or join a club first." else "No conversations match your search.", modifier = Modifier.fillMaxSize())
                    else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        items(targets, key = { "${it.type}:${it.id}" }) { target ->
                            val key = target.forwardKey()
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).background(if (key in selectedKeys) NbTheme.colors.brandTeal.copy(alpha = 0.08f) else Color.Transparent).clickable(enabled = !sending) { onToggle(target) }.padding(horizontal = NbDimens.space8, vertical = NbDimens.space12),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
                            ) {
                                SelectionIndicator(key in selectedKeys)
                                if (target.type == ForwardTargetType.Direct) NbAvatar(imageUrl = target.avatar, name = target.name, size = 42.dp)
                                else Box(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(NbDimens.radiusMd)).background(NbTheme.colors.brandTeal.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp)) }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(target.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (target.type == ForwardTargetType.Direct) "Direct message" else "Club", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
            NbButton(
                text = if (selectedKeys.isEmpty()) "Choose conversations" else "Forward to ${selectedKeys.size}",
                onClick = { onForward(); Unit },
                enabled = selectedKeys.isNotEmpty() && !loading && !sending,
                loading = sending,
                modifier = Modifier.fillMaxWidth(),
                variant = NbButtonVariant.Primary,
            )
        }
    }
}

private enum class MessageDeleteMode { ForMe, ForEveryone }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: Message,
    viewerId: String?,
    onReply: () -> Unit,
    onForward: () -> Boolean,
    onSelect: () -> Boolean,
    onReaction: (String) -> Boolean,
    onDeleteForMe: () -> Boolean,
    onDeleteForEveryone: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var deleteMode by remember(message.id) { mutableStateOf<MessageDeleteMode?>(null) }
    val attachmentUrl = message.image ?: message.video?.url ?: message.file?.url
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            if (deleteMode != null) {
                val everyone = deleteMode == MessageDeleteMode.ForEveryone
                Text(if (everyone) "Delete for everyone?" else "Delete for you?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text(if (everyone) "This replaces the message for everyone in the conversation." else "This only removes the message from your view.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), modifier = Modifier.padding(top = NbDimens.space8)) {
                    NbButton("Cancel", { deleteMode = null }, modifier = Modifier.weight(1f), variant = NbButtonVariant.Secondary)
                    NbButton(
                        "Delete",
                        {
                            if (everyone) onDeleteForEveryone() else onDeleteForMe()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        variant = NbButtonVariant.Primary,
                    )
                }
            } else {
                Text("Message actions", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                if (!message.text.isNullOrBlank()) {
                    Text(message.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else {
                    message.file?.let { file ->
                        Text(file.name.ifBlank { "Document" }, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = NbDimens.space4), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    items(listOf("❤️", "😂", "👍", "👏", "🔥")) { emoji ->
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(NbTheme.colors.surfaceSoft).clickable { onReaction(emoji); onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                MessageActionRow(NbIcons.Reply, "Reply", onReply)
                if (!message.isDeletedForEveryone) {
                    MessageActionRow(NbIcons.Forward, "Forward", { onForward(); Unit })
                    MessageActionRow(NbIcons.Check, "Select messages", { onSelect(); Unit })
                }
                if (!message.text.isNullOrBlank()) {
                    MessageActionRow(NbIcons.Copy, "Copy text", onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("NextBench message", message.text))
                        onDismiss()
                    })
                }
                if (!attachmentUrl.isNullOrBlank()) {
                    MessageActionRow(NbIcons.ArrowRight, "Open attachment", onClick = {
                        openAttachment(context, attachmentUrl)
                        onDismiss()
                    })
                }
                Text(if (message.readBy.isEmpty()) "Delivered" else "Seen by ${message.readBy.size}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, modifier = Modifier.padding(vertical = NbDimens.space4))
                MessageActionRow(NbIcons.Trash, "Delete for me", { deleteMode = MessageDeleteMode.ForMe }, NbTheme.colors.brandPink)
                if (message.senderId == viewerId) {
                    MessageActionRow(NbIcons.Close, "Delete for everyone", { deleteMode = MessageDeleteMode.ForEveryone }, NbTheme.colors.brandPink)
                }
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = NbTheme.colors.ink,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).clickable(onClick = onClick).padding(vertical = NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentPickerSheet(onPickMedia: () -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    NbBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Text("Add attachment", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            AttachmentOption(NbIcons.Camera, "Photo or video", "Share from your device", onPickMedia, NbTheme.colors.brandTeal)
            AttachmentOption(NbIcons.FileText, "Document", "PDF, notes, slides, and other files", onPickFile, NbTheme.colors.brandPink)
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusSm)).clickable(onClick = onClick).padding(vertical = NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        }
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(18.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}

private fun attachmentLabel(mime: String): String = when {
    mime.startsWith("image/") -> "Photo"
    mime.startsWith("video/") -> "Video"
    mime == "application/pdf" -> "PDF"
    else -> "Document"
}

private fun documentMetadata(size: Long, mime: String, pages: Int?): String = buildList {
    add(formatBytes(size))
    pages?.takeIf { it > 0 }?.let { add("$it ${if (it == 1) "page" else "pages"}") }
    if (mime == "application/pdf") add("PDF")
}.joinToString("  ·  ")

private fun openAttachment(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun NoticeBanner(notice: ChatNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (notice.kind) {
        ChatNoticeKind.Success -> NbTheme.colors.brandMint
        ChatNoticeKind.Error -> NbTheme.colors.brandPink
        ChatNoticeKind.Info -> NbTheme.colors.ink
    }
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(top = NbDimens.space12, start = NbDimens.space16, end = NbDimens.space16)) {
        LaunchedEffect(notice.id) {
            kotlinx.coroutines.delay(3200)
            onDismiss()
        }
        Surface(color = color, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss)) {
            Text(notice.message, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = NbDimens.space14, vertical = NbDimens.space12))
        }
    }
}
