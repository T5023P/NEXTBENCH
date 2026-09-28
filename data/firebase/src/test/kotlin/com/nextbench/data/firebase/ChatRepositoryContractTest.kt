package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.nextbench.data.model.ChatRoom
import com.nextbench.data.model.ClubType
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VideoAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ChatRepositoryContractTest {

    @Test
    fun `image payloads follow the website schema while rich attachments keep explicit types`() {
        assertEquals(null, explicitAttachmentMessageType(MessageType.Image.raw))
        assertEquals(MessageType.Video.raw, explicitAttachmentMessageType(MessageType.Video.raw))
        assertEquals(MessageType.File.raw, explicitAttachmentMessageType(MessageType.File.raw))
    }

    @Test
    fun `club documents match the shared web schema`() {
        val typingAt = Timestamp(Date(1_725_000_000_000L))
        val club = mapOf<String, Any?>(
            "name" to "Design Circle",
            "avatar" to "https://cdn/club.jpg",
            "school" to "NIT Jaipur",
            "city" to "Jaipur",
            "type" to "private",
            "leadId" to "lead-1",
            "memberIds" to listOf("lead-1", "student-2"),
            "memberCount" to 2L,
            "typingUsers" to mapOf("student-2" to typingAt),
            "settings" to mapOf("slowMode" to 30L, "onlyLeadsCanPost" to true),
        ).toClub("club-1")

        assertEquals("club-1", club.id)
        assertEquals("https://cdn/club.jpg", club.avatar)
        assertEquals("lead-1", club.leadId)
        assertEquals(2, club.memberCount)
        assertEquals(30, club.settings.slowMode)
        assertEquals(true, club.settings.onlyLeadsCanPost)
        assertEquals(ClubType.Private.raw, club.type)
        assertEquals(typingAt, club.typingUsers["student-2"])
    }

    @Test
    fun `legacy club fields remain readable without migration`() {
        val club = mapOf<String, Any?>(
            "imageUrl" to "https://cdn/legacy.jpg",
            "leadIds" to listOf("lead-1"),
            "memberIds" to listOf("lead-1"),
            "settings" to mapOf("slowMode" to true),
        ).toClub("legacy")

        assertEquals("https://cdn/legacy.jpg", club.avatar)
        assertEquals("lead-1", club.leadId)
        assertEquals(1, club.memberCount)
        assertEquals(0, club.settings.slowMode)
    }

    @Test
    fun `text messages match the shared web room contract`() {
        val payload = textMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya", profilePicture = "https://cdn/avatar.jpg"),
            messageId = "message-1",
            text = "Is this still available?",
        )

        assertEquals("student-1", payload["senderId"])
        assertEquals("Maya", payload["senderName"])
        assertEquals("https://cdn/avatar.jpg", payload["senderAvatar"])
        assertEquals("Is this still available?", payload["text"])
        assertEquals("text", payload["type"])
        assertEquals("android_message-1", payload["clientMessageId"])
        assertEquals("sent", payload["status"])
        assertTrue(payload.containsKey("createdAt"))
    }

    @Test
    fun `text payload preserves website reply metadata`() {
        val reply = Message(
            id = "message-older",
            senderId = "student-2",
            senderName = "Noah",
            type = MessageType.Image.raw,
            image = "https://cdn/photo.jpg",
        )
        val payload = textMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "message-new",
            text = "Looks great",
            replyTo = reply,
        )

        assertEquals("message-older", payload["replyToMessageId"])
        assertEquals("Photo", payload["replyToText"])
        assertEquals("image", payload["replyToType"])
        assertEquals("Noah", payload["replyToSenderName"])
    }

    @Test
    fun `text payload preserves stable client id for idempotent retries`() {
        val payload = textMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "android-stable-id",
            text = "Sent once",
            clientMessageId = "android-stable-id",
        )

        assertEquals("android-stable-id", payload["clientMessageId"])
        assertTrue(isValidMessageDocumentId("android-stable-id"))
        assertFalse(isValidMessageDocumentId("android/invalid"))
        assertFalse(isValidMessageDocumentId("a".repeat(129)))
    }

    @Test
    fun `voice payload matches the shared web contract`() {
        val payload = voiceMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "voice-1",
            audioUrl = "https://storage/voice.m4a",
            durationSeconds = 42L,
            fileSize = 320_000L,
            mimeType = "audio/mp4",
            replyTo = Message(id = "older", senderName = "Noah", text = "Listen to this"),
        )

        assertEquals("voice", payload["type"])
        assertEquals("https://storage/voice.m4a", payload["audioUrl"])
        assertEquals(42L, payload["duration"])
        assertEquals(320_000L, payload["fileSize"])
        assertEquals("audio/mp4", payload["mimeType"])
        assertEquals("older", payload["replyToMessageId"])
        assertEquals("android_voice-1", payload["clientMessageId"])
    }

    @Test
    fun `voice validation enforces duration size and audio format`() {
        assertTrue(voiceMessageValidationError(0L, 1L, "audio/mp4")!!.contains("too short"))
        assertTrue(voiceMessageValidationError(301L, 1L, "audio/mp4")!!.contains("5 minutes"))
        assertTrue(voiceMessageValidationError(1L, 10L * 1024L * 1024L + 1L, "audio/mp4")!!.contains("10 MB"))
        assertTrue(voiceMessageValidationError(1L, 1L, "video/mp4")!!.contains("format"))
        assertEquals(null, voiceMessageValidationError(300L, 10L * 1024L * 1024L, "audio/mp4"))
    }

    @Test
    fun `voice messages preserve playback metadata when read`() {
        val message = requireNotNull(mapOf<String, Any?>(
            "senderId" to "student-1",
            "type" to "voice",
            "audioUrl" to "https://storage/voice.m4a",
            "duration" to 17L,
            "fileSize" to 128_000L,
            "mimeType" to "audio/mp4",
        ).toChatMessage("voice-1"))

        assertEquals(MessageType.Voice.raw, message.type)
        assertEquals(17L, message.duration)
        assertEquals(128_000L, message.fileSize)
        assertEquals("audio/mp4", message.mimeType)
    }

    @Test
    fun `forwarded payload preserves original author and rich content`() {
        val source = Message(
            id = "source",
            senderId = "original-author",
            senderName = "Noah",
            text = "Watch this",
            type = MessageType.Video.raw,
            video = VideoAttachment(url = "https://cdn/video.mp4", poster = "https://cdn/poster.jpg", w = 720, h = 1280, duration = 8_000L),
        )
        val payload = forwardedMessagePayload(
            sender = UserData(uid = "forwarder", name = "Maya"),
            source = source,
            messageId = "forwarded",
        )

        assertEquals("forwarder", payload["senderId"])
        assertEquals("video", payload["type"])
        assertEquals("Watch this", payload["text"])
        assertEquals("https://cdn/video.mp4", (payload["video"] as Map<*, *>)["url"])
        assertEquals("original-author", (payload["forwardedFrom"] as Map<*, *>)["senderId"])
        assertEquals("Noah", (payload["forwardedFrom"] as Map<*, *>)["senderName"])
    }

    @Test
    fun `forwarded messages retain structured attribution when read`() {
        val message = mapOf<String, Any?>(
            "senderId" to "forwarder",
            "text" to "Useful note",
            "forwardedFrom" to mapOf("senderId" to "author", "senderName" to "Noah"),
        ).toChatMessage("forwarded")

        assertEquals("author", message?.forwardedFrom?.senderId)
        assertEquals("Noah", message?.forwardedFrom?.senderName)
    }

    @Test
    fun `bulk delete payload removes all attachment metadata`() {
        val payload = deletedForEveryonePayload()

        assertEquals(true, payload["isDeletedForEveryone"])
        assertEquals("This message was deleted", payload["text"])
        assertTrue(payload.containsKey("image"))
        assertTrue(payload.containsKey("video"))
        assertTrue(payload.containsKey("file"))
        assertTrue(payload.containsKey("audioUrl"))
        assertTrue(payload.containsKey("mimeType"))
    }

    @Test
    fun `room metadata identifies recipients and restores sender visibility`() {
        val payload = roomMetadataPayload(
            senderId = "buyer-1",
            lastMessage = "Can we meet tomorrow?",
            recipientIds = listOf("seller-1"),
        )

        assertEquals("buyer-1", payload["lastSenderId"])
        assertEquals("Can we meet tomorrow?", payload["lastMessage"])
        assertTrue(payload.containsKey("updatedAt"))
        assertTrue(payload.containsKey("unreadBy"))
        assertTrue(payload.containsKey("deletedBy"))
    }

    @Test
    fun `inbox item visibility follows unread delete and archive rules`() {
        val deleted = ChatRoomListItem(
            room = ChatRoom(id = "room", deletedBy = listOf("viewer")),
            otherUser = null,
            viewerId = "viewer",
        )
        val revived = deleted.copy(
            room = deleted.room.copy(unreadBy = listOf("viewer")),
        )
        val archived = deleted.copy(
            room = deleted.room.copy(deletedBy = emptyList(), archivedBy = listOf("viewer")),
        )

        assertTrue(deleted.deleted)
        assertFalse(revived.deleted)
        assertTrue(revived.unread)
        assertTrue(archived.archived)
    }

    @Test
    fun `muted unread activity does not hide a deleted conversation`() {
        val item = ChatRoomListItem(
            room = ChatRoom(
                id = "room",
                deletedBy = listOf("viewer"),
                unreadBy = listOf("viewer"),
                mutedBy = listOf("viewer"),
            ),
            otherUser = null,
            viewerId = "viewer",
        )

        assertTrue(item.hasUnreadActivity)
        assertFalse(item.unread)
        assertFalse(item.deleted)
        assertTrue(item.muted)
    }

    @Test
    fun `inbox bulk operations preserve per-user room flags`() {
        val uid = "viewer"

        val markRead = inboxBulkPayload(uid, InboxBulkOperation.MarkRead)
        val delete = inboxBulkPayload(uid, InboxBulkOperation.Delete)

        assertEquals(setOf("unreadBy", "deletedBy"), markRead.keys)
        assertEquals(setOf("unreadBy", "deletedBy"), delete.keys)
        assertEquals(setOf("pinnedBy"), inboxBulkPayload(uid, InboxBulkOperation.Pin).keys)
        assertEquals(setOf("pinnedBy"), inboxBulkPayload(uid, InboxBulkOperation.Unpin).keys)
        assertEquals(setOf("mutedBy"), inboxBulkPayload(uid, InboxBulkOperation.Mute).keys)
        assertEquals(setOf("mutedBy"), inboxBulkPayload(uid, InboxBulkOperation.Unmute).keys)
        assertEquals(setOf("archivedBy"), inboxBulkPayload(uid, InboxBulkOperation.Archive).keys)
        assertEquals(setOf("archivedBy"), inboxBulkPayload(uid, InboxBulkOperation.Restore).keys)
        assertEquals(setOf("unreadBy"), inboxBulkPayload(uid, InboxBulkOperation.MarkUnread).keys)
    }

    @Test
    fun `block state is symmetric for send policy`() {
        assertTrue(ChatBlockState(blockedByViewer = true).isBlocked)
        assertTrue(ChatBlockState(blockedViewer = true).isBlocked)
        assertFalse(ChatBlockState().isBlocked)
    }

    @Test
    fun `web image objects and legacy reply ids map without migration`() {
        val message = mapOf<String, Any?>(
            "senderId" to "seller-1",
            "image" to mapOf("url" to "https://cdn/photo.jpg", "w" to 800L, "h" to 600L),
            "replyToId" to "older-message",
            "replyToText" to "Still available?",
        ).toChatMessage("message-1")

        assertEquals("image", message?.type)
        assertEquals("https://cdn/photo.jpg", message?.image)
        assertEquals("older-message", message?.replyToMessageId)
        assertEquals("Still available?", message?.replyToText)
    }

    @Test
    fun `file messages keep attachment metadata and numeric coercion`() {
        val message = mapOf<String, Any?>(
            "senderId" to "student-1",
            "type" to "file",
            "file" to mapOf(
                "url" to "https://cdn/notes.pdf",
                "name" to "Notes.pdf",
                "size" to 4096.0,
                "mime" to "application/pdf",
                "pages" to 12L,
            ),
        ).toChatMessage("message-2")

        assertEquals("Notes.pdf", message?.file?.name)
        assertEquals(4096L, message?.file?.size)
        assertEquals(12, message?.file?.pages)
    }

    @Test
    fun `attachment display names remove paths cap length and preserve useful fallback`() {
        assertEquals("Notes.pdf", normalizedAttachmentDisplayName("/cache/Notes.pdf"))
        assertEquals("Slides.pptx", normalizedAttachmentDisplayName("C:\\temp\\Slides.pptx"))
        assertEquals(180, normalizedAttachmentDisplayName("x".repeat(240)).length)
        assertEquals("Document", normalizedAttachmentDisplayName("   "))
    }
}
