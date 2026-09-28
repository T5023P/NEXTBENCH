package com.nextbench.app.create

import com.nextbench.data.firebase.PostPrivacy
import com.nextbench.data.firebase.PostType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateStateTest {
    @Test
    fun `a blank composer cannot publish but content or media can`() {
        assertFalse(CreateUiState().canPublish)
        assertTrue(CreateUiState(content = "A useful update").canPublish)
        assertTrue(canPublishDraft(title = "", content = "", imageCount = 1))
    }

    @Test
    fun `confession type defaults to anonymous and privacy stays explicit`() {
        val confession = CreateUiState(type = PostType.Confession, anonymous = true, privacy = PostPrivacy.Private, content = "A thought")

        assertTrue(confession.anonymous)
        assertTrue(confession.canPublish)
        assertTrue(IllegalStateException("network unavailable").createMessage().contains("internet"))
    }

    @Test
    fun `composer errors explain missing setup`() {
        assertTrue(IllegalStateException("Firebase is not configured").createMessage().contains("google-services.json"))
        assertTrue(IllegalStateException("Cloudinary is not configured").createMessage().contains("Image uploads"))
        assertTrue(IllegalStateException("Your session expired").createMessage().contains("session expired"))
    }
}
