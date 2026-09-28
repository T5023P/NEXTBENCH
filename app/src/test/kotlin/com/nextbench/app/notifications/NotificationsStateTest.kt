package com.nextbench.app.notifications

import com.nextbench.app.toAppDeepLink
import com.nextbench.app.navigation.notificationRoute
import com.nextbench.data.model.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsStateTest {
    @Test
    fun `filters and unread counts follow notification categories`() {
        val notifications = listOf(
            Notification(id = "deal", type = "item_reserved", read = false),
            Notification(id = "social", type = "mention", read = false),
            Notification(id = "system", type = "user_approved", read = true),
        )
        val state = NotificationsUiState(notifications = notifications, filter = NotificationFilter.Deals)

        assertEquals(listOf("deal"), state.visibleNotifications.map(Notification::id))
        assertEquals(1, state.unreadCount)
        assertEquals(2, state.unreadTotal)
        assertEquals(1, state.counts[NotificationFilter.Social])
    }

    @Test
    fun `web links become native routes and push deep links`() {
        assertEquals("product/product-1", notificationRoute("/product/product-1"))
        assertEquals("community", notificationRoute("https://nextbench.in/dashboard"))
        assertEquals("club/club-1", notificationRoute("/club/club-1"))
        assertEquals("nextbench://chat/room-1", "/chat/room-1".toAppDeepLink())
        assertEquals("nextbench://product/product-1", "https://nextbench.in/product/product-1".toAppDeepLink())
        assertNull(" ".toAppDeepLink())
    }

    @Test
    fun `notification errors remain actionable`() {
        assertTrue(IllegalStateException("network unavailable").notificationMessage().contains("internet"))
        assertTrue(IllegalStateException("Firebase is not configured").notificationMessage().contains("google-services.json"))
    }
}
