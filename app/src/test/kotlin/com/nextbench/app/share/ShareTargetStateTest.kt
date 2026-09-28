package com.nextbench.app.share

import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.ForwardTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTargetStateTest {
    @Test
    fun `shared text combines distinct subject and body`() {
        assertEquals("Useful notes\n\nhttps://nextbench.in/post/one", combinedSharedText("Useful notes", "https://nextbench.in/post/one"))
        assertEquals("Same text", combinedSharedText("Same text", "Same text"))
    }

    @Test
    fun `shared streams merge sources without duplicates`() {
        assertEquals(listOf("first", "second", "third"), distinctShareItems(listOf("first", "second"), listOf("first"), listOf("second", "third")))
    }

    @Test
    fun `share requires content and a selected destination`() {
        val target = ForwardTarget("room", ForwardTargetType.Direct, "Maya")

        assertFalse(ShareTargetUiState(text = "Hello").canSend)
        assertFalse(ShareTargetUiState(selectedTarget = target).canSend)
        assertTrue(ShareTargetUiState(text = "Hello", selectedTarget = target).canSend)
        assertFalse(ShareTargetUiState(text = "Hello", selectedTarget = target, isSending = true).canSend)
    }
}
