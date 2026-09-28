package com.nextbench.app.create

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbCard
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.firebase.PostPrivacy
import com.nextbench.data.firebase.PostComposerRepository
import com.nextbench.data.firebase.PostType
import com.nextbench.data.model.UserData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    user: UserData?,
    onOpenSell: () -> Unit,
    onOpenPost: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscardSheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.prepareImages(uris)
    }

    LaunchedEffect(state.publishedPostId) {
        state.publishedPostId?.let { postId ->
            viewModel.consumePublishedPost()
            onOpenPost(postId)
        }
    }

    BackHandler(enabled = state.step == CreateStep.Compose && !state.isPublishing) {
        if (state.hasDraft) showDiscardSheet = true else viewModel.discardDraft()
    }

    AnimatedContent(
        targetState = state.step,
        transitionSpec = {
            (slideInHorizontally(animationSpec = NbMotion.interactionTween()) { it / 8 } + fadeIn()) togetherWith
                (slideOutHorizontally(animationSpec = NbMotion.interactionTween()) { -it / 8 } + fadeOut())
        },
        label = "create_step",
        modifier = modifier.fillMaxSize(),
    ) { step ->
        when (step) {
            CreateStep.Choose -> CreateChooser(
                user = user,
                onPost = viewModel::startPost,
                onSell = onOpenSell,
            )
            CreateStep.Compose -> PostComposer(
                user = user,
                state = state,
                onBack = {
                    if (state.hasDraft) showDiscardSheet = true else viewModel.discardDraft()
                },
                onType = viewModel::selectType,
                onPrivacy = viewModel::selectPrivacy,
                onAnonymous = viewModel::setAnonymous,
                onTitle = viewModel::setTitle,
                onContent = viewModel::setContent,
                onPickImages = { picker.launch("image/*") },
                onRemoveImage = viewModel::removeImage,
                onPublish = { user?.let(viewModel::publish) },
            )
        }
    }

    if (showDiscardSheet) {
        NbBottomSheet(onDismiss = { showDiscardSheet = false }) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Text("Discard this post?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Your draft and selected images will be removed.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton(
                    text = "Discard draft",
                    onClick = { showDiscardSheet = false; viewModel.discardDraft() },
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Primary,
                )
                NbButton(
                    text = "Keep editing",
                    onClick = { showDiscardSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
private fun CreateChooser(
    user: UserData?,
    onPost: () -> Unit,
    onSell: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NbTheme.colors.surfaceBase),
        contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space20),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = NbDimens.space4), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                Text("Make something useful", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                Text("Share with your campus or pass something on to someone nearby.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
            }
        }
        item {
            CreateActionCard(
                icon = NbIcons.Plus,
                title = "Start a post",
                message = "Ask a question, share notes, announce an event, or say what is on your mind.",
                accent = NbTheme.colors.brandPink,
                badge = if (user?.verified == true) "Ready to publish" else "Verification required",
                onClick = onPost,
            )
        }
        item {
            CreateActionCard(
                icon = NbIcons.Marketplace,
                title = "List an item",
                message = "Sell books, electronics, and campus essentials to people you can trust.",
                accent = NbTheme.colors.brandTeal,
                badge = "Marketplace",
                onClick = onSell,
            )
        }
        item {
            Surface(
                color = NbTheme.colors.brandTeal.copy(alpha = 0.07f),
                shape = RoundedCornerShape(NbDimens.radiusMd),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(NbDimens.space14), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Text("Keep it useful and respectful", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                        Text("Verified members make NextBench feel safer for everyone.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    accent: Color,
    badge: String,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(targetValue = 1f, animationSpec = NbMotion.pressSpring(), label = "create_action_scale")
    NbCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .pressScale(onTap = onClick)
            .semantics { role = Role.Button; contentDescription = title },
    ) {
        Column(modifier = Modifier.padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(NbDimens.radiusMd)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            NbPill(label = badge, contentColor = accent)
        }
    }
}

@Composable
private fun PostComposer(
    user: UserData?,
    state: CreateUiState,
    onBack: () -> Unit,
    onType: (PostType) -> Unit,
    onPrivacy: (PostPrivacy) -> Unit,
    onAnonymous: (Boolean) -> Unit,
    onTitle: (String) -> Unit,
    onContent: (String) -> Unit,
    onPickImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onPublish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space8, vertical = NbDimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !state.isPublishing, modifier = Modifier.semantics { contentDescription = "Back to create choices" }) {
                Icon(NbIcons.Back, contentDescription = null, tint = NbTheme.colors.inkMuted)
            }
            Text("New post", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
            if (state.hasDraft) NbPill(label = "Draft", contentColor = NbTheme.colors.brandTeal, modifier = Modifier.padding(end = NbDimens.space8))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space8),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    NbAvatar(imageUrl = user?.profilePicture, name = user?.name ?: "Student", size = NbDimens.avatarLg)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Text(if (state.type == PostType.Confession && state.anonymous) user?.anonymousPersonaName ?: "Anonymous" else user?.name ?: "Student", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(user?.school ?: "Your campus", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { TypeSelector(selected = state.type, onSelect = onType) }
            item { PrivacySelector(selected = state.privacy, onSelect = onPrivacy) }
            item {
                NbTextField(
                    value = state.title,
                    onValueChange = onTitle,
                    label = "Title",
                    placeholder = "Give your post a clear headline",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                NbTextField(
                    value = state.content,
                    onValueChange = onContent,
                    label = "Message",
                    placeholder = "What's on your mind?",
                    singleLine = false,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                )
            }
            if (state.type == PostType.Confession) {
                item { AnonymousToggle(enabled = state.anonymous, onToggle = onAnonymous) }
            }
            item {
                MediaSection(
                    images = state.images,
                    isPreparing = state.isPreparingMedia,
                    onPickImages = onPickImages,
                    onRemoveImage = onRemoveImage,
                )
            }
            state.error?.let { error ->
                item { ErrorBanner(error) }
            }
        }
        PublishBar(state = state, onPublish = onPublish)
    }
}

@Composable
private fun TypeSelector(selected: PostType, onSelect: (PostType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Text("What is this about?", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            PostType.entries.forEach { type ->
                val active = type == selected
                Surface(
                    color = if (active) NbTheme.colors.ink else NbTheme.colors.surfaceCard,
                    shape = RoundedCornerShape(NbDimens.radiusFull),
                    modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusFull)).clickable(role = Role.Tab, onClick = { onSelect(type) }).semantics { role = Role.Tab; contentDescription = type.label },
                ) {
                    Text(type.label, style = MaterialTheme.typography.labelMedium, color = if (active) NbTheme.colors.surfaceBase else NbTheme.colors.inkMuted, modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space8))
                }
            }
        }
        Text(selected.description, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun PrivacySelector(selected: PostPrivacy, onSelect: (PostPrivacy) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Icon(if (selected == PostPrivacy.Public) NbIcons.Home else NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(18.dp))
        Text("Visible to", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
        PostPrivacy.entries.forEach { privacy ->
            val active = privacy == selected
            Text(
                privacy.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                color = if (active) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted,
                modifier = Modifier.clip(RoundedCornerShape(NbDimens.radiusFull)).clickable(role = Role.RadioButton, onClick = { onSelect(privacy) }).padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
            )
        }
    }
}

@Composable
private fun AnonymousToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(color = NbTheme.colors.brandPink.copy(alpha = 0.07f), shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = { onToggle(!enabled) })) {
        Row(modifier = Modifier.padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Icon(NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Text("Post anonymously", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Your persona is shown instead of your name.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Box(modifier = Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(NbDimens.radiusFull)).background(if (enabled) NbTheme.colors.brandPink else NbTheme.colors.surfaceSoft).padding(3.dp), contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

@Composable
private fun MediaSection(
    images: List<ComposerImage>,
    isPreparing: Boolean,
    onPickImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Text("Add images", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Up to ${PostComposerRepository.MaxImages} photos", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Text("${images.size}/${PostComposerRepository.MaxImages}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            images.forEach { image ->
                Box(modifier = Modifier.size(78.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft)) {
                    AsyncImage(model = image.uri, contentDescription = "Selected image", modifier = Modifier.fillMaxSize())
                    IconButton(onClick = { onRemoveImage(image.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.56f)).semantics { contentDescription = "Remove selected image" }) {
                        Icon(NbIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }
            if (images.size < PostComposerRepository.MaxImages) {
                Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusSm), modifier = Modifier.size(78.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).clickable(onClick = onPickImages).semantics { role = Role.Button; contentDescription = "Add images" }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isPreparing) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp) else Icon(NbIcons.Camera, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PublishBar(state: CreateUiState, onPublish: () -> Unit) {
    Surface(color = NbTheme.colors.surfaceCard, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space8), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            AnimatedVisibility(visible = state.isPublishing, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(state.progress?.label ?: "Publishing your post", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
                    androidx.compose.material3.LinearProgressIndicator(progress = { state.progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth(), color = NbTheme.colors.brandTeal, trackColor = NbTheme.colors.surfaceSoft)
                }
            }
            NbButton(text = if (state.isPublishing) "Publishing..." else "Publish post", onClick = onPublish, enabled = state.canPublish, loading = state.isPublishing, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = NbTheme.colors.brandPink.copy(alpha = 0.09f), shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(NbDimens.space12), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Icon(NbIcons.Refresh, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.brandPink)
        }
    }
}
