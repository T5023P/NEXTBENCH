package com.nextbench.app.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nextbench.app.navigation.NbRoute
import com.nextbench.data.firebase.SessionState
import com.nextbench.data.model.VerificationStatus

enum class RouteRequirement { Public, SignedIn, Verified }

internal sealed interface GateDecision {
    data object Allow : GateDecision
    data object Wait : GateDecision
    data class Redirect(val path: String) : GateDecision
}

internal sealed interface GateSession {
    data object Loading : GateSession
    data object SignedOut : GateSession
    data class SignedIn(val verified: Boolean) : GateSession
}

fun requirementForRoute(path: String?): RouteRequirement = when {
    path == null -> RouteRequirement.Public
    path == NbRoute.Profile.path || path == NbRoute.Wishlist.path || path == NbRoute.Notifications.path || path == NbRoute.Clubs.path ||
        path == NbRoute.Invite.path || path == NbRoute.Verification.path || path.startsWith("product/") ||
        path.startsWith("profile/") || path.startsWith("u/") || path.startsWith("club/") -> RouteRequirement.SignedIn
    path == NbRoute.Create.path || path == NbRoute.Sell.path || path == NbRoute.Messages.path || path == NbRoute.Share.path ||
        path.startsWith("edit-item/") || path.startsWith("messages/") ||
        path.startsWith("chat/") -> RouteRequirement.Verified
    else -> RouteRequirement.Public
}

internal fun gateDecision(
    requirement: RouteRequirement,
    session: GateSession,
): GateDecision {
    val signedIn = session as? GateSession.SignedIn
    return when {
        requirement == RouteRequirement.Public -> GateDecision.Allow
        session == GateSession.Loading -> GateDecision.Wait
        session == GateSession.SignedOut -> GateDecision.Redirect(NbRoute.Login.path)
        requirement == RouteRequirement.Verified && signedIn?.verified != true ->
            GateDecision.Redirect(NbRoute.Verification.path)
        else -> GateDecision.Allow
    }
}

private fun SessionState.toGateSession(): GateSession = when (this) {
    SessionState.Loading -> GateSession.Loading
    SessionState.SignedOut -> GateSession.SignedOut
    is SessionState.SignedIn -> GateSession.SignedIn(
        verified = userData?.verified == true,
    )
}

@Composable
fun AuthGate(
    navController: NavHostController,
    requirement: RouteRequirement,
    viewModel: AuthViewModel,
    content: @Composable () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val decision = remember(requirement, session) {
        gateDecision(requirement, session.toGateSession())
    }

    LaunchedEffect(decision) {
        (decision as? GateDecision.Redirect)?.path?.let { destination ->
            navController.navigate(destination) {
                launchSingleTop = true
            }
        }
    }

    if (decision == GateDecision.Allow) content()
}

fun verificationStatus(userData: com.nextbench.data.model.UserData?): VerificationStatus =
    VerificationStatus.from(userData?.verificationStatus)
