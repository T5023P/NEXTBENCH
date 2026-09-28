package com.nextbench.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.nextbench.core.designsystem.NbIcons

/**
 * String-backed routes. Navigation-Compose type-safe routes need
 * kotlinx-serialization, which this project does not depend on; paths keep the
 * graph declarative without pulling in a serialization runtime.
 */
sealed class NbRoute(val path: String) {

    data object Splash : NbRoute("splash")
    data object Onboarding : NbRoute("onboarding")

    data object Feed : NbRoute("community")
    data object Marketplace : NbRoute("marketplace")
    data object Create : NbRoute("create")
    data object Messages : NbRoute("messages")
    data object Profile : NbRoute("profile")
    data object Clubs : NbRoute("clubs")

    data object Search : NbRoute("search")
    data object Notifications : NbRoute("notifications")
    data object Wishlist : NbRoute("wishlist")
    data object Sell : NbRoute("sell")
    data object EditItem : NbRoute("edit-item/{productId}")
    data object ProductDetail : NbRoute("product/{productId}")
    data object PostDetail : NbRoute("post/{postId}")
    data object ProfileDetail : NbRoute("profile/{userId}")
    data object UsernameProfile : NbRoute("u/{username}")
    data object Chat : NbRoute("chat/{roomId}")
    data object MessagesRoom : NbRoute("messages/{roomId}")
    data object MessagesClub : NbRoute("messages/club/{clubId}")
    data object Club : NbRoute("club/{clubId}")
    data object ClubSettings : NbRoute("club/{clubId}/settings")
    data object ClubJoin : NbRoute("club/join/{inviteCode}")
    data object Invite : NbRoute("invite")
    data object Share : NbRoute("share")

    data object Login : NbRoute("login")
    data object Signup : NbRoute("signup")
    data object OrgSignup : NbRoute("org-signup")
    data object Verification : NbRoute("verification")
    data object Auth : NbRoute("auth") // Legacy entry point retained for deep links.

    data object Terms : NbRoute("terms")
    data object Privacy : NbRoute("privacy")

    companion object {
        /** Routes that render the bottom bar. */
        val topLevel = listOf(Feed, Search, Create, Messages, Profile)

        /** Routes that intentionally render without the authenticated app chrome. */
        val chromeFree = setOf(
            Splash,
            Onboarding,
            Login,
            Signup,
            OrgSignup,
            Verification,
            Auth,
            Terms,
            Privacy,
        )

        fun tabFor(path: String?): NbTab? = NbTab.entries.firstOrNull { it.route.path == path }

        fun isTopLevel(path: String?): Boolean = topLevel.any { it.path == path }

        fun product(id: String) = "product/${id.encodeRouteSegment()}"
        fun editItem(id: String) = "edit-item/${id.encodeRouteSegment()}"
        fun post(id: String) = "post/${id.encodeRouteSegment()}"
        fun profile(userId: String) = "profile/${userId.encodeRouteSegment()}"
        fun username(username: String) = "u/${username.encodeRouteSegment()}"
        fun chat(roomId: String) = "chat/${roomId.encodeRouteSegment()}"
        fun messages(roomId: String) = "messages/${roomId.encodeRouteSegment()}"
        fun clubMessages(clubId: String) = "messages/club/${clubId.encodeRouteSegment()}"
    fun club(clubId: String) = "club/${clubId.encodeRouteSegment()}"
        fun clubSettings(clubId: String) = "club/${clubId.encodeRouteSegment()}/settings"
        fun clubJoin(inviteCode: String) = "club/join/${inviteCode.encodeRouteSegment()}"

        private fun String.encodeRouteSegment(): String =
            java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
    }
}

/** A bottom-bar destination: route plus the chrome the bar needs to draw it. */
enum class NbTab(
    val route: NbRoute,
    val label: String,
    val icon: ImageVector,
    val isAccent: Boolean = false,
) {
    Feed(NbRoute.Feed, "Feed", NbIcons.Home),
    Search(NbRoute.Search, "Search", NbIcons.Search),
    Create(NbRoute.Create, "Create", NbIcons.Plus, isAccent = true),
    Messages(NbRoute.Messages, "Chats", NbIcons.Messages),
    Profile(NbRoute.Profile, "You", NbIcons.Profile),
}
