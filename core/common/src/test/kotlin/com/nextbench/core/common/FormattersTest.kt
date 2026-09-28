package com.nextbench.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formatRelativeTime returns just now for under 60s`() {
        val now = System.currentTimeMillis()
        assertEquals("just now", formatRelativeTime(now - 30_000))
    }

    @Test
    fun `formatRelativeTime returns minutes`() {
        val now = System.currentTimeMillis()
        assertEquals("5m", formatRelativeTime(now - 5 * 60_000))
    }

    @Test
    fun `formatRelativeTime returns hours`() {
        val now = System.currentTimeMillis()
        assertEquals("3h", formatRelativeTime(now - 3 * 3_600_000))
    }

    @Test
    fun `formatRelativeTime returns days`() {
        val now = System.currentTimeMillis()
        assertEquals("2d", formatRelativeTime(now - 2 * 86_400_000L))
    }

    @Test
    fun `formatRupees formats correctly`() {
        assertEquals("₹1,299", formatRupees(1299))
        assertEquals("₹10,000", formatRupees(10000))
        assertEquals("₹0", formatRupees(0))
    }
}
