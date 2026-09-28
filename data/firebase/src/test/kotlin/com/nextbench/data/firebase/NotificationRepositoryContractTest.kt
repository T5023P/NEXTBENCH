package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRepositoryContractTest {
    @Test
    fun `notification mapping preserves the cross-platform contract`() {
        val notification = mapOf<String, Any?>(
            "userId" to "student-1",
            "type" to "item_reserved",
            "title" to "Item reserved",
            "message" to "Maya reserved your listing",
            "link" to "/product/product-1",
            "postId" to null,
            "read" to true,
            "createdAt" to Timestamp(Date(1_700_000_000_000L)),
        ).toNextBenchNotification("notification-1")

        assertEquals("notification-1", notification.id)
        assertEquals("student-1", notification.userId)
        assertEquals("item_reserved", notification.type)
        assertEquals("/product/product-1", notification.link)
        assertNull(notification.postId)
        assertEquals(1_700_000_000_000L, notification.createdAt?.toDate()?.time)
    }

    @Test
    fun `notification mapping defaults missing read state to unread`() {
        val notification = mapOf<String, Any?>().toNextBenchNotification("notification-1")

        assertFalse(notification.read)
        assertEquals("", notification.title)
    }
}
