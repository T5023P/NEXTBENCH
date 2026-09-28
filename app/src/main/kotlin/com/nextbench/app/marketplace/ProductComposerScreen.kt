package com.nextbench.app.marketplace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.firebase.ProductComposerRepository
import com.nextbench.data.model.UserData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductComposerScreen(
    user: UserData?,
    onOpenProduct: (String) -> Unit,
    onBack: () -> Unit,
    productId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: ProductComposerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscardSheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.prepareImages(uris)
    }

    LaunchedEffect(state.publishedProductId) {
        state.publishedProductId?.let { id ->
            viewModel.consumePublishedProduct()
            onOpenProduct(id)
        }
    }

    LaunchedEffect(productId, user?.uid) {
        productId?.let { viewModel.loadForEdit(it, user) }
    }

    BackHandler(enabled = !state.isPublishing) {
        if (state.hasDraft) showDiscardSheet = true else onBack()
    }

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space8, vertical = NbDimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (state.hasDraft) showDiscardSheet = true else onBack() }, enabled = !state.isPublishing, modifier = Modifier.semantics { contentDescription = "Back to create choices" }) {
                Icon(NbIcons.Back, contentDescription = null, tint = NbTheme.colors.inkMuted)
            }
            Text(if (productId == null) "List an item" else "Edit listing", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
            if (state.hasDraft) NbPill(label = "Draft", contentColor = NbTheme.colors.brandTeal, modifier = Modifier.padding(end = NbDimens.space8))
        }

        if (state.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = NbTheme.colors.brandTeal)
            }
        } else if (productId != null && state.editingProductId == null) {
            LoadFailure(
                message = state.error ?: "This listing could not be loaded.",
                onRetry = { viewModel.loadForEdit(productId, user) },
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NbDimens.space16, vertical = NbDimens.space8),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    NbAvatar(imageUrl = user?.profilePicture, name = user?.name ?: "Student", size = NbDimens.avatarLg)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Text(user?.name ?: "Student", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                        Text(user?.school ?: "Your campus", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    NbPill(label = "Verified seller", contentColor = NbTheme.colors.brandTeal)
                }
            }
            item {
                ImageSection(
                    images = state.images,
                    isPreparing = state.isPreparingMedia,
                    onPickImages = { picker.launch("image/*") },
                    onRemoveImage = viewModel::removeImage,
                )
            }
            item {
                NbTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = "Listing title",
                    placeholder = "What are you passing on?",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.title.length}/${ProductComposerRepository.MaxTitleLength}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint, modifier = Modifier.fillMaxWidth().padding(top = NbDimens.space4, end = NbDimens.space4))
            }
            item { PriceCategorySection(state, viewModel::setPrice, viewModel::setCategory, viewModel::setCondition) }
            item {
                NbTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = "Description",
                    placeholder = "Describe the condition, history, and what is included...",
                    singleLine = false,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Text("${state.description.length}/${ProductComposerRepository.MaxDescriptionLength}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint, modifier = Modifier.fillMaxWidth().padding(top = NbDimens.space4, end = NbDimens.space4))
            }
            item { FulfillmentSection(state, viewModel::setMeetup, viewModel::setDelivery) }
            state.error?.let { error -> item { ErrorBanner(error) } }
        }
        PublishBar(state = state, onPublish = { user?.let(viewModel::publish) })
    }

    if (showDiscardSheet) {
        NbBottomSheet(onDismiss = { showDiscardSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                Text(if (productId == null) "Discard this listing?" else "Discard your changes?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text(if (productId == null) "Your draft and selected images will be removed." else "Your listing will stay unchanged.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton(text = if (productId == null) "Discard draft" else "Discard changes", onClick = { showDiscardSheet = false; viewModel.discardDraft(); onBack() }, modifier = Modifier.fillMaxWidth())
                NbButton(text = "Keep editing", onClick = { showDiscardSheet = false }, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun LoadFailure(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(NbDimens.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NbIcons.Refresh, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(NbDimens.space12))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
        Spacer(Modifier.height(NbDimens.space16))
        NbButton(text = "Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        NbButton(text = "Go back", onClick = onBack, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
    }
}

@Composable
private fun ImageSection(
    images: List<ProductComposerImage>,
    isPreparing: Boolean,
    onPickImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Text("Photos", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Show the item clearly. Up to ${ProductComposerRepository.MaxImages} photos.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Text("${images.size}/${ProductComposerRepository.MaxImages}", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint)
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            images.forEachIndexed { index, image ->
                Box(modifier = Modifier.size(112.dp).clip(RoundedCornerShape(NbDimens.radiusMd)).background(NbTheme.colors.surfaceSoft)) {
                    AsyncImage(model = image.uri ?: image.remoteUrl, contentDescription = "Listing photo ${index + 1}", modifier = Modifier.fillMaxSize())
                    if (index == 0) NbPill(label = "Cover", contentColor = Color.White, containerColor = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    IconButton(onClick = { onRemoveImage(image.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.56f)).semantics { contentDescription = "Remove listing photo ${index + 1}" }) {
                        Icon(NbIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (images.size < ProductComposerRepository.MaxImages) {
                Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.size(112.dp).clip(RoundedCornerShape(NbDimens.radiusMd)).clickable(onClick = onPickImages).semantics { role = Role.Button; contentDescription = "Add listing photos" }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isPreparing) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                            Icon(NbIcons.Camera, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(25.dp))
                            Text("Add photos", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.brandTeal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceCategorySection(
    state: ProductComposerState,
    onPrice: (String) -> Unit,
    onCategory: (String) -> Unit,
    onCondition: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            NbTextField(value = state.price, onValueChange = onPrice, label = "Price", placeholder = "₹ 0", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(0.8f))
            SelectorField(label = "Condition", value = state.condition, options = ProductComposerRepository.Conditions, onSelect = onCondition, modifier = Modifier.weight(1.2f))
        }
        SelectorField(label = "Category", value = state.category, options = ProductComposerCategories, onSelect = onCategory, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SelectorField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
            Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NbDimens.radiusMd)).clickable { expanded = true }.semantics { role = Role.Button; contentDescription = "Choose $label" }) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space14), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = NbTheme.colors.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(NbIcons.ArrowDown, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}

@Composable
private fun FulfillmentSection(state: ProductComposerState, onMeetup: (Boolean) -> Unit, onDelivery: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Text("How can someone receive it?", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            FulfillmentChip(label = "Campus meetup", icon = NbIcons.Home, selected = state.meetupAvailable, onClick = { onMeetup(!state.meetupAvailable) }, modifier = Modifier.weight(1f))
            FulfillmentChip(label = "Delivery", icon = NbIcons.Marketplace, selected = state.deliveryAvailable, onClick = { onDelivery(!state.deliveryAvailable) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FulfillmentChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = if (selected) NbTheme.colors.brandTeal.copy(alpha = 0.1f) else NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = modifier.clip(RoundedCornerShape(NbDimens.radiusMd)).clickable(onClick = onClick).semantics { role = Role.Checkbox; contentDescription = "$label ${if (selected) "enabled" else "disabled"}" }) {
        Row(modifier = Modifier.padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Icon(icon, contentDescription = null, tint = if (selected) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) NbTheme.colors.brandTeal else NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PublishBar(state: ProductComposerState, onPublish: () -> Unit) {
    Surface(color = NbTheme.colors.surfaceCard, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space8), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            AnimatedVisibility(visible = state.isPublishing, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(state.progress?.label ?: "Publishing your listing", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
                    LinearProgressIndicator(progress = { state.progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth(), color = NbTheme.colors.brandTeal, trackColor = NbTheme.colors.surfaceSoft)
                }
            }
            NbButton(text = if (state.isPublishing) "Saving..." else if (state.editingProductId == null) "Submit listing" else "Save changes", onClick = onPublish, enabled = state.canPublish, loading = state.isPublishing, modifier = Modifier.fillMaxWidth())
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
