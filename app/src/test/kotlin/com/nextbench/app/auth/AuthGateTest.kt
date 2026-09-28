package com.nextbench.app.auth

import com.nextbench.app.navigation.NbRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthGateTest {

    @Test
    fun routeRequirementsMatchWebsiteAccessPolicy() {
        assertEquals(RouteRequirement.Public, requirementForRoute(NbRoute.Feed.path))
        assertEquals(RouteRequirement.Public, requirementForRoute(NbRoute.Marketplace.path))
        assertEquals(RouteRequirement.Public, requirementForRoute(NbRoute.Search.path))
        assertEquals(RouteRequirement.SignedIn, requirementForRoute(NbRoute.ProductDetail.path))
        assertEquals(RouteRequirement.SignedIn, requirementForRoute(NbRoute.Profile.path))
        assertEquals(RouteRequirement.Verified, requirementForRoute(NbRoute.Create.path))
        assertEquals(RouteRequirement.Verified, requirementForRoute(NbRoute.EditItem.path))
        assertEquals(RouteRequirement.Verified, requirementForRoute(NbRoute.MessagesRoom.path))
        assertEquals(RouteRequirement.Verified, requirementForRoute(NbRoute.Share.path))
    }

    @Test
    fun signedOutUsersAreRedirectedOnlyFromProtectedRoutes() {
        assertEquals(
            GateDecision.Allow,
            gateDecision(RouteRequirement.Public, GateSession.SignedOut),
        )
        assertEquals(
            GateDecision.Redirect(NbRoute.Login.path),
            gateDecision(RouteRequirement.SignedIn, GateSession.SignedOut),
        )
    }

    @Test
    fun verifiedRequirementsUseProfileClaims() {
        val member = GateSession.SignedIn(verified = false)
        val verified = GateSession.SignedIn(verified = true)

        assertEquals(
            GateDecision.Redirect(NbRoute.Verification.path),
            gateDecision(RouteRequirement.Verified, member),
        )
        assertEquals(GateDecision.Allow, gateDecision(RouteRequirement.Verified, verified))
    }
}
