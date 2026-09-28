package com.nextbench.app.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nextbench.app.auth.deletionConfirmationAccepted
import com.nextbench.core.common.formatRelativeTime
import com.nextbench.core.common.formatRupees
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbCard
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbOutlinedButton
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonBox
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbToast
import com.nextbench.core.designsystem.NbToastKind
import com.nextbench.core.designsystem.NbToastState
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.NbVerifiedBadge
import com.nextbench.core.designsystem.NbVerifiedPill
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.ProductStatus
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VerificationStatus
import com.nextbench.core.designsystem.NbBottomSheet
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: UserData?,
    onOpenListing: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenVerification: () -> Unit,
    onOpenNotifications: () -> Unit,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit,
    signOutLoading: Boolean,
    signOutError: String?,
    onDismissSignOutError: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    deleteAccountLoading: Boolean,
    deleteAccountError: String?,
    onDismissDeleteAccountError: () -> Unit,
    resumeDeletion: Boolean = false,
    onDeleteAccountResumeDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::prepareProfilePicture)
    }
    var showSignOutSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDeleteAccountSheet by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf("") }
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }
    // The user came back after signing in again to finish a deletion. Reopen the confirmation with
    // nothing pre-filled: a fresh sign-in must not silently authorize an irreversible delete.
    LaunchedEffect(resumeDeletion, state.user) {
        if (!resumeDeletion || state.user == null || showDeleteAccountSheet) return@LaunchedEffect
        showSettingsSheet = false
        showSignOutSheet = false
        deleteConfirmation = ""
        showDeleteAccountSheet = true
    }
    LaunchedEffect(signOutError) {
        if (signOutError != null) showSignOutSheet = false
    }
    LaunchedEffect(state.editor.open, state.editor.username) {
        if (!state.editor.open) return@LaunchedEffect
        delay(420)
        viewModel.checkUsername()
    }

    Box(modifier = modifier.fillMaxSize()) {
        ProfileContent(
            state = state,
            onSelectTab = viewModel::selectTab,
            onRefresh = viewModel::retry,
            onOpenListing = onOpenListing,
            onOpenPost = onOpenPost,
            onOpenSaved = onOpenSaved,
            onOpenMessages = onOpenMessages,
            onOpenInvite = onOpenInvite,
            onOpenVerification = onOpenVerification,
            onOpenSettings = { showSettingsSheet = true },
            onEditProfile = viewModel::openEditor,
            onSignOut = { showSignOutSheet = true },
            modifier = Modifier.fillMaxSize(),
        )
        state.notice?.let { notice ->
            NbToast(
                state = NbToastState(
                    message = notice.message,
                    kind = if (notice.kind == ProfileNoticeKind.Success) NbToastKind.Success else NbToastKind.Error,
                    visible = true,
                ),
                onDismiss = { viewModel.dismissNotice(notice.id) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showSignOutSheet) {
        NbBottomSheet(onDismiss = { if (!signOutLoading) showSignOutSheet = false }) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Text("Sign out of NextBench?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("Your conversations and saved listings will be waiting when you return.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton(
                    text = "Sign out",
                    onClick = onSignOut,
                    loading = signOutLoading,
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Primary,
                )
                NbButton(
                    text = "Keep me signed in",
                    onClick = { showSignOutSheet = false },
                    enabled = !signOutLoading,
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Ghost,
                )
            }
        }
    }

    if (showSettingsSheet && state.user != null) {
        NbBottomSheet(onDismiss = { showSettingsSheet = false }) {
            ProfileSettingsSheet(
                user = state.user!!,
                onToggleTheme = onToggleTheme,
                onToggleFollowersOnly = viewModel::setFollowersOnly,
                onToggleHideLastSeen = viewModel::setHideLastSeen,
                onOpenSaved = { showSettingsSheet = false; onOpenSaved() },
                onOpenInvite = { showSettingsSheet = false; onOpenInvite() },
                onOpenNotifications = { showSettingsSheet = false; onOpenNotifications() },
                onEditProfile = { showSettingsSheet = false; viewModel.openEditor() },
                onSignOut = { showSettingsSheet = false; showSignOutSheet = true },
                onDeleteAccount = { showSettingsSheet = false; deleteConfirmation = ""; showDeleteAccountSheet = true },
            )
        }
    }

    if (state.editor.open && state.user != null) {
        NbBottomSheet(onDismiss = viewModel::closeEditor) {
            ProfileEditorSheet(
                profile = state.user!!,
                state = state.editor,
                onDismiss = viewModel::closeEditor,
                onPickProfilePicture = {
                    profilePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveProfilePicture = viewModel::removeProfilePicture,
                onNameChange = viewModel::setEditorName,
                onAboutChange = viewModel::setEditorAbout,
                onUsernameChange = viewModel::setEditorUsername,
                onCheckUsername = viewModel::checkUsername,
                onSave = viewModel::saveProfile,
            )
        }
    }

    if (showDeleteAccountSheet) {
        // Irreversible, so the user must type the phrase and the count of what is removed is
        // stated before confirming. The deletion itself runs server-side and only clears the
        // session once the callable confirms it.
        NbBottomSheet(
            onDismiss = {
                if (!deleteAccountLoading) {
                    showDeleteAccountSheet = false
                    onDeleteAccountResumeDismissed()
                }
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Text(
                    "Delete your account?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NbTheme.colors.ink,
                )
                if (resumeDeletion) {
                    Text(
                        "You're signed in again. Confirm once more to finish deleting your account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NbTheme.colors.inkMuted,
                    )
                }
                Text(
                    "This permanently removes your profile, posts, listings, stories, saved items, " +
                        "follows, blocks, notifications, club memberships, chat participation, push " +
                        "tokens, and your verification documents. Reports you filed are kept for " +
                        "moderation with your identity removed. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NbTheme.colors.inkMuted,
                )
                NbTextField(
                    value = deleteConfirmation,
                    onValueChange = { deleteConfirmation = it },
                    label = "Type DELETE to confirm",
                    placeholder = "DELETE",
                    enabled = !deleteAccountLoading,
                    error = deleteAccountError.orEmpty(),
                )
                NbButton(
                    text = "Delete my account",
                    onClick = { onDeleteAccount(deleteConfirmation) },
                    enabled = deletionConfirmationAccepted(deleteConfirmation) && !deleteAccountLoading,
                    loading = deleteAccountLoading,
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Primary,
                )
                NbButton(
                    text = "Keep my account",
                    onClick = {
                        onDismissDeleteAccountError()
                        showDeleteAccountSheet = false
                        onDeleteAccountResumeDismissed()
                    },
                    enabled = !deleteAccountLoading,
                    modifier = Modifier.fillMaxWidth(),
                    variant = NbButtonVariant.Ghost,
                )
            }
        }
    }

    if (signOutError != null) {
        NbBottomSheet(onDismiss = onDismissSignOutError) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Text("Could not sign you out", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text(signOutError, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton("Try again", onClick = onSignOut, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileContent(
    state: ProfileUiState,
    onSelectTab: (ProfileTab) -> Unit,
    onRefresh: () -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenVerification: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.user
    when {
        state.isLoading && profile == null -> ProfileLoading(modifier)
        state.error != null && profile == null -> NbEmptyState(
            icon = NbIcons.Refresh,
            title = "Your profile is taking a moment",
            message = state.error,
            action = {
                Text("Tap to retry", color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = onRefresh).padding(NbDimens.space8))
            },
            modifier = modifier.fillMaxSize(),
        )
        profile == null -> NbEmptyState(
            icon = NbIcons.Profile,
            title = "Your space is unavailable",
            message = "Sign in again to view your profile.",
            modifier = modifier.fillMaxSize(),
        )
        else -> ProfileLoaded(
            state = state,
            profile = profile,
            onSelectTab = onSelectTab,
            onOpenListing = onOpenListing,
            onOpenPost = onOpenPost,
            onOpenSaved = onOpenSaved,
            onOpenMessages = onOpenMessages,
            onOpenInvite = onOpenInvite,
            onOpenVerification = onOpenVerification,
            onOpenSettings = onOpenSettings,
            onEditProfile = onEditProfile,
            onSignOut = onSignOut,
            modifier = modifier,
        )
    }
}

@Composable
private fun ProfileLoaded(
    state: ProfileUiState,
    profile: UserData,
    onSelectTab: (ProfileTab) -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenVerification: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = NbDimens.space32),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        item(key = "identity") {
            ProfileIdentity(
                profile = profile,
                onOpenVerification = onOpenVerification,
                onOpenSettings = onOpenSettings,
                onEditProfile = onEditProfile,
            )
        }
        item(key = "stats") {
            ProfileStatsRow(
                followers = state.followersCount,
                following = state.followingCount,
                listings = state.listings.size,
                posts = state.posts.size,
            )
        }
        item(key = "shortcuts") {
            ProfileActionStrip(
                actions = listOf(
                    ProfileAction(NbIcons.Bookmark, "Saved", onOpenSaved, NbTheme.colors.brandTeal),
                    ProfileAction(NbIcons.Messages, "Messages", onOpenMessages, NbTheme.colors.brandTeal),
                    ProfileAction(NbIcons.Share, "Invite", onOpenInvite, NbTheme.colors.brandPink),
                ),
            )
        }
        item(key = "activity-header") {
            ProfileActivityTabs(state.tab, state.listings.size, state.posts.size, onSelectTab)
        }
        item(key = "activity-content") {
            AnimatedContent(
                targetState = state.tab,
                transitionSpec = {
                    (slideInHorizontally(animationSpec = NbMotion.interactionTween()) { it / 8 } + fadeIn()) togetherWith
                        (slideOutHorizontally(animationSpec = NbMotion.interactionTween()) { -it / 8 } + fadeOut()) using
                        SizeTransform(clip = false)
                },
                label = "profile_activity_tab",
            ) { tab ->
                when (tab) {
                    ProfileTab.Listings -> ProfileListings(state.listings, onOpenListing)
                    ProfileTab.Posts -> ProfilePosts(state.posts, onOpenPost)
                }
            }
        }
    }
}

@Composable
private fun ProfileIdentity(
    profile: UserData,
    onOpenVerification: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(148.dp).background(NbTheme.colors.surfaceSoft)) {
            profile.coverPhoto?.takeIf(String::isNotBlank)?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = "Profile cover photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NbDimens.space12)
                    .clip(CircleShape)
                    .background(NbTheme.colors.surfaceCard.copy(alpha = 0.90f))
                    .semantics { contentDescription = "Open settings" },
            ) {
                Icon(NbIcons.More, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(19.dp))
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = NbDimens.space20),
            verticalAlignment = Alignment.Bottom,
        ) {
            NbAvatar(
                imageUrl = profile.profilePicture,
                name = profile.name.ifBlank { "Student" },
                size = 88.dp,
                modifier = Modifier.padding(top = 0.dp).offset(y = (-28).dp),
            )
            Spacer(Modifier.width(NbDimens.space12))
            Column(modifier = Modifier.padding(bottom = NbDimens.space12), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    Text(profile.name.ifBlank { "Student" }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.verified) NbVerifiedBadge(size = 18.dp)
                }
                profile.username?.takeIf(String::isNotBlank)?.let { username ->
                    Text("@$username", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space20),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            val location = listOfNotNull(profile.school.takeIf(String::isNotBlank), profile.city.takeIf(String::isNotBlank)).joinToString("  ·  ")
            if (location.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    Icon(NbIcons.Building, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(17.dp))
                    Text(location, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            profile.about?.takeIf(String::isNotBlank)?.let { about ->
                Text(about, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), verticalAlignment = Alignment.CenterVertically) {
                val status = VerificationStatus.from(profile.verificationStatus)
                if (profile.verified) {
                    NbVerifiedPill()
                } else {
                    NbPill(label = verificationLabel(status), contentColor = verificationColor(status))
                    Text("Verify profile", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.brandTeal, modifier = Modifier.clickable(onClick = onOpenVerification).padding(NbDimens.space4))
                }
                Text("Reputation ${profile.reputation.cleanScore()}", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
            }
            NbOutlinedButton(
                text = "Edit profile",
                onClick = onEditProfile,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProfileEditorSheet(
    profile: UserData,
    state: ProfileEditorState,
    onDismiss: () -> Unit,
    onPickProfilePicture: () -> Unit,
    onRemoveProfilePicture: () -> Unit,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onCheckUsername: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NbDimens.space20),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text("Edit profile", style = MaterialTheme.typography.titleLarge, color = NbTheme.colors.ink)
                Text("Keep your public details current.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            IconButton(onClick = onDismiss, enabled = !state.isSaving) {
                Icon(NbIcons.Close, contentDescription = "Close edit profile", tint = NbTheme.colors.inkMuted)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(NbTheme.colors.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                val imageModel: Any? = state.selectedProfilePicture ?: if (state.removeProfilePicture) null else profile.profilePicture
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = "Selected profile picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(34.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), modifier = Modifier.padding(top = NbDimens.space8)) {
                TextButton(onClick = onPickProfilePicture, enabled = !state.isSaving && !state.isPreparingImage) {
                    if (state.isPreparingImage) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                    } else {
                        Icon(NbIcons.Camera, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(NbDimens.space4))
                        Text("Choose photo", color = NbTheme.colors.brandTeal)
                    }
                }
                if (profile.profilePicture != null || state.selectedProfilePicture != null) {
                    TextButton(onClick = onRemoveProfilePicture, enabled = !state.isSaving && !state.isPreparingImage) {
                        Icon(NbIcons.Trash, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(NbDimens.space4))
                        Text("Remove", color = NbTheme.colors.brandPink)
                    }
                }
            }
        }

        NbTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = "Display name",
            placeholder = "Your name",
            enabled = !state.isSaving,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
        )
        NbTextField(
            value = state.about,
            onValueChange = onAboutChange,
            label = "About",
            placeholder = "A short introduction",
            enabled = !state.isSaving,
            singleLine = false,
            maxLines = 5,
        )
        NbTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = "Username",
            placeholder = "your.username",
            enabled = !state.isSaving,
            error = state.usernameError.orEmpty(),
            leadingIcon = { Text("@", color = NbTheme.colors.inkMuted, style = MaterialTheme.typography.bodyLarge) },
            trailingIcon = {
                when {
                    state.isCheckingUsername -> CircularProgressIndicator(modifier = Modifier.size(17.dp), color = NbTheme.colors.brandTeal, strokeWidth = 2.dp)
                    state.usernameAvailable == true -> Icon(NbIcons.Check, contentDescription = "Username available", tint = NbTheme.colors.brandMint, modifier = Modifier.size(18.dp))
                    state.usernameAvailable == false -> Icon(NbIcons.Close, contentDescription = "Username unavailable", tint = NbTheme.colors.brandPink, modifier = Modifier.size(18.dp))
                }
            },
        )
        if (state.usernameError == "Availability could not be checked.") {
            TextButton(onClick = onCheckUsername, enabled = !state.isCheckingUsername && !state.isSaving) {
                Text("Check again", color = NbTheme.colors.brandTeal)
            }
        }
        Text("Usernames can be changed once every 30 days.", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)

        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.brandPink)
        }
        NbButton(
            text = if (state.isSaving) "Saving..." else "Save changes",
            onClick = onSave,
            enabled = state.canSave,
            loading = state.isSaving,
            modifier = Modifier.fillMaxWidth(),
            variant = NbButtonVariant.Secondary,
        )
    }
}

@Composable
private fun ProfileListings(listings: List<Product>, onOpen: (String) -> Unit) {
    if (listings.isEmpty()) {
        ProfileEmptyActivity(icon = NbIcons.Marketplace, title = "No listings yet", message = "Items you list for your campus will show up here.")
        return
    }
    Column(modifier = Modifier.padding(horizontal = NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        listings.forEach { product ->
            ProfileListingRow(product = product, onOpen = { onOpen(product.id) })
        }
    }
}

@Composable
private fun ProfileListingRow(product: Product, onOpen: () -> Unit) {
    val image = product.images.firstOrNull() ?: product.image
    NbCard(modifier = Modifier.fillMaxWidth().pressScale(onTap = onOpen)) {
        Row(modifier = Modifier.padding(NbDimens.space12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Box(modifier = Modifier.size(74.dp).clip(RoundedCornerShape(NbDimens.radiusSm)).background(NbTheme.colors.surfaceSoft), contentAlignment = Alignment.Center) {
                if (image.isNullOrBlank()) Icon(NbIcons.Marketplace, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(28.dp))
                else AsyncImage(model = image, contentDescription = product.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text(product.title.ifBlank { "Untitled listing" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    Text(formatRupees(product.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                    Text(product.condition.ifBlank { "Used" }, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                    NbPill(label = profileStatusLabel(product.status), contentColor = profileStatusColor(product.status))
                    product.createdAt?.toDate()?.time?.let { Text(formatRelativeTime(it), style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint) }
                }
            }
            Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ProfilePosts(posts: List<Post>, onOpen: (String) -> Unit) {
    if (posts.isEmpty()) {
        ProfileEmptyActivity(icon = NbIcons.Home, title = "No posts yet", message = "Questions, notes, and campus updates you share will appear here.")
        return
    }
    Column(modifier = Modifier.padding(horizontal = NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        posts.forEach { post -> ProfilePostRow(post, onOpen = { onOpen(post.id) }) }
    }
}

@Composable
private fun ProfilePostRow(post: Post, onOpen: () -> Unit) {
    NbCard(modifier = Modifier.fillMaxWidth().pressScale(onTap = onOpen)) {
        Column(modifier = Modifier.padding(NbDimens.space14), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbPill(label = postTypeLabel(post.type), contentColor = NbTheme.colors.brandTeal)
                Spacer(Modifier.weight(1f))
                post.createdAt?.toDate()?.time?.let { Text(formatRelativeTime(it), style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint) }
            }
            Text(post.title.ifBlank { post.content.ifBlank { "Untitled post" } }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (post.title.isNotBlank() && post.content.isNotBlank()) Text(post.content, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                Text("${post.upvotesCount} upvotes", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                Text("${post.repliesCount} replies", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
            }
        }
    }
}

@Composable
private fun ProfileEmptyActivity(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    NbEmptyState(icon = icon, title = title, message = message, modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space16, vertical = NbDimens.space16))
}

@Composable
private fun ProfileLoading(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        NbSkeletonBox(Modifier.fillMaxWidth().height(156.dp), radius = 0.dp)
        Row(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalAlignment = Alignment.CenterVertically) {
            NbSkeletonBox(Modifier.size(88.dp), radius = NbDimens.radiusFull)
            Spacer(Modifier.width(NbDimens.space12))
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) { NbSkeletonLine(widthFraction = 0.48f, height = 18.dp); NbSkeletonLine(widthFraction = 0.30f) }
        }
        Column(modifier = Modifier.padding(horizontal = NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) { NbSkeletonLine(widthFraction = 0.62f); NbSkeletonLine(widthFraction = 0.90f, height = 14.dp); NbSkeletonLine(widthFraction = 0.76f, height = 14.dp) }
        Row(modifier = Modifier.padding(horizontal = NbDimens.space16), horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) { repeat(3) { NbSkeletonBox(Modifier.weight(1f).height(72.dp), radius = NbDimens.radiusMd) } }
    }
}

private fun postTypeLabel(type: String): String = when (type.lowercase()) {
    "info" -> "Info"
    "notes" -> "Notes"
    "event" -> "Event"
    "confession" -> "Confession"
    else -> "Post"
}

private fun profileStatusLabel(status: String): String = when (ProductStatus.from(status)) {
    ProductStatus.Available -> "Available"
    ProductStatus.Reserved -> "Reserved"
    ProductStatus.Sold -> "Sold"
    ProductStatus.Pending -> "Under review"
    ProductStatus.Rejected -> "Unavailable"
}

@Composable
private fun profileStatusColor(status: String): Color = when (ProductStatus.from(status)) {
    ProductStatus.Available -> NbTheme.colors.brandMint
    ProductStatus.Reserved -> Color(0xFFB7791F)
    ProductStatus.Sold, ProductStatus.Rejected -> NbTheme.colors.brandPink
    ProductStatus.Pending -> NbTheme.colors.brandTeal
}

private fun verificationLabel(status: VerificationStatus): String = when (status) {
    VerificationStatus.Pending -> "Verification pending"
    VerificationStatus.Approved -> "Verified soon"
    VerificationStatus.Rejected -> "Verification needs attention"
    VerificationStatus.FlaggedManual -> "Under manual review"
}

@Composable
private fun verificationColor(status: VerificationStatus): Color = when (status) {
    VerificationStatus.Rejected -> NbTheme.colors.brandPink
    VerificationStatus.Pending, VerificationStatus.FlaggedManual -> Color(0xFFB7791F)
    VerificationStatus.Approved -> NbTheme.colors.brandMint
}

private fun Double.cleanScore(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
