package com.nextbench.app.post

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextbench.app.feed.PostCardBody
import com.nextbench.data.firebase.PostVote
import com.nextbench.data.model.PostReply
import com.nextbench.data.model.UserData
import com.nextbench.core.common.formatRelativeTime
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
import com.nextbench.data.firebase.PostDetailRepository

private enum class DetailAccessRequest { SignIn, Verify }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostDetailScreen(
    user: UserData?,
    onOpenProfile: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val composerFocusRequester = remember { FocusRequester() }
    var accessRequest by remember { mutableStateOf<DetailAccessRequest?>(null) }
    val viewer = remember(user?.uid, user?.verified) { DetailViewer(user) }
    val rows = remember(state.replies) { flattenReplies(state.replies) }
    val context = LocalContext.current

    LaunchedEffect(user?.uid, user?.verified) { viewModel.syncViewer(user) }
    LaunchedEffect(state.replyTarget?.id) {
        if (state.replyTarget != null && viewer.verified) composerFocusRequester.requestFocus()
    }
    val requestComposer = {
        when {
            !viewer.signedIn -> accessRequest = DetailAccessRequest.SignIn
            !viewer.verified -> accessRequest = DetailAccessRequest.Verify
            else -> composerFocusRequester.requestFocus()
        }
    }

    PostDetailContent(
        state = state,
        viewer = viewer,
        rows = rows,
        listState = listState,
        onRefresh = viewModel::refresh,
        onRetryPost = viewModel::retryPost,
        onRetryReplies = viewModel::retryReplies,
        onOpenProfile = onOpenProfile,
        onVote = { vote, doubleTap ->
            when {
                !viewer.signedIn -> {
                    accessRequest = DetailAccessRequest.SignIn
                    false
                }
                !viewer.verified -> {
                    accessRequest = DetailAccessRequest.Verify
                    false
                }
                doubleTap && vote == PostVote.Up && state.upvoted -> false
                else -> viewModel.toggleVote(vote)
            }
        },
        onSave = {
            when {
                !viewer.signedIn -> accessRequest = DetailAccessRequest.SignIn
                else -> viewModel.toggleSave()
            }
        },
        onStartReply = { reply ->
            if (viewer.verified) viewModel.startReply(reply) else requestComposer()
        },
        onRequestComposer = requestComposer,
        onClearReplyTarget = viewModel::clearReplyTarget,
        onTextChanged = viewModel::setComposerText,
        onSubmitReply = {
            when {
                !viewer.signedIn -> accessRequest = DetailAccessRequest.SignIn
                !viewer.verified -> accessRequest = DetailAccessRequest.Verify
                else -> viewModel.submitReply()
            }
        },
        onOpenAttachment = { url -> openExternal(context, url) },
        composerFocusRequester = composerFocusRequester,
        modifier = modifier,
    )

    state.notice?.let { notice ->
        DetailToast(
            notice = notice,
            onDismiss = { viewModel.dismissNotice(notice.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    accessRequest?.let { request ->
        DetailAccessSheet(
            request = request,
            onDismiss = { accessRequest = null },
            onContinue = {
                accessRequest = null
                if (request == DetailAccessRequest.SignIn) onSignIn() else onVerify()
            },
        )
    }
}

private data class DetailViewer(
    val user: UserData?,
) {
    val signedIn: Boolean get() = user?.uid?.isNotBlank() == true
    val verified: Boolean get() = user?.verified == true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PostDetailContent(
    state: PostDetailUiState,
    viewer: DetailViewer,
    rows: List<ReplyRow>,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onRetryPost: () -> Unit,
    onRetryReplies: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onVote: (PostVote, Boolean) -> Boolean,
    onSave: () -> Unit,
    onStartReply: (PostReply) -> Unit,
    onRequestComposer: () -> Unit,
    onClearReplyTarget: () -> Unit,
    onTextChanged: (String) -> Unit,
    onSubmitReply: () -> Unit,
    onOpenAttachment: (String) -> Unit,
    composerFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val pullState = rememberPullToRefreshState()
    val interactionEnabled = when {
        !viewer.signedIn || !viewer.verified -> true
        else -> state.interactionsReady && !state.interactionBusy
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isInitialLoading -> DetailLoading()
                    state.initialError != null && state.post == null -> DetailError(
                        message = state.initialError,
                        onRetry = onRetryPost,
                    )
                    state.post == null -> DetailError(
                        message = "This post is no longer available.",
                        onRetry = onRetryPost,
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NbDimens.space24),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
                    ) {
                        item(key = "post") {
                            Surface(
                                color = NbTheme.colors.surfaceCard,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                PostCardBody(
                                    post = state.post,
                                    upvoted = state.upvoted,
                                    downvoted = state.downvoted,
                                    saved = state.saved,
                                    interactionsEnabled = interactionEnabled,
                                    onOpen = {},
                                    onComments = onRequestComposer,
                                    onAttachment = onOpenAttachment,
                                    onProfile = {
                                        if (!state.post.isAnonymous && state.post.authorId.isNotBlank()) {
                                            onOpenProfile(state.post.authorId)
                                        }
                                    },
                                    onVote = onVote,
                                    onSave = onSave,
                                    expanded = true,
                                )
                            }
                        }

                        item(key = "conversation_header") {
                            ConversationHeader(
                                count = state.post.repliesCount,
                                loading = state.isLoadingReplies,
                                signedIn = viewer.signedIn,
                            )
                        }

                        if (!viewer.signedIn) {
                            item(key = "guest_conversation") {
                                JoinConversationCard(onSignIn = onRequestComposer)
                            }
                        } else if (state.repliesError != null && state.replies.isEmpty()) {
                            item(key = "replies_error") {
                                RepliesError(message = state.repliesError, onRetry = onRetryReplies)
                            }
                        } else if (state.isLoadingReplies && state.replies.isEmpty()) {
                            items(3, key = { "reply_skeleton_$it" }) { ReplySkeleton() }
                        } else if (rows.isEmpty()) {
                            item(key = "replies_empty") {
                                RepliesEmpty(onFocusComposer = onRequestComposer)
                            }
                        } else {
                            items(
                                items = rows,
                                key = { row -> row.reply.id },
                                contentType = { "reply" },
                            ) { row ->
                                ReplyCard(
                                    row = row,
                                    onReply = { onStartReply(row.reply) },
                                    onProfile = {
                                        if (row.reply.authorId.isNotBlank()) onOpenProfile(row.reply.authorId)
                                    },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.post != null) {
            DetailComposer(
                viewer = viewer,
                state = state,
                onTextChanged = onTextChanged,
                onSubmit = onSubmitReply,
                onClearReplyTarget = onClearReplyTarget,
                focusRequester = composerFocusRequester,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

@Composable
private fun ConversationHeader(
    count: Int,
    loading: Boolean,
    signedIn: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NbTheme.colors.surfaceBase)
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Icon(NbIcons.Reply, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
        AnimatedContent(targetState = count, label = "reply_count") { value ->
            Text(
                text = "$value ${if (value == 1) "reply" else "replies"}",
                style = MaterialTheme.typography.titleMedium,
                color = NbTheme.colors.ink,
            )
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = NbTheme.colors.brandTeal,
            )
        }
        Spacer(Modifier.weight(1f))
        if (!signedIn) NbPill(label = "Preview", contentColor = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun JoinConversationCard(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16),
    ) {
        Surface(
            color = NbTheme.colors.brandTeal.copy(alpha = 0.10f),
            shape = RoundedCornerShape(NbDimens.radiusLg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(NbDimens.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(NbTheme.colors.brandTeal.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.brandTeal) }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text("Join the conversation", style = MaterialTheme.typography.titleSmall, color = NbTheme.colors.ink)
                    Text("Sign in to read replies and add your perspective.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                }
                NbButton(
                    text = "Sign in",
                    onClick = onSignIn,
                    variant = NbButtonVariant.Secondary,
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun RepliesEmpty(onFocusComposer: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Reply,
        title = "Start the thread",
        message = "Be the first verified student to add a thoughtful reply.",
        modifier = Modifier.padding(horizontal = NbDimens.space16),
        action = {
            NbButton(
                text = "Write a reply",
                onClick = onFocusComposer,
                variant = NbButtonVariant.Ghost,
            )
        },
    )
}

@Composable
private fun RepliesError(message: String, onRetry: () -> Unit) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusLg),
        modifier = Modifier.padding(horizontal = NbDimens.space16),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space12),
        ) {
            Icon(NbIcons.Refresh, contentDescription = null, tint = NbTheme.colors.brandPink)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted, modifier = Modifier.weight(1f))
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelLarge,
                color = NbTheme.colors.brandTeal,
                modifier = Modifier
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = NbDimens.space8, vertical = NbDimens.space8),
            )
        }
    }
}

@Composable
private fun ReplyCard(
    row: ReplyRow,
    onReply: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reply = row.reply
    val author = reply.authorName.ifBlank { "Student" }
    val metadata = listOfNotNull(
        reply.authorSchool.takeIf(String::isNotBlank),
        reply.createdAt?.toDate()?.time?.let(::formatRelativeTime),
    ).joinToString("  |  ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (NbDimens.space16 + (row.depth * 18).dp).coerceAtMost(70.dp), end = NbDimens.space16),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        verticalAlignment = Alignment.Top,
    ) {
        if (row.depth > 0) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(78.dp)
                    .background(NbTheme.colors.brandTeal.copy(alpha = 0.25f)),
            )
        }
        Surface(
            color = NbTheme.colors.surfaceCard,
            shape = RoundedCornerShape(NbDimens.radiusSm),
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier.padding(NbDimens.space12),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    NbAvatar(imageUrl = reply.authorProfilePicture, name = author, size = 32.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                            Text(
                                text = author,
                                style = MaterialTheme.typography.titleSmall,
                                color = NbTheme.colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    enabled = reply.authorId.isNotBlank(),
                                    role = Role.Button,
                                    onClick = onProfile,
                                ),
                            )
                        }
                        Text(metadata, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = onReply,
                        modifier = Modifier.semantics { contentDescription = "Reply to $author" },
                    ) { Icon(NbIcons.Reply, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(19.dp)) }
                }
                row.parentAuthorName?.let { parentName ->
                    Text(
                        text = "Replying to $parentName",
                        style = MaterialTheme.typography.labelSmall,
                        color = NbTheme.colors.brandTeal,
                    )
                }
                if (reply.content.isNotBlank()) {
                    Text(
                        text = reply.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NbTheme.colors.ink,
                    )
                }
                reply.imageUrl?.takeIf(String::isNotBlank)?.let { imageUrl ->
                    coil.compose.AsyncImage(
                        model = imageUrl,
                        contentDescription = "Reply image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(NbDimens.radiusSm)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                if (reply.edited) {
                    Text("Edited", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun DetailComposer(
    viewer: DetailViewer,
    state: PostDetailUiState,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearReplyTarget: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            AnimatedVisibility(
                visible = state.replyTarget != null,
                enter = fadeIn(NbMotion.interactionTween()) + slideInVertically(NbMotion.interactionTween()),
                exit = fadeOut(NbMotion.interactionTween()) + slideOutVertically(NbMotion.interactionTween()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NbDimens.radiusSm))
                        .background(NbTheme.colors.brandTeal.copy(alpha = 0.09f))
                        .padding(horizontal = NbDimens.space8, vertical = NbDimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Replying to ${state.replyTarget?.authorName.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NbTheme.colors.brandTeal,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClearReplyTarget, modifier = Modifier.size(28.dp)) {
                        Icon(NbIcons.Close, contentDescription = "Cancel reply", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(17.dp))
                    }
                }
            }

            when {
                !viewer.signedIn -> ComposerAccessRow(
                    icon = NbIcons.Profile,
                    title = "Sign in to reply",
                    action = "Sign in",
                    onClick = onSubmit,
                )
                !viewer.verified -> ComposerAccessRow(
                    icon = NbIcons.Shield,
                    title = "Verify your student account to reply",
                    action = "Verify",
                    onClick = onSubmit,
                )
                else -> VerifiedComposer(
                    state = state,
                    onTextChanged = onTextChanged,
                    onSubmit = onSubmit,
                    focusRequester = focusRequester,
                )
            }
        }
    }
}

@Composable
private fun ComposerAccessRow(
    icon: ImageVector,
    title: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        Icon(icon, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted, modifier = Modifier.weight(1f))
        NbButton(text = action, onClick = onClick, variant = NbButtonVariant.Secondary)
    }
}

@Composable
private fun VerifiedComposer(
    state: PostDetailUiState,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = state.composerText.trim().isNotEmpty() && !state.isSubmitting
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        NbTextField(
            value = state.composerText,
            onValueChange = onTextChanged,
            placeholder = "Share something useful...",
            modifier = Modifier.weight(1f),
            singleLine = false,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = {
                if (canSubmit) {
                    onSubmit()
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }),
            focusRequester = focusRequester,
            trailingIcon = {
                Text(
                    text = "${state.composerText.length}/${PostDetailRepository.ReplyCharacterLimit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.composerText.length >= PostDetailRepository.ReplyCharacterLimit) NbTheme.colors.brandPink else NbTheme.colors.inkMuted,
                )
            },
        )
        IconButton(
            onClick = {
                onSubmit()
                if (canSubmit) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            },
            enabled = canSubmit,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (canSubmit) NbTheme.colors.brandPink else NbTheme.colors.surfaceSoft)
                .semantics { contentDescription = "Post reply" },
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(NbIcons.Send, contentDescription = null, tint = if (canSubmit) Color.White else NbTheme.colors.inkMuted, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun ReplySkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        NbSkeletonBox(modifier = Modifier.size(32.dp), radius = NbDimens.radiusFull)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            NbSkeletonLine(widthFraction = 0.35f)
            NbSkeletonLine(widthFraction = 0.82f)
            NbSkeletonLine(widthFraction = 0.58f)
        }
    }
}

@Composable
private fun DetailLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NbDimens.space16),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
    ) {
        NbSkeletonBox(modifier = Modifier.fillMaxWidth().height(230.dp), radius = NbDimens.radiusLg)
        NbSkeletonLine(widthFraction = 0.62f)
        NbSkeletonLine(widthFraction = 0.94f)
        NbSkeletonLine(widthFraction = 0.78f)
        Spacer(Modifier.height(NbDimens.space16))
        repeat(3) { ReplySkeleton() }
    }
}

@Composable
private fun DetailError(message: String, onRetry: () -> Unit) {
    NbEmptyState(
        icon = NbIcons.Home,
        title = "Post unavailable",
        message = message,
        modifier = Modifier.fillMaxSize(),
        action = {
            NbButton(text = "Try again", onClick = onRetry, variant = NbButtonVariant.Secondary)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailAccessSheet(
    request: DetailAccessRequest,
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
                    imageVector = if (request == DetailAccessRequest.SignIn) NbIcons.Profile else NbIcons.Shield,
                    contentDescription = null,
                    tint = NbTheme.colors.brandTeal,
                )
            }
            Text(
                text = if (request == DetailAccessRequest.SignIn) "Join the conversation" else "Verify your student account",
                style = MaterialTheme.typography.titleLarge,
                color = NbTheme.colors.ink,
            )
            Text(
                text = if (request == DetailAccessRequest.SignIn) {
                    "Sign in to read replies and take part in your campus community."
                } else {
                    "Verification keeps replies accountable and the community useful."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
            NbButton(
                text = if (request == DetailAccessRequest.SignIn) "Sign in" else "Verify now",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                variant = NbButtonVariant.Secondary,
            )
            NbButton(text = "Not now", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
        }
    }
}

@Composable
private fun DetailToast(
    notice: PostDetailNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(notice.id) {
        kotlinx.coroutines.delay(3_000)
        onDismiss()
    }
    val color by animateColorAsState(
        targetValue = when (notice.kind) {
            PostDetailNoticeKind.Success -> NbTheme.colors.brandMint
            PostDetailNoticeKind.Error -> NbTheme.colors.brandPink
            PostDetailNoticeKind.Info -> NbTheme.colors.ink
        },
        animationSpec = NbMotion.interactionTween(),
        label = "detail_toast_color",
    )
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(NbMotion.interactionTween()) + scaleIn(initialScale = 0.96f),
        exit = fadeOut(NbMotion.interactionTween()),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(NbDimens.space16),
            contentAlignment = Alignment.BottomCenter,
        ) {
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

private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
