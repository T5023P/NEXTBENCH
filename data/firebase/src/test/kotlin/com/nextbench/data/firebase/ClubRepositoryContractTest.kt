package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.nextbench.data.model.UserData
import com.nextbench.data.model.FileAttachment
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageType
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubRepositoryContractTest {
    @Test
    fun `join payload uses atomic membership fields`() {
        val payload = clubJoinUpdatePayload("student-1")

        assertTrue(payload.containsKey("memberIds"))
        assertTrue(payload.containsKey("memberCount"))
        assertTrue(payload.containsKey("updatedAt"))
    }

    @Test
    fun `leave payload cannot make member count negative`() {
        val payload = clubLeaveUpdatePayload("student-1", 0)

        assertEquals(0, payload["memberCount"])
        assertTrue(payload.containsKey("coLeadIds"))
    }

    @Test
    fun `leadership transfer payload removes the new lead from co-leads`() {
        val payload = clubLeadershipTransferPayload("member-2")

        assertEquals("member-2", payload["leadId"])
        assertTrue(payload.containsKey("coLeadIds"))
        assertTrue(payload.containsKey("updatedAt"))
    }

    @Test
    fun `role payloads match website field contracts`() {
        val promote = clubRoleUpdatePayload("member-1", RoleUpdate.Promote)
        val demote = clubRoleUpdatePayload("member-1", RoleUpdate.Demote)
        val transfer = clubLeadershipTransferPayload("member-2")
        val remove = clubMemberRemovalPayload("member-3")

        assertTrue(promote.containsKey("coLeadIds"))
        assertTrue(demote.containsKey("coLeadIds"))
        assertEquals("member-2", transfer["leadId"])
        assertTrue(transfer.containsKey("coLeadIds"))
        assertTrue(remove.containsKey("memberIds"))
        assertTrue(remove.containsKey("coLeadIds"))
        assertTrue(remove.containsKey("memberCount"))
    }

    @Test
    fun `invite codes normalize whitespace and case`() {
        assertEquals("ab12Cd34", normalizeClubInviteCode(" ab12 Cd34-extra "))
    }

    @Test
    fun `club message metadata includes sender and unread recipients`() {
        val payload = clubMessageMetadataPayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            lastMessage = "See you at the studio",
            recipientIds = listOf("student-2"),
        )

        assertEquals("student-1", payload["lastSenderId"])
        assertEquals("Maya", payload["lastSenderName"])
        assertEquals("See you at the studio", payload["lastMessage"])
        assertTrue(payload.containsKey("unreadBy"))
        assertFalse(payload.containsKey("deletedBy"))
    }

    @Test
    fun `club text messages preserve structured reply metadata`() {
        val payload = textMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "message-2",
            text = "I can share it",
            replyTo = Message(
                id = "message-1",
                senderId = "student-2",
                senderName = "Noah",
                type = MessageType.File.raw,
                file = com.nextbench.data.model.FileAttachment(name = "Notes.pdf"),
            ),
        )

        assertEquals("message-1", payload["replyToMessageId"])
        assertEquals("Noah", payload["replyToSenderName"])
        assertEquals("Notes.pdf", payload["replyToText"])
        assertEquals(MessageType.File.raw, payload["replyToType"])
    }

    @Test
    fun `club image attachments preserve dimensions caption and reply metadata`() {
        val payload = clubAttachmentPayload(
            sender = UserData(uid = "student-1", name = "Maya", profilePicture = "https://cdn/avatar.jpg"),
            messageId = "message-2",
            uploaded = CloudinaryResult("https://cdn/photo.jpg", "photo", 1200, 900, "jpg"),
            kind = ClubAttachmentKind.Image(width = 1200, height = 900),
            fileName = "photo.jpg",
            fileSize = 240_000L,
            caption = "  Studio references  ",
            replyTo = Message(id = "message-1", senderName = "Noah", text = "Share the moodboard"),
        )

        assertEquals(null, payload["type"])
        assertEquals("Studio references", payload["text"])
        assertEquals("https://cdn/photo.jpg", (payload["image"] as Map<*, *>)["url"])
        assertEquals(1200, (payload["image"] as Map<*, *>)["w"])
        assertEquals("message-1", payload["replyToMessageId"])
        assertEquals("Share the moodboard", payload["replyToText"])
    }

    @Test
    fun `club video attachments preserve playback metadata`() {
        val payload = clubAttachmentPayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "video-1",
            uploaded = CloudinaryResult("https://cdn/clip.mp4", "clip", 1080, 1920, "mp4"),
            kind = ClubAttachmentKind.Video(width = 1080, height = 1920, durationMs = 8_500L),
            fileName = "clip.mp4",
            fileSize = 2_400_000L,
            caption = null,
            replyTo = null,
        )

        val video = payload["video"] as Map<*, *>
        assertEquals(MessageType.Video.raw, payload["type"])
        assertEquals("https://cdn/clip.mp4", video["url"])
        assertEquals(1080, video["w"])
        assertEquals(1920, video["h"])
        assertEquals(8_500L, video["duration"])
    }

    @Test
    fun `club document attachments preserve file metadata and page count`() {
        val payload = clubAttachmentPayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "file-1",
            uploaded = CloudinaryResult("https://cdn/notes.pdf", "notes", 0, 0, "pdf", pages = 12),
            kind = ClubAttachmentKind.File(mime = "application/pdf"),
            fileName = "notes.pdf",
            fileSize = 480_000L,
            caption = "Read before Friday",
            replyTo = Message(
                id = "older-file",
                senderName = "Noah",
                type = MessageType.File.raw,
                file = FileAttachment(name = "brief.pdf"),
            ),
        )

        val file = payload["file"] as Map<*, *>
        assertEquals(MessageType.File.raw, payload["type"])
        assertEquals("https://cdn/notes.pdf", file["url"])
        assertEquals("notes.pdf", file["name"])
        assertEquals(480_000L, file["size"])
        assertEquals("application/pdf", file["mime"])
        assertEquals(12, file["pages"])
        assertEquals("brief.pdf", payload["replyToText"])
    }

    @Test
    fun `club voice payload preserves audio metadata and structured reply`() {
        val payload = voiceMessagePayload(
            sender = UserData(uid = "student-1", name = "Maya"),
            messageId = "voice-1",
            audioUrl = "https://cdn/voice.m4a",
            durationSeconds = 17L,
            fileSize = 128_000L,
            mimeType = "audio/mp4",
            replyTo = Message(id = "older", senderName = "Noah", type = MessageType.Voice.raw, audioUrl = "https://cdn/older.m4a"),
        )

        assertEquals(MessageType.Voice.raw, payload["type"])
        assertEquals("https://cdn/voice.m4a", payload["audioUrl"])
        assertEquals(17L, payload["duration"])
        assertEquals(128_000L, payload["fileSize"])
        assertEquals("audio/mp4", payload["mimeType"])
        assertEquals("older", payload["replyToMessageId"])
        assertEquals("Voice message", payload["replyToText"])
    }

    @Test
    fun `club delete for everyone redacts all supported media`() {
        val payload = clubDeletedForEveryonePayload()

        assertEquals(true, payload["isDeletedForEveryone"])
        assertEquals("This message was deleted", payload["text"])
        assertTrue(payload.containsKey("image"))
        assertTrue(payload.containsKey("video"))
        assertTrue(payload.containsKey("file"))
        assertTrue(payload.containsKey("audioUrl"))
    }

    @Test
    fun `club creation payload matches website schema and creator ownership`() {
        val payload = clubCreationPayload(
            creator = UserData(uid = "student-1", school = "Loreto", city = "Lucknow"),
            name = "  Physics Study Group  ",
            description = "  Weekly problem solving  ",
            type = "private",
            inviteCode = "AbC234xy",
        )

        assertEquals("Physics Study Group", payload["name"])
        assertEquals("Weekly problem solving", payload["description"])
        assertEquals("private", payload["type"])
        assertEquals("student-1", payload["leadId"])
        assertEquals(listOf("student-1"), payload["memberIds"])
        assertEquals(1, payload["memberCount"])
        assertEquals("Loreto", payload["school"])
        assertEquals("Lucknow", payload["city"])
        assertTrue(payload.containsKey("createdAt"))
        assertTrue(payload.containsKey("updatedAt"))
        assertTrue(payload.containsKey("typingUsers"))
    }

    @Test
    fun `generated club invite codes are unambiguous and eight characters`() {
        val code = generateClubInviteCode(kotlin.random.Random(7))

        assertEquals(8, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789" })
    }

    @Test
    fun `a blocked member's club messages are hidden from the viewer`() {
        val messages = listOf(
            clubMessage("m1", sender = "blocked", at = 1_000L),
            clubMessage("m2", sender = "viewer", at = 2_000L),
            clubMessage("m3", sender = "friend", at = 3_000L),
        )

        val visible = visibleClubMessages(messages, blockedSenderIds = setOf("blocked"), viewerId = "viewer")

        assertEquals(listOf("m2", "m3"), visible.map { it.id })
    }

    @Test
    fun `the viewer's own club messages are never hidden by their own blocks`() {
        // A block document is a pair, so a malformed read could surface the viewer's own id. It must
        // never remove the viewer's own messages from the transcript.
        val messages = listOf(clubMessage("m1", sender = "viewer", at = 1_000L))

        val visible = visibleClubMessages(messages, blockedSenderIds = setOf("viewer"), viewerId = "viewer")

        assertEquals(listOf("m1"), visible.map { it.id })
    }

    @Test
    fun `messages the viewer deleted for themselves stay hidden when a block is present`() {
        val messages = listOf(
            clubMessage("m1", sender = "friend", at = 1_000L).copy(deletedFor = listOf("viewer")),
            clubMessage("m2", sender = "friend", at = 2_000L),
        )

        val visible = visibleClubMessages(messages, blockedSenderIds = setOf("blocked"), viewerId = "viewer")

        assertEquals(listOf("m2"), visible.map { it.id })
    }

    @Test
    fun `visible club messages keep chronological order`() {
        val messages = listOf(
            clubMessage("late", sender = "friend", at = 9_000L),
            clubMessage("early", sender = "friend", at = 1_000L),
            clubMessage("unknown-time", sender = "friend", at = null),
        )

        val visible = visibleClubMessages(messages, blockedSenderIds = emptySet(), viewerId = "viewer")

        assertEquals(listOf("early", "late", "unknown-time"), visible.map { it.id })
    }

    private fun clubMessage(id: String, sender: String, at: Long?): Message = Message(
        id = id,
        senderId = sender,
        senderName = sender,
        createdAt = at?.let { Timestamp(Date(it)) },
    )
}
