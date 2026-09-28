package com.nextbench.app.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.model.Story
import com.nextbench.data.model.StoryMediaType
import com.nextbench.data.model.StoryPrivacy
import com.nextbench.data.model.StoryTrayEntry
import com.nextbench.data.model.UserData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ImageStoryDurationMs = 5_000L
private const val StoryReplyLimit = 500

internal data class StoryCursor(val authorIndex: Int, val storyIndex: Int)

internal fun advanceStoryCursor(
    cursor: StoryCursor,
    tray: List<StoryTrayEntry>,
): StoryCursor? {
    val entry = tray.getOrNull(cursor.authorIndex) ?: return null
    if (cursor.storyIndex < entry.stories.lastIndex) {
        return cursor.copy(storyIndex = cursor.storyIndex + 1)
    }
    val nextAuthor = ((cursor.authorIndex + 1)..tray.lastIndex).firstOrNull { tray[it].stories.isNotEmpty() }
    return nextAuthor?.let { StoryCursor(authorIndex = it, storyIndex = 0) }
}

internal fun rewindStoryCursor(
    cursor: StoryCursor,
    tray: List<StoryTrayEntry>,
): StoryCursor {
    if (cursor.storyIndex > 0) return cursor.copy(storyIndex = cursor.storyIndex - 1)
    val previousAuthor = (cursor.authorIndex - 1 downTo 0).firstOrNull { tray[it].stories.isNotEmpty() }
    return previousAuthor?.let {
        StoryCursor(authorIndex = it, storyIndex = tray[it].stories.lastIndex)
    } ?: StoryCursor(authorIndex = cursor.authorIndex.coerceAtLeast(0), storyIndex = 0)
}

@Composable
internal fun StoriesTray(
    user: UserData,
    state: StoryUiState,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
) {
    val ownEntry = state.tray.firstOrNull { it.authorId == user.uid }
    val otherEntries = state.tray.filterNot { it.authorId == user.uid }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.surfaceBase),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = NbDimens.space16,
                vertical = NbDimens.space12,
            ),
        ) {
            item(key = "your_story") {
                StoryTrayAvatar(
                    name = user.name,
                    photoUrl = user.profilePicture,
                    label = "Your story",
                    hasStory = ownEntry != null,
                    seen = ownEntry?.allSeen == true,
                    showAdd = true,
                    onClick = {
                        val index = state.tray.indexOfFirst { it.authorId == user.uid }
                        if (index >= 0) onOpen(index) else onAdd()
                    },
                    onAdd = onAdd,
                )
            }
            if (state.isLoading && state.tray.isEmpty()) {
                items(5, key = { "story_skeleton_$it" }) { StoryTraySkeleton() }
            } else {
                items(otherEntries, key = StoryTrayEntry::authorId) { entry ->
                    StoryTrayAvatar(
                        name = entry.authorUsername,
                        photoUrl = entry.authorPhotoURL,
                        label = entry.authorUsername,
                        hasStory = true,
                        seen = entry.allSeen,
                        onClick = {
                            val index = state.tray.indexOfFirst { it.authorId == entry.authorId }
                            if (index >= 0) onOpen(index)
                        },
                    )
                }
            }
        }
        if (state.error != null && state.tray.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRetry)
                    .padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Retry", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.brandTeal)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = NbTheme.colors.border)
    }
}

@Composable
private fun StoryTrayAvatar(
    name: String,
    photoUrl: String?,
    label: String,
    hasStory: Boolean,
    seen: Boolean,
    onClick: () -> Unit,
    showAdd: Boolean = false,
    onAdd: () -> Unit = {},
) {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            NbAvatar(
                imageUrl = photoUrl,
                name = name,
                size = 68.dp,
                hasStory = hasStory,
                storySeen = seen,
                modifier = Modifier
                    .pressScale(targetScale = 0.94f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ),
            )
            if (showAdd) {
                Surface(
                    onClick = onAdd,
                    shape = CircleShape,
                    color = NbTheme.colors.brandTeal,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .border(2.dp, NbTheme.colors.surfaceBase, CircleShape),
                ) {
                    Icon(
                        imageVector = NbIcons.Plus,
                        contentDescription = "Add to your story",
                        tint = Color.White,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NbTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StoryTraySkeleton() {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(NbTheme.colors.inkFaint),
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(NbDimens.radiusFull))
                .background(NbTheme.colors.inkFaint),
        )
    }
}

@Composable
internal fun StoryViewer(
    user: UserData,
    state: StoryUiState,
    initialAuthorIndex: Int,
    onClose: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onMarkSeen: (StoryTrayEntry) -> Unit,
    onRecordView: (Story) -> Unit,
    onLoadLiked: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onDelete: (Story) -> Unit,
) {
    if (state.tray.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var authorIndex by remember { mutableStateOf(initialAuthorIndex.coerceIn(state.tray.indices)) }
    var storyIndex by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var progress by remember(storyIndex, authorIndex) { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val entry = state.tray.getOrNull(authorIndex) ?: return
    val story = entry.stories.getOrNull(storyIndex) ?: return

    fun advance() {
        val next = advanceStoryCursor(StoryCursor(authorIndex, storyIndex), state.tray)
        if (next == null) {
            onClose()
        } else {
            authorIndex = next.authorIndex
            storyIndex = next.storyIndex
        }
    }

    fun rewind() {
        val previous = rewindStoryCursor(StoryCursor(authorIndex, storyIndex), state.tray)
        authorIndex = previous.authorIndex
        storyIndex = previous.storyIndex
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(entry.authorId) { onMarkSeen(entry) }
    LaunchedEffect(story.id) {
        inputFocused = false
        onLoadLiked(story.id)
        delay(800)
        onRecordView(story)
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .graphicsLayer {
                    translationX = dragX
                    translationY = dragY
                    val dragScale = 1f - (kotlin.math.abs(dragY) / size.height.coerceAtLeast(1f) * 0.12f).coerceIn(0f, 0.12f)
                    scaleX = dragScale
                    scaleY = dragScale
                    alpha = 1f - (kotlin.math.abs(dragY) / size.height.coerceAtLeast(1f) * 0.55f).coerceIn(0f, 0.4f)
                }
                .pointerInput(story.id) {
                    detectDragGestures(
                        onDragEnd = {
                            when {
                                dragY > 120.dp.toPx() -> onClose()
                                dragX < -80.dp.toPx() -> advanceStoryCursor(
                                    StoryCursor(authorIndex, entry.stories.lastIndex),
                                    state.tray,
                                )?.let { next -> authorIndex = next.authorIndex; storyIndex = next.storyIndex }
                                dragX > 80.dp.toPx() -> rewindStoryCursor(
                                    StoryCursor(authorIndex, 0),
                                    state.tray,
                                ).let { previous -> authorIndex = previous.authorIndex; storyIndex = previous.storyIndex }
                            }
                            scope.launch {
                                Animatable(dragX).animateTo(0f, NbMotion.interactionTween()) { dragX = value }
                            }
                            scope.launch {
                                Animatable(dragY).animateTo(0f, NbMotion.interactionTween()) { dragY = value }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    }
                },
        ) {
            StoryMedia(
                story = story,
                paused = paused || inputFocused,
                muted = muted,
                onProgress = { progress = it },
                onAdvance = ::advance,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(story.id) {
                        detectTapGestures(
                            onPress = { offset ->
                                paused = true
                                val released = tryAwaitRelease()
                                paused = false
                                if (released) {
                                    if (offset.x < size.width * 0.33f) rewind() else advance()
                                }
                            },
                        )
                    },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.28f)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))),
            )
            StoryViewerTopBar(
                entry = entry,
                story = story,
                storyIndex = storyIndex,
                activeProgress = progress,
                muted = muted,
                onToggleMute = { muted = !muted },
                onProfile = {
                    onClose()
                    onOpenProfile(entry.authorId)
                },
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )

            if (entry.authorId == user.uid) {
                OwnerStoryActions(
                    busy = story.id in state.busyStoryIds,
                    onDelete = { onDelete(story); onClose() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            } else {
                StoryInteractionBar(
                    story = story,
                    liked = story.id in state.likedStoryIds,
                    busy = story.id in state.busyStoryIds,
                    replyCompleted = state.replyCompletedStoryId == story.id,
                    onFocusChanged = { inputFocused = it },
                    onLike = { onToggleLike(story.id) },
                    onReply = { onReply(story.id, it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        }
    }
}

@Composable
private fun StoryMedia(
    story: Story,
    paused: Boolean,
    muted: Boolean,
    onProgress: (Float) -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loaded by remember(story.id) { mutableStateOf(false) }
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (StoryMediaType.from(story.mediaType)) {
            StoryMediaType.Image -> {
                val animation = remember(story.id) { Animatable(0f) }
                LaunchedEffect(story.id, loaded, paused) {
                    if (!loaded || paused || animation.value >= 1f) return@LaunchedEffect
                    val duration = (story.durationMs ?: ImageStoryDurationMs).coerceAtLeast(1L)
                    animation.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = ((1f - animation.value) * duration).toInt().coerceAtLeast(1),
                            easing = LinearEasing,
                        ),
                    ) { onProgress(value) }
                    if (animation.value >= 1f) onAdvance()
                }
                AsyncImage(
                    model = story.mediaUrl,
                    contentDescription = "${story.authorUsername}'s story",
                    contentScale = ContentScale.Fit,
                    onState = { loaded = it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            StoryMediaType.Video -> StoryVideoPlayer(
                uri = story.mediaUrl,
                paused = paused,
                muted = muted,
                repeat = false,
                onReady = { loaded = true },
                onProgress = onProgress,
                onEnded = onAdvance,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!loaded) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun StoryViewerTopBar(
    entry: StoryTrayEntry,
    story: Story,
    storyIndex: Int,
    activeProgress: Float,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onProfile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = NbDimens.space12, vertical = NbDimens.space8)) {
        StoryProgressBars(
            count = entry.stories.size,
            activeIndex = storyIndex,
            activeProgress = activeProgress,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = NbDimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .clickable(onClick = onProfile)
                    .padding(vertical = NbDimens.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                NbAvatar(entry.authorPhotoURL, entry.authorUsername, size = 34.dp)
                Text(
                    entry.authorUsername,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatRelativeTime(story.createdAt), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.68f))
            }
            if (StoryMediaType.from(story.mediaType) == StoryMediaType.Video) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.semantics {
                        contentDescription = if (muted) "Unmute story" else "Mute story"
                    },
                ) {
                    Icon(
                        imageVector = if (muted) NbIcons.VolumeOff else NbIcons.Volume,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close stories" },
            ) {
                Icon(NbIcons.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun StoryProgressBars(
    count: Int,
    activeIndex: Int,
    activeProgress: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(NbDimens.radiusFull))
                    .background(Color.White.copy(alpha = 0.28f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            when {
                                index < activeIndex -> 1f
                                index > activeIndex -> 0f
                                else -> activeProgress.coerceIn(0f, 1f)
                            },
                        )
                        .background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun StoryInteractionBar(
    story: Story,
    liked: Boolean,
    busy: Boolean,
    replyCompleted: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onLike: () -> Unit,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reply by remember(story.id) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(replyCompleted) {
        if (replyCompleted) {
            reply = ""
            focusManager.clearFocus()
            onFocusChanged(false)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .padding(start = NbDimens.space12, end = NbDimens.space8, top = NbDimens.space32, bottom = NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        TextField(
            value = reply,
            onValueChange = { reply = it.take(StoryReplyLimit) },
            placeholder = { Text("Send a reply", color = Color.White.copy(alpha = 0.58f)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                reply.trim().takeIf(String::isNotBlank)?.let(onReply)
            }),
            trailingIcon = {
                AnimatedVisibility(
                    visible = reply.isNotBlank(),
                    enter = scaleIn(NbMotion.pressSpring()) + fadeIn(),
                    exit = scaleOut(NbMotion.interactionTween()) + fadeOut(),
                ) {
                    IconButton(onClick = { reply.trim().takeIf(String::isNotBlank)?.let(onReply) }, enabled = !busy) {
                        Icon(NbIcons.Send, contentDescription = "Send reply", tint = Color.White)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.16f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.12f),
                disabledContainerColor = Color.White.copy(alpha = 0.08f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Color.White,
            ),
            shape = RoundedCornerShape(NbDimens.radiusFull),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .onFocusChanged { onFocusChanged(it.isFocused) },
        )
        IconButton(onClick = onLike, enabled = !busy, modifier = Modifier.pressScale(targetScale = 0.82f)) {
            Icon(
                imageVector = if (liked) NbIcons.HeartFilled else NbIcons.Heart,
                contentDescription = if (liked) "Unlike story" else "Like story",
                tint = if (liked) Color(0xFFFF375F) else Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun OwnerStoryActions(
    busy: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space20),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            onClick = onDelete,
            enabled = !busy,
            color = Color.White.copy(alpha = 0.14f),
            shape = CircleShape,
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                NbIcons.Trash,
                contentDescription = "Delete story",
                tint = Color.White,
                modifier = Modifier.padding(NbDimens.space12),
            )
        }
    }
}

@Composable
internal fun StoryComposer(
    media: PreparedStoryMedia,
    privacy: StoryPrivacy,
    publishing: Boolean,
    onPrivacy: (StoryPrivacy) -> Unit,
    onDismiss: () -> Unit,
    onPublish: () -> Unit,
) {
    var privacyMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = !publishing, onBack = onDismiss)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!publishing) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (media.mimeType.startsWith("video/")) {
                StoryVideoPlayer(
                    uri = media.previewUri.toString(),
                    paused = false,
                    muted = false,
                    repeat = true,
                    onReady = {},
                    onProgress = {},
                    onEnded = {},
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = media.previewUri,
                    contentDescription = "Story preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.22f)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.68f), Color.Transparent))),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(NbDimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !publishing) {
                    Icon(NbIcons.Close, contentDescription = "Discard story", tint = Color.White)
                }
                Text(
                    "New story",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                    .navigationBarsPadding()
                    .padding(NbDimens.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Box {
                    Surface(
                        onClick = { privacyMenu = true },
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(NbDimens.radiusFull),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = NbDimens.space14, vertical = NbDimens.space12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                        ) {
                            Icon(NbIcons.Profile, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text(privacy.label(), style = MaterialTheme.typography.labelLarge, color = Color.White)
                            Icon(NbIcons.ChevronDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(
                        expanded = privacyMenu,
                        onDismissRequest = { privacyMenu = false },
                    ) {
                        StoryPrivacy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label()) },
                                onClick = { onPrivacy(option); privacyMenu = false },
                                trailingIcon = if (option == privacy) {
                                    { Icon(NbIcons.Check, contentDescription = null, tint = NbTheme.colors.brandTeal) }
                                } else null,
                            )
                        }
                    }
                }
                NbButton(
                    text = "Share story",
                    onClick = onPublish,
                    enabled = !publishing,
                    loading = publishing,
                    modifier = Modifier.weight(1f).height(50.dp),
                )
            }
        }
    }
}

@Composable
internal fun StoryPreparingDialog() {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Text("Preparing story", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

@Composable
private fun StoryVideoPlayer(
    uri: String,
    paused: Boolean,
    muted: Boolean,
    repeat: Boolean,
    onReady: () -> Unit,
    onProgress: (Float) -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = !paused
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) onReady()
                if (playbackState == Player.STATE_ENDED && !repeat) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, paused) {
        player.playWhenReady = !paused
        if (!paused) player.play()
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player) {
        while (isActive) {
            val duration = player.duration.takeIf { it > 0L } ?: 1L
            onProgress((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
            delay(50)
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { useController = false; this.player = player } },
        update = { it.player = player },
        modifier = modifier,
    )
}

private fun StoryPrivacy.label(): String = when (this) {
    StoryPrivacy.Public -> "Everyone"
    StoryPrivacy.Followers -> "Followers"
    StoryPrivacy.CloseFriends -> "Close friends"
}
