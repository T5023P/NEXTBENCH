package com.nextbench.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextbench.app.navigation.NbBottomBar
import com.nextbench.app.navigation.NbNavHost
import com.nextbench.app.navigation.NbRoute
import com.nextbench.app.navigation.NbTab
import com.nextbench.app.navigation.navigateToTab
import com.nextbench.app.auth.AuthViewModel
import com.nextbench.app.marketplace.MarketplacePreviewRoute
import com.nextbench.app.marketplace.ProductDetailPreviewRoute
import com.nextbench.app.onboarding.OnboardingViewModel
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.firebase.SessionState
import kotlinx.coroutines.delay

/**
 * The single app chrome used by every authenticated screen. Keeping the scaffold
 * outside the graph means tab state survives destination transitions and the
 * bottom bar never jumps while a screen animates.
 */
@Composable
fun NbAppShell(
    onToggleTheme: () -> Unit,
    pendingExternalIntent: Intent? = null,
    onExternalIntentHandled: (Intent) -> Unit = {},
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPath = backStackEntry?.destination?.route
    val chrome = resolveChrome(currentPath)
    val selectedTab = NbRoute.tabFor(currentPath) ?: NbTab.Feed
    val session by authViewModel.session.collectAsStateWithLifecycle()
    val signedInUid = (session as? SessionState.SignedIn)?.firebaseUser?.uid
    val onboardingState by onboardingViewModel.state.collectAsStateWithLifecycle()
    var feedChromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(currentPath) {
        if (currentPath != NbRoute.Feed.path) feedChromeVisible = true
    }
    val showAnimatedChrome = currentPath != NbRoute.Feed.path || feedChromeVisible

    PushPermissionCoordinator(signedInUid)
    PresenceCoordinator(signedInUid, authViewModel::setPresence)
    val pendingShareIntent = pendingExternalIntent?.takeIf { intent ->
        intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
    }
    LaunchedEffect(pendingExternalIntent, signedInUid, onboardingState.completed, currentPath) {
        val externalIntent = pendingExternalIntent ?: return@LaunchedEffect
        val initialJourneyFinished = currentPath != NbRoute.Splash.path && currentPath != NbRoute.Onboarding.path
        if (onboardingState.completed == true && initialJourneyFinished) {
            if (externalIntent.action == Intent.ACTION_SEND || externalIntent.action == Intent.ACTION_SEND_MULTIPLE) {
                if (shouldOpenShareRoute(currentPath)) navController.navigate(NbRoute.Share.path) { launchSingleTop = true }
            } else {
                navController.handleDeepLink(externalIntent)
                onExternalIntentHandled(externalIntent)
            }
        }
    }

    BackHandler(enabled = chrome.canNavigateBack) {
        navigateBackOrHome(navController)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
        containerColor = NbTheme.colors.surfaceBase,
        topBar = {
            AnimatedVisibility(
                visible = chrome.showTopBar && showAnimatedChrome,
                enter = slideInVertically(NbMotion.interactionTween()) { -it } + fadeIn(NbMotion.interactionTween()),
                exit = slideOutVertically(NbMotion.interactionTween()) { -it } + fadeOut(NbMotion.interactionTween()),
            ) {
                NbTopBar(
                    path = currentPath,
                    canNavigateBack = chrome.canNavigateBack,
                    onBack = { navigateBackOrHome(navController) },
                    onSearch = { navController.navigate(NbRoute.Search.path) },
                    onNotifications = { navController.navigate(NbRoute.Notifications.path) },
                    onToggleTheme = onToggleTheme,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = chrome.showBottomBar && showAnimatedChrome,
                enter = slideInVertically(NbMotion.interactionTween()) { it } + fadeIn(NbMotion.interactionTween()),
                exit = slideOutVertically(NbMotion.interactionTween()) { it } + fadeOut(NbMotion.interactionTween()),
            ) {
                NbBottomBar(
                    selected = selectedTab,
                    onSelect = navController::navigateToTab,
                )
            }
        },
    ) { innerPadding ->
        NbNavHost(
            navController = navController,
            authViewModel = authViewModel,
            onboardingViewModel = onboardingViewModel,
            onToggleTheme = onToggleTheme,
            onFeedChromeVisibilityChanged = { visible ->
                if (currentPath == NbRoute.Feed.path) feedChromeVisible = visible
            },
            pendingShareIntent = pendingShareIntent,
            onShareIntentConsumed = onExternalIntentHandled,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

internal fun shouldOpenShareRoute(currentPath: String?): Boolean = currentPath !in setOf(
    NbRoute.Share.path,
    NbRoute.Login.path,
    NbRoute.Signup.path,
    NbRoute.OrgSignup.path,
    NbRoute.Verification.path,
    NbRoute.Auth.path,
)

@Composable
private fun PresenceCoordinator(
    uid: String?,
    setPresence: suspend (String, Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uid, foreground) {
        val activeUid = uid ?: return@LaunchedEffect
        setPresence(activeUid, foreground)
        if (!foreground) return@LaunchedEffect
        while (true) {
            delay(PresenceHeartbeatMillis)
            setPresence(activeUid, true)
        }
    }
}

private const val PresenceHeartbeatMillis = 60_000L

private fun navigateBackOrHome(navController: NavHostController) {
    if (!navController.popBackStack()) {
        navController.navigateToTab(NbTab.Feed)
    }
}

@Composable
private fun NbTopBar(
    path: String?,
    canNavigateBack: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val title = when {
        path == NbRoute.Feed.path -> "Community"
        path == NbRoute.Marketplace.path -> "Marketplace"
        path == MarketplacePreviewRoute -> "Marketplace"
        path == ProductDetailPreviewRoute -> "Listing"
        path == NbRoute.Create.path -> "Create"
        path == NbRoute.Messages.path -> "Messages"
        path == NbRoute.Clubs.path -> "Clubs"
        path == NbRoute.Profile.path -> "Your space"
        path == NbRoute.Search.path -> "Search"
        path == NbRoute.Notifications.path -> "Notifications"
        path == NbRoute.Wishlist.path -> "Saved"
        path == NbRoute.Invite.path -> "Invite friends"
        path == NbRoute.Share.path -> "Share to NextBench"
        path == NbRoute.Sell.path -> "List an item"
        path?.startsWith("edit-item/") == true -> "Edit listing"
        path?.startsWith("product/") == true -> "Listing"
        path?.startsWith("post/") == true -> "Post"
        path?.startsWith("profile/") == true || path?.startsWith("u/") == true -> "Profile"
        path?.startsWith("chat/") == true || path?.startsWith("messages/") == true -> "Conversation"
        path?.startsWith("club/") == true -> "Club"
        else -> "NextBench"
    }
    val isTopLevel = NbRoute.isTopLevel(path)

    Surface(
        color = NbTheme.colors.surfaceBase.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NbDimens.topBarHeight)
                .padding(horizontal = NbDimens.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        ) {
            if (canNavigateBack) {
                NbChromeIconButton(
                    icon = NbIcons.Back,
                    description = "Go back",
                    onClick = onBack,
                )
            } else {
                NbLogo(size = 32.dp)
            }

            Text(
                text = title,
                style = if (isTopLevel) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = NbTheme.colors.ink,
                modifier = Modifier.weight(1f),
            )

            if (isTopLevel) {
                if (path == NbRoute.Profile.path) {
                    NbChromeIconButton(
                        icon = if (NbTheme.colors.isDark) NbIcons.Sun else NbIcons.Moon,
                        description = "Toggle theme",
                        onClick = onToggleTheme,
                    )
                } else if (path != NbRoute.Search.path) {
                    NbChromeIconButton(NbIcons.Search, "Search", onSearch)
                }
                NbChromeIconButton(NbIcons.Bell, "Notifications", onNotifications)
            }
        }
    }
}

internal data class NbChromeState(
    val showTopBar: Boolean,
    val showBottomBar: Boolean,
    val canNavigateBack: Boolean,
)

internal fun resolveChrome(path: String?): NbChromeState {
    if (path == null || path == NbRoute.Splash.path) {
        return NbChromeState(false, false, false)
    }

    val chromeFree = NbRoute.chromeFree.any { it.path == path }
    val topLevel = NbRoute.isTopLevel(path)
    return NbChromeState(
        showTopBar = !chromeFree,
        showBottomBar = !chromeFree && topLevel,
        canNavigateBack = !chromeFree && !topLevel,
    )
}

@Composable
private fun NbChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .pressScale(targetScale = 0.92f)
            .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NbTheme.colors.inkMuted,
        )
    }
}
