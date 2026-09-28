package com.nextbench.app

import com.nextbench.app.navigation.NbRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NbAppShellTest {

    @Test
    fun unresolvedAndSplashRoutesRenderWithoutChrome() {
        listOf(null, NbRoute.Splash.path).forEach { path ->
            val state = resolveChrome(path)

            assertFalse(state.showTopBar)
            assertFalse(state.showBottomBar)
            assertFalse(state.canNavigateBack)
        }
    }

    @Test
    fun topLevelRoutesRenderBothBarsWithoutCustomBackHandling() {
        NbRoute.topLevel.forEach { route ->
            val state = resolveChrome(route.path)

            assertTrue(state.showTopBar)
            assertTrue(state.showBottomBar)
            assertFalse(state.canNavigateBack)
        }
    }

    @Test
    fun detailRoutesRenderTopBarAndBackHandling() {
        val state = resolveChrome(NbRoute.ProductDetail.path)

        assertTrue(state.showTopBar)
        assertFalse(state.showBottomBar)
        assertTrue(state.canNavigateBack)
    }

    @Test
    fun authRoutesRenderWithoutAppChrome() {
        NbRoute.chromeFree.forEach { route ->
            val state = resolveChrome(route.path)

            assertFalse(state.showTopBar)
            assertFalse(state.showBottomBar)
            assertFalse(state.canNavigateBack)
        }
    }

    @Test
    fun `pending shares wait on auth routes and reopen after authentication`() {
        assertFalse(shouldOpenShareRoute(NbRoute.Login.path))
        assertFalse(shouldOpenShareRoute(NbRoute.Verification.path))
        assertFalse(shouldOpenShareRoute(NbRoute.Share.path))
        assertTrue(shouldOpenShareRoute(NbRoute.Feed.path))
    }
}
