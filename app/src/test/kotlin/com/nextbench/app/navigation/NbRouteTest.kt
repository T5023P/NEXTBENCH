package com.nextbench.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NbRouteTest {

    @Test
    fun websiteRouteFamiliesHaveStableAndroidPaths() {
        assertEquals("onboarding", NbRoute.Onboarding.path)
        assertEquals("community", NbRoute.Feed.path)
        assertEquals("product/{productId}", NbRoute.ProductDetail.path)
        assertEquals("post/{postId}", NbRoute.PostDetail.path)
        assertEquals("edit-item/{productId}", NbRoute.EditItem.path)
        assertEquals("messages/club/{clubId}", NbRoute.MessagesClub.path)
        assertEquals("club/{clubId}/settings", NbRoute.ClubSettings.path)
        assertEquals("u/{username}", NbRoute.UsernameProfile.path)
    }

    @Test
    fun firstLaunchRoutesToOnboardingBeforeFeed() {
        assertEquals(NbRoute.Onboarding.path, initialDestination(onboardingComplete = false))
        assertEquals(NbRoute.Feed.path, initialDestination(onboardingComplete = true))
    }

    @Test
    fun routeBuildersEncodeIndividualPathSegments() {
        assertEquals("product/book+%26+notes", NbRoute.product("book & notes"))
        assertEquals("edit-item/book+%26+notes", NbRoute.editItem("book & notes"))
        assertEquals("post/campus+%26+notes", NbRoute.post("campus & notes"))
        assertEquals("u/maryam%2Fdesigns", NbRoute.username("maryam/designs"))
    }

    @Test
    fun onlyTabsAreTopLevel() {
        assertTrue(NbRoute.isTopLevel(NbRoute.Search.path))
        assertFalse(NbRoute.isTopLevel(NbRoute.Marketplace.path))
        assertFalse(NbRoute.isTopLevel(NbRoute.ProductDetail.path))
        assertEquals(NbTab.Profile, NbRoute.tabFor(NbRoute.Profile.path))
        assertEquals(NbTab.Search, NbRoute.tabFor(NbRoute.Search.path))
    }
}
