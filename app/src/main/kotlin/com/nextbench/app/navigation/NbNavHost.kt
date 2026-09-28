package com.nextbench.app.navigation

import android.content.Intent

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nextbench.app.auth.AuthGate
import com.nextbench.app.auth.AuthViewModel
import com.nextbench.app.auth.requirementForRoute
import com.nextbench.app.auth.AuthScreen
import com.nextbench.app.auth.OrganizationSignupScreen
import com.nextbench.app.chat.ChatRoomScreen
import com.nextbench.app.chat.MessagesScreen
import com.nextbench.app.create.CreateScreen
import com.nextbench.app.feed.CommunityScreen
import com.nextbench.app.marketplace.MarketplaceScreen
import com.nextbench.app.marketplace.MarketplacePreviewRoute
import com.nextbench.app.marketplace.MarketplacePreviewScreen
import com.nextbench.app.marketplace.ProductDetailPreviewRoute
import com.nextbench.app.marketplace.ProductDetailPreviewScreen
import com.nextbench.app.marketplace.ProductDetailScreen
import com.nextbench.app.marketplace.ProductComposerScreen
import com.nextbench.app.marketplace.WishlistScreen
import com.nextbench.app.notifications.NotificationsScreen
import com.nextbench.app.onboarding.OnboardingScreen
import com.nextbench.app.onboarding.OnboardingViewModel
import com.nextbench.app.invite.InviteScreen
import com.nextbench.app.legal.LegalDocument
import com.nextbench.app.legal.LegalDocumentScreen
import com.nextbench.app.clubs.ClubChatScreen
import com.nextbench.app.clubs.ClubJoinScreen
import com.nextbench.app.clubs.ClubSettingsScreen
import com.nextbench.app.clubs.ClubsScreen
import com.nextbench.app.post.PostDetailScreen
import com.nextbench.app.profile.ProfileScreen
import com.nextbench.app.profile.PublicProfileScreen
import com.nextbench.app.search.SearchScreen
import com.nextbench.app.share.ShareTargetScreen
import com.nextbench.data.firebase.SessionState
import com.nextbench.app.ui.SplashScreen
import com.nextbench.app.verification.VerificationScreen
import com.nextbench.core.designsystem.NbMotion

/**
 * Push forward: incoming screen scales up from 96 % + fades in while the
 * outgoing screen scales down to 96 % + fades out.  This mirrors the iOS
 * depth-of-field navigation feel without a distracting full-width slide.
 *
 * Pop (back): directions are reversed so it reads as "coming back".
 */
private val NbEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = NbMotion.navTween()) +
        scaleIn(initialScale = 0.96f, animationSpec = NbMotion.navTween())
}

private val NbExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = NbMotion.navTween()) +
        scaleOut(targetScale = 0.96f, animationSpec = NbMotion.navTween())
}

private val NbPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = NbMotion.navTween()) +
        scaleIn(initialScale = 1.04f, animationSpec = NbMotion.navTween())
}

private val NbPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = NbMotion.navTween()) +
        scaleOut(targetScale = 1.04f, animationSpec = NbMotion.navTween())
}

/**
 * The whole app graph. Feature screens are added behind these stable route
 * contracts, so deep links and notification targets remain valid while each
 * vertical slice is implemented.
 */
@Composable
fun NbNavHost(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    onboardingViewModel: OnboardingViewModel,
    onToggleTheme: () -> Unit,
    onFeedChromeVisibilityChanged: (Boolean) -> Unit = {},
    pendingShareIntent: Intent? = null,
    onShareIntentConsumed: (Intent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val session by authViewModel.session.collectAsStateWithLifecycle()
    val onboardingState by onboardingViewModel.state.collectAsStateWithLifecycle()
    // A deletion waiting on a fresh sign-in reopens its confirmation on the profile screen.
    val deletionPending by authViewModel.pendingDeletion.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = NbRoute.Splash.path,
        modifier = modifier,
        enterTransition = NbEnter,
        exitTransition = NbExit,
        popEnterTransition = NbPopEnter,
        popExitTransition = NbPopExit,
    ) {
        composable(NbRoute.Splash.path) {
            SplashScreen(
                appReady = session !is SessionState.Loading && onboardingState.completed != null,
                onFinished = {
                    val onboardingComplete = onboardingState.completed ?: return@SplashScreen
                    navController.navigate(initialDestination(onboardingComplete)) {
                        popUpTo(NbRoute.Splash.path) { inclusive = true }
                    }
                },
            )
        }

        composable(NbRoute.Onboarding.path) {
            OnboardingScreen(
                isCompleting = onboardingState.isCompleting,
                error = onboardingState.error,
                onComplete = onboardingViewModel::complete,
            )
            LaunchedEffect(onboardingState.completed) {
                if (onboardingState.completed == true) {
                    navController.navigate(NbRoute.Feed.path) {
                        popUpTo(NbRoute.Onboarding.path) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }

        composable(NbRoute.Feed.path) {
            CommunityScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenPost = { postId ->
                    navController.navigate(NbRoute.post(postId)) { launchSingleTop = true }
                },
                onOpenProfile = { userId ->
                    navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true }
                },
                onOpenProduct = { productId ->
                    navController.navigate(NbRoute.product(productId)) { launchSingleTop = true }
                },
                onChromeVisibilityChanged = onFeedChromeVisibilityChanged,
                onSignIn = {
                    navController.navigate(NbRoute.Login.path) { launchSingleTop = true }
                },
                onVerify = {
                    navController.navigate(NbRoute.Verification.path) { launchSingleTop = true }
                },
            )
        }

        composable(NbRoute.Marketplace.path) {
            MarketplaceScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenProduct = { productId ->
                    navController.navigate(NbRoute.product(productId)) { launchSingleTop = true }
                },
                onOpenProfile = { userId ->
                    navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true }
                },
                onOpenWishlist = {
                    navController.navigate(NbRoute.Wishlist.path) { launchSingleTop = true }
                },
                onSell = {
                    navController.navigate(NbRoute.Sell.path) { launchSingleTop = true }
                },
                onSignIn = {
                    navController.navigate(NbRoute.Login.path) { launchSingleTop = true }
                },
                onVerify = {
                    navController.navigate(NbRoute.Verification.path) { launchSingleTop = true }
                },
            )
        }
        if (com.nextbench.app.BuildConfig.DEBUG) {
            composable(
                route = MarketplacePreviewRoute,
                deepLinks = listOf(navDeepLink { uriPattern = "nextbench://marketplace/preview" }),
            ) {
                MarketplacePreviewScreen()
            }
        }

        composable(
            route = NbRoute.Create.path,
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/create" },
                navDeepLink { uriPattern = "nextbench://create" },
            ),
        ) {
            GuardedDestination(navController, NbRoute.Create.path, authViewModel) {
                CreateScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenSell = {
                        navController.navigate(NbRoute.Sell.path) { launchSingleTop = true }
                    },
                    onOpenPost = { postId ->
                        navController.navigate(NbRoute.post(postId)) { launchSingleTop = true }
                    },
                )
            }
        }

        composable(NbRoute.Share.path) {
            GuardedDestination(navController, NbRoute.Share.path, authViewModel) {
                ShareTargetScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    incomingIntent = pendingShareIntent,
                    onIntentConsumed = onShareIntentConsumed,
                    onSent = { target ->
                        val route = when (target.type) {
                            com.nextbench.data.firebase.ForwardTargetType.Direct -> NbRoute.messages(target.id)
                            com.nextbench.data.firebase.ForwardTargetType.Club -> NbRoute.clubMessages(target.id)
                        }
                        navController.navigate(route) {
                            popUpTo(NbRoute.Share.path) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }

        composable(
            route = NbRoute.Messages.path,
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/messages" },
                navDeepLink { uriPattern = "nextbench://messages" },
            ),
        ) {
            GuardedDestination(navController, NbRoute.Messages.path, authViewModel) {
                MessagesScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenRoom = { roomId ->
                        navController.navigate(NbRoute.messages(roomId)) { launchSingleTop = true }
                    },
                    onOpenClub = { clubId ->
                        navController.navigate(NbRoute.clubMessages(clubId)) { launchSingleTop = true }
                    },
                    onOpenClubs = { navController.navigate(NbRoute.Clubs.path) { launchSingleTop = true } },
                )
            }
        }

        composable(NbRoute.Clubs.path) {
            GuardedDestination(navController, NbRoute.Clubs.path, authViewModel) {
                ClubsScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenClub = { clubId -> navController.navigate(NbRoute.club(clubId)) { launchSingleTop = true } },
                )
            }
        }

        composable(NbRoute.Profile.path) {
            GuardedDestination(navController, NbRoute.Profile.path, authViewModel) {
                val signOutState by authViewModel.signOutState.collectAsStateWithLifecycle()
                val deleteAccountState by authViewModel.deleteAccountState.collectAsStateWithLifecycle()
                ProfileScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenListing = { productId ->
                        navController.navigate(NbRoute.product(productId)) { launchSingleTop = true }
                    },
                    onOpenPost = { postId ->
                        navController.navigate(NbRoute.post(postId)) { launchSingleTop = true }
                    },
                    onOpenSaved = {
                        navController.navigate(NbRoute.Wishlist.path) { launchSingleTop = true }
                    },
                    onOpenMessages = {
                        navController.navigate(NbRoute.Messages.path) { launchSingleTop = true }
                    },
                    onOpenInvite = {
                        navController.navigate(NbRoute.Invite.path) { launchSingleTop = true }
                    },
                    onOpenVerification = {
                        navController.navigate(NbRoute.Verification.path) { launchSingleTop = true }
                    },
                    onOpenNotifications = {
                        navController.navigate(NbRoute.Notifications.path) { launchSingleTop = true }
                    },
                    onToggleTheme = onToggleTheme,
                    onSignOut = authViewModel::signOut,
                    signOutLoading = signOutState.isLoading,
                    signOutError = signOutState.error?.message,
                    onDismissSignOutError = authViewModel::clearSignOutError,
                    onDeleteAccount = authViewModel::deleteAccount,
                    deleteAccountLoading = deleteAccountState.isLoading,
                    deleteAccountError = deleteAccountState.error?.message,
                    onDismissDeleteAccountError = authViewModel::clearDeleteAccountError,
                    resumeDeletion = deletionPending,
                    onDeleteAccountResumeDismissed = authViewModel::clearPendingDeletion,
                )
            }
        }

        composable(
            route = NbRoute.Search.path,
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/search" },
                navDeepLink { uriPattern = "nextbench://search" },
            ),
        ) {
            SearchScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                onOpenPost = { postId -> navController.navigate(NbRoute.post(postId)) { launchSingleTop = true } },
                onOpenListing = { productId -> navController.navigate(NbRoute.product(productId)) { launchSingleTop = true } },
                onOpenClub = { clubId -> navController.navigate(NbRoute.club(clubId)) { launchSingleTop = true } },
            )
        }

        composable(
            route = NbRoute.Notifications.path,
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/notifications" },
                navDeepLink { uriPattern = "nextbench://notifications" },
            ),
        ) {
            GuardedDestination(navController, NbRoute.Notifications.path, authViewModel) {
                NotificationsScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenLink = { link ->
                        val route = notificationRoute(link)
                        if (route != null) navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }
        }

        composable(NbRoute.Wishlist.path) {
            GuardedDestination(navController, NbRoute.Wishlist.path, authViewModel) {
                WishlistScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenProduct = { productId ->
                        navController.navigate(NbRoute.product(productId)) { launchSingleTop = true }
                    },
                    onBrowse = {
                        navController.navigate(NbRoute.Marketplace.path) { launchSingleTop = true }
                    },
                )
            }
        }
        composable(NbRoute.Sell.path) {
            GuardedDestination(navController, NbRoute.Sell.path, authViewModel) {
                ProductComposerScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenProduct = { productId ->
                        navController.navigate(NbRoute.product(productId)) { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(
            route = NbRoute.EditItem.path,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) {
            GuardedDestination(navController, NbRoute.EditItem.path, authViewModel) {
                ProductComposerScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    productId = it.arguments?.getString("productId"),
                    onOpenProduct = { productId ->
                        navController.navigate(NbRoute.product(productId)) {
                            popUpTo(NbRoute.EditItem.path) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = NbRoute.ProductDetail.path,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/product/{productId}" },
                navDeepLink { uriPattern = "nextbench://product/{productId}" },
            ),
        ) {
            GuardedDestination(navController, NbRoute.ProductDetail.path, authViewModel) {
                ProductDetailScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenProfile = { userId ->
                        navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true }
                    },
                    onOpenChat = { roomId ->
                        navController.navigate(NbRoute.messages(roomId)) { launchSingleTop = true }
                    },
                    onEdit = { productId ->
                        navController.navigate(NbRoute.editItem(productId)) { launchSingleTop = true }
                    },
                    onSignIn = {
                        navController.navigate(NbRoute.Login.path) { launchSingleTop = true }
                    },
                    onVerify = {
                        navController.navigate(NbRoute.Verification.path) { launchSingleTop = true }
                    },
                )
            }
        }
        if (com.nextbench.app.BuildConfig.DEBUG) {
            composable(
                route = ProductDetailPreviewRoute,
                deepLinks = listOf(navDeepLink { uriPattern = "nextbench://marketplace/preview-product" }),
            ) {
                ProductDetailPreviewScreen()
            }
        }
        composable(
            route = NbRoute.PostDetail.path,
            arguments = listOf(navArgument("postId") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/post/{postId}" },
                navDeepLink { uriPattern = "nextbench://post/{postId}" },
            ),
        ) {
            PostDetailScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenProfile = { userId ->
                    navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true }
                },
                onSignIn = {
                    navController.navigate(NbRoute.Login.path) { launchSingleTop = true }
                },
                onVerify = {
                    navController.navigate(NbRoute.Verification.path) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = NbRoute.ProfileDetail.path,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
        ) {
            GuardedDestination(navController, NbRoute.ProfileDetail.path, authViewModel) {
                PublicProfileScreen(
                    profileKey = it.arguments?.getString("userId").orEmpty(),
                    username = false,
                    viewer = (session as? SessionState.SignedIn)?.userData,
                    onOpenListing = { productId -> navController.navigate(NbRoute.product(productId)) { launchSingleTop = true } },
                    onOpenPost = { postId -> navController.navigate(NbRoute.post(postId)) { launchSingleTop = true } },
                    onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                    onOpenChat = { roomId -> navController.navigate(NbRoute.messages(roomId)) { launchSingleTop = true } },
                    onOpenOwnProfile = { navController.navigateToTab(NbTab.Profile) },
                )
            }
        }
        composable(
            route = NbRoute.UsernameProfile.path,
            arguments = listOf(navArgument("username") { type = NavType.StringType }),
        ) {
            GuardedDestination(navController, NbRoute.UsernameProfile.path, authViewModel) {
                PublicProfileScreen(
                    profileKey = it.arguments?.getString("username").orEmpty(),
                    username = true,
                    viewer = (session as? SessionState.SignedIn)?.userData,
                    onOpenListing = { productId -> navController.navigate(NbRoute.product(productId)) { launchSingleTop = true } },
                    onOpenPost = { postId -> navController.navigate(NbRoute.post(postId)) { launchSingleTop = true } },
                    onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                    onOpenChat = { roomId -> navController.navigate(NbRoute.messages(roomId)) { launchSingleTop = true } },
                    onOpenOwnProfile = { navController.navigateToTab(NbTab.Profile) },
                )
            }
        }
        composable(
            route = NbRoute.Chat.path,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/chat/{roomId}" },
                navDeepLink { uriPattern = "nextbench://chat/{roomId}" },
            ),
        ) { backStackEntry ->
            GuardedDestination(navController, NbRoute.Chat.path, authViewModel) {
                ChatRoomScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenProduct = { productId -> navController.navigate(NbRoute.product(productId)) { launchSingleTop = true } },
                    onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                )
            }
        }
        composable(
            route = NbRoute.MessagesRoom.path,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/messages/{roomId}" },
                navDeepLink { uriPattern = "nextbench://messages/{roomId}" },
            ),
        ) {
            GuardedDestination(navController, NbRoute.MessagesRoom.path, authViewModel) {
                ChatRoomScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenProduct = { productId -> navController.navigate(NbRoute.product(productId)) { launchSingleTop = true } },
                    onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                )
            }
        }
        composable(
            route = NbRoute.MessagesClub.path,
            arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
        ) { GuardedDestination(navController, NbRoute.MessagesClub.path, authViewModel) {
                ClubChatScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onOpenSettings = { clubId -> navController.navigate(NbRoute.clubSettings(clubId)) { launchSingleTop = true } },
                    onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
                )
        } }
        composable(
            route = NbRoute.Club.path,
            arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/club/{clubId}" },
                navDeepLink { uriPattern = "nextbench://club/{clubId}" },
            ),
        ) { GuardedDestination(navController, NbRoute.Club.path, authViewModel) {
            ClubChatScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenSettings = { clubId -> navController.navigate(NbRoute.clubSettings(clubId)) { launchSingleTop = true } },
                onOpenProfile = { userId -> navController.navigate(NbRoute.profile(userId)) { launchSingleTop = true } },
            )
        } }
        composable(
            route = NbRoute.ClubSettings.path,
            arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
        ) {
            GuardedDestination(navController, NbRoute.ClubSettings.path, authViewModel) {
                ClubSettingsScreen(
                    user = (session as? SessionState.SignedIn)?.userData,
                    onBack = { navigateBackOrFeed(navController) },
                    onLeave = {
                        navController.navigate(NbRoute.Clubs.path) {
                            popUpTo(NbRoute.Clubs.path) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable(
            route = NbRoute.ClubJoin.path,
            arguments = listOf(navArgument("inviteCode") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nextbench.in/club/join/{inviteCode}" },
                navDeepLink { uriPattern = "nextbench://club/join/{inviteCode}" },
            ),
        ) { GuardedDestination(navController, NbRoute.ClubJoin.path, authViewModel) {
            ClubJoinScreen(
                user = (session as? SessionState.SignedIn)?.userData,
                onOpenClub = { clubId -> navController.navigate(NbRoute.club(clubId)) { popUpTo(NbRoute.ClubJoin.path) { inclusive = true } } },
            )
        } }
        composable(NbRoute.Invite.path) {
            GuardedDestination(navController, NbRoute.Invite.path, authViewModel) {
                InviteScreen(user = (session as? SessionState.SignedIn)?.userData)
            }
        }
        composable(NbRoute.Login.path) { AuthScreen(authViewModel = authViewModel, initialMode = com.nextbench.app.auth.OtpMode.Login, navController = navController) }
        composable(NbRoute.Signup.path) { AuthScreen(authViewModel = authViewModel, initialMode = com.nextbench.app.auth.OtpMode.Signup, navController = navController) }
        composable(NbRoute.OrgSignup.path) {
            OrganizationSignupScreen(
                onBack = { navigateBackOrFeed(navController) },
                onTerms = { navController.navigate(NbRoute.Terms.path) },
                onPrivacy = { navController.navigate(NbRoute.Privacy.path) },
                onStudentSignup = {
                    navController.navigate(NbRoute.Signup.path) {
                        popUpTo(NbRoute.OrgSignup.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSignIn = {
                    navController.navigate(NbRoute.Login.path) {
                        popUpTo(NbRoute.OrgSignup.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onRegistered = {
                    navController.navigate(NbRoute.Verification.path) {
                        popUpTo(NbRoute.OrgSignup.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(NbRoute.Verification.path) {
            GuardedDestination(navController, NbRoute.Verification.path, authViewModel) {
                val userData = (session as? SessionState.SignedIn)?.userData
                if (userData != null) {
                    VerificationScreen(
                        user = userData,
                        onClose = { leaveVerification(navController, completed = false) },
                        onContinue = { leaveVerification(navController, completed = true) },
                    )
                }
            }
        }
        composable(NbRoute.Auth.path) { AuthScreen(authViewModel = authViewModel, initialMode = com.nextbench.app.auth.OtpMode.Login, navController = navController) }
        composable(NbRoute.Terms.path) { LegalDocumentScreen(LegalDocument.Terms, onBack = { navigateBackOrFeed(navController) }) }
        composable(NbRoute.Privacy.path) { LegalDocumentScreen(LegalDocument.Privacy, onBack = { navigateBackOrFeed(navController) }) }
    }
}

internal fun initialDestination(onboardingComplete: Boolean): String =
    if (onboardingComplete) NbRoute.Feed.path else NbRoute.Onboarding.path

internal fun notificationRoute(link: String): String? {
    val normalized = link.trim().removePrefix("https://nextbench.in/").removePrefix("/")
    return when {
        normalized == "dashboard" || normalized == "community" -> NbRoute.Feed.path
        normalized == "marketplace" -> NbRoute.Marketplace.path
        normalized == "notifications" -> NbRoute.Notifications.path
        normalized.startsWith("product/") -> normalized
        normalized.startsWith("post/") -> normalized
        normalized.startsWith("profile/") -> normalized
        normalized.startsWith("u/") -> normalized
        normalized.startsWith("chat/") -> normalized
        normalized.startsWith("messages/") -> normalized
        normalized.startsWith("club/") -> normalized
        else -> null
    }
}

@Composable
private fun GuardedDestination(
    navController: NavHostController,
    path: String,
    authViewModel: AuthViewModel,
    content: @Composable () -> Unit,
) {
    AuthGate(
        navController = navController,
        requirement = requirementForRoute(path),
        viewModel = authViewModel,
        content = content,
    )
}

/**
 * Tab taps swap the root instead of stacking: Feed is the effective start
 * destination once Splash pops itself inclusively, so popping to the graph's
 * declared start would be a no-op and the back stack would grow on every tap.
 */
fun NavHostController.navigateToTab(tab: NbTab) {
    navigate(tab.route.path) {
        popUpTo(NbRoute.Feed.path) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun navigateBackOrFeed(navController: NavHostController) {
    if (!navController.popBackStack()) {
        navController.navigate(NbRoute.Feed.path) { launchSingleTop = true }
    }
}

private fun leaveVerification(
    navController: NavHostController,
    completed: Boolean,
) {
    val caller = navController.previousBackStackEntry?.destination?.route
    val callerNeedsVerification = requirementForRoute(caller) == com.nextbench.app.auth.RouteRequirement.Verified
    if ((completed || !callerNeedsVerification) && navController.popBackStack()) return

    navController.navigate(NbRoute.Feed.path) {
        if (callerNeedsVerification) popUpTo(caller.orEmpty()) { inclusive = true }
        else popUpTo(NbRoute.Verification.path) { inclusive = true }
        launchSingleTop = true
    }
}
