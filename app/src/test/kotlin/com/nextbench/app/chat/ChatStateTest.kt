package com.nextbench.app.chat

import com.google.firebase.Timestamp
import com.nextbench.data.firebase.ChatBlockState
import com.nextbench.data.firebase.ChatRoomDetail
import com.nextbench.data.firebase.ChatRoomListItem
import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.ForwardTargetType
import com.nextbench.data.model.ChatRoom
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageStatus
import com.nextbench.data.model.UserData
import java.time.ZoneId
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTest {

    @Test
    fun `inbox filtering excludes deleted rooms and matches room context`() {
        val viewer = "viewer"
        val active = roomItem(
            id = "active",
            title = "Physics notes",
            message = "Still available",
            other = UserData(uid = "seller", name = "Maya"),
        )
        val deleted = roomItem(id = "deleted", title = "Old", deletedBy = listOf(viewer))
        val archived = roomItem(id = "archived", title = "Desk lamp", archivedBy = listOf(viewer))

        val state = MessagesUiState(
            rooms = listOf(active, deleted, archived),
            viewerId = viewer,
            query = "physics",
        )

        assertEquals(listOf("active"), state.visibleRooms.map { it.room.id })
        assertEquals(1, state.archivedCount)
    }

    @Test
    fun `archived filter only exposes archived rooms`() {
        val viewer = "viewer"
        val state = MessagesUiState(
            rooms = listOf(
                roomItem(id = "active"),
                roomItem(id = "archived", archivedBy = listOf(viewer)),
            ),
            viewerId = viewer,
            showArchived = true,
        )

        assertEquals(listOf("archived"), state.visibleRooms.map { it.room.id })
    }

    @Test
    fun `inbox selection derives rooms in source order and mixed flags choose enable actions`() {
        val state = MessagesUiState(
            rooms = listOf(
                roomItem(id = "first", pinnedBy = listOf("viewer"), mutedBy = listOf("viewer")),
                roomItem(id = "second", unreadBy = listOf("viewer")),
            ),
            viewerId = "viewer",
            selectedRoomIds = setOf("second", "missing", "first"),
        )

        assertTrue(state.selectionMode)
        assertEquals(listOf("first", "second"), state.selectedRooms.map { it.room.id })
        assertFalse(state.allSelectedPinned)
        assertFalse(state.allSelectedRead)
        assertFalse(state.allSelectedMuted)
    }

    @Test
    fun `inbox selection detects consistent room flags`() {
        val state = MessagesUiState(
            rooms = listOf(
                roomItem(id = "first", pinnedBy = listOf("viewer"), mutedBy = listOf("viewer")),
                roomItem(id = "second", pinnedBy = listOf("viewer"), mutedBy = listOf("viewer")),
            ),
            viewerId = "viewer",
            selectedRoomIds = setOf("first", "second"),
        )

        assertTrue(state.allSelectedPinned)
        assertTrue(state.allSelectedRead)
        assertTrue(state.allSelectedMuted)
    }

    @Test
    fun `pending request can only be answered by recipient`() {
        val room = ChatRoomDetail(
            room = ChatRoom(
                id = "room",
                participants = listOf("requester", "recipient"),
                status = "pending",
                requestedBy = "requester",
            ),
            otherUser = UserData(uid = "requester", name = "Maya"),
        )
        val state = ChatRoomUiState(room = room, blockState = ChatBlockState())

        assertTrue(state.canRespondToRequest("recipient"))
        assertFalse(state.canRespondToRequest("requester"))
        assertFalse(state.canRespondToRequest(null))
    }

    @Test
    fun `chat failures become actionable user copy`() {
        assertTrue(IllegalStateException("network unavailable").chatMessage().contains("internet"))
        assertTrue(IllegalStateException("Cannot message this user.").chatMessage().contains("cannot message"))
        assertTrue(IllegalStateException("Firebase is not configured").chatMessage().contains("google-services.json"))
    }

    @Test
    fun `typing timestamp expires after five seconds`() {
        val now = 10_000L

        assertTrue(isUserTyping(timestampMillis = now - 4_999L, nowMillis = now))
        assertFalse(isUserTyping(timestampMillis = now - 5_000L, nowMillis = now))
        assertFalse(isUserTyping(timestampMillis = null, nowMillis = now))
    }

    @Test
    fun `online flag requires a fresh heartbeat`() {
        val now = 200_000L

        assertTrue(isUserOnline(online = true, lastSeenMillis = now - 89_999L, nowMillis = now))
        assertFalse(isUserOnline(online = true, lastSeenMillis = now - 90_000L, nowMillis = now))
        assertFalse(isUserOnline(online = false, lastSeenMillis = now, nowMillis = now))
    }

    @Test
    fun `hidden last seen is never rendered as online or last seen`() {
        val now = 200_000L
        val hidden = UserData(
            uid = "hidden",
            online = true,
            lastSeen = Timestamp(Date(now - 1_000L)),
            hideLastSeen = true,
        )

        assertFalse(visibleOnline(hidden, now))
        assertEquals(null, visibleLastSeenMillis(hidden))
    }

    @Test
    fun `presence is unchanged while last seen is visible`() {
        val now = 200_000L
        val fresh = UserData(uid = "fresh", online = true, lastSeen = Timestamp(Date(now - 1_000L)))
        val stale = UserData(uid = "stale", online = true, lastSeen = Timestamp(Date(now - 90_000L)))

        assertTrue(visibleOnline(fresh, now))
        assertEquals(now - 1_000L, visibleLastSeenMillis(fresh))
        assertFalse(visibleOnline(stale, now))
        assertEquals(now - 90_000L, visibleLastSeenMillis(stale))
        assertFalse(visibleOnline(null, now))
        assertEquals(null, visibleLastSeenMillis(null))
    }

    @Test
    fun `chat header hides presence but keeps profile copy when last seen is hidden`() {
        val now = 200_000L
        val hidden = UserData(
            uid = "hidden",
            online = true,
            lastSeen = Timestamp(Date(now - 1_000L)),
            hideLastSeen = true,
        )

        val label = chatPresenceLabel(
            online = visibleOnline(hidden, now),
            lastSeenMillis = visibleLastSeenMillis(hidden),
            school = "IIT Delhi",
            verified = true,
            nowMillis = now,
        )

        assertEquals("IIT Delhi  · Verified member", label)
    }

    @Test
    fun `presence labels match the shared website contract`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = java.time.ZonedDateTime.of(2026, 8, 2, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("Online", chatPresenceLabel(true, now - 30_000L, null, false, now))
        assertEquals("Active just now", chatPresenceLabel(false, now - 30_000L, null, false, now))
        assertEquals("Active 3m ago", chatPresenceLabel(false, now - 3 * 60_000L, null, false, now))
        assertEquals("Last seen yesterday", lastSeenLabel(now - 24 * 60 * 60_000L, now, zone))
    }

    @Test
    fun `message day helpers respect today yesterday and boundaries`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val today = java.time.ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = today - 24 * 60 * 60_000L
        val previous = messageAt("previous", yesterday)
        val current = messageAt("current", today)

        assertTrue(messageStartsNewDay(previous, current, zone))
        assertFalse(messageStartsNewDay(current, messageAt("same-day", today + 60_000L), zone))
        assertEquals("Today", messageDayLabel(today, today, zone))
        assertEquals("Yesterday", messageDayLabel(yesterday, today, zone))
    }

    @Test
    fun `voice work blocks duplicate composer actions`() {
        val room = ChatRoomDetail(
            room = ChatRoom(id = "room", participants = listOf("viewer", "other")),
            otherUser = UserData(uid = "other"),
        )

        assertFalse(ChatRoomUiState(room = room, isRecordingVoice = true).canSend("viewer"))
        assertFalse(ChatRoomUiState(room = room, isSendingVoice = true).canSend("viewer"))
        assertTrue(ChatRoomUiState(room = room).canSend("viewer"))
    }

    @Test
    fun `voice duration and speed labels are compact`() {
        assertEquals("0:00", formatVoiceTime(0L))
        assertEquals("1:05", formatVoiceTime(65_000L))
        assertEquals("1x", formatPlaybackSpeed(1f))
        assertEquals("1.5x", formatPlaybackSpeed(1.5f))
    }

    @Test
    fun `swipe reply requires threshold and remains disabled for selection or deleted messages`() {
        assertFalse(shouldTriggerSwipeReply(offsetPx = 63f, thresholdPx = 64f, selectionMode = false, deleted = false))
        assertTrue(shouldTriggerSwipeReply(offsetPx = 64f, thresholdPx = 64f, selectionMode = false, deleted = false))
        assertFalse(shouldTriggerSwipeReply(offsetPx = 80f, thresholdPx = 64f, selectionMode = true, deleted = false))
        assertFalse(shouldTriggerSwipeReply(offsetPx = 80f, thresholdPx = 64f, selectionMode = false, deleted = true))
    }

    @Test
    fun `jump to latest appears only when the reader is away from the final message`() {
        assertFalse(shouldShowJumpToLatest(totalItems = 0, lastVisibleIndex = null))
        assertFalse(shouldShowJumpToLatest(totalItems = 12, lastVisibleIndex = 10))
        assertTrue(shouldShowJumpToLatest(totalItems = 12, lastVisibleIndex = 9))
    }

    @Test
    fun `reply target index accounts for conversation intro and reconciled client ids`() {
        val messages = listOf(
            Message(id = "first"),
            Message(id = "server-second", clientMessageId = "android-second"),
        )

        assertEquals(1, replyTargetListIndex(messages, "first"))
        assertEquals(2, replyTargetListIndex(messages, "server-second"))
        assertEquals(2, replyTargetListIndex(messages, "android-second"))
        assertEquals(null, replyTargetListIndex(messages, "missing"))
    }

    @Test
    fun `optimistic messages reconcile by client id and preserve chronological order`() {
        val sender = UserData(uid = "viewer", name = "Maya")
        val pending = optimisticTextMessage("android-pending", sender, "Pending", null, MessageStatus.Pending)
        val delivered = pending.copy(id = "server-pending", status = MessageStatus.Sent.raw, createdAt = Timestamp(Date(1_000L)))
        val failed = optimisticTextMessage("android-failed", sender, "Retry me", null, MessageStatus.Failed).copy(createdAt = Timestamp(Date(2_000L)))

        val merged = mergeOptimisticMessages(remote = listOf(delivered), optimistic = listOf(pending, failed))

        assertEquals(listOf("server-pending", "android-failed"), merged.map(Message::id))
        assertEquals(MessageStatus.Failed.raw, merged.last().status)
    }

    @Test
    fun `optimistic replies preserve structured preview metadata`() {
        val reply = Message(id = "photo", senderName = "Noah", type = "image", image = "https://cdn/photo.jpg")
        val optimistic = optimisticTextMessage("android-message", UserData(uid = "viewer", name = "Maya"), "Looks good", reply, MessageStatus.Pending)

        assertEquals("photo", optimistic.replyToMessageId)
        assertEquals("Photo", optimistic.replyToText)
        assertEquals("image", optimistic.replyToType)
    }

    @Test
    fun `message selection derives stable messages in conversation order`() {
        val first = Message(id = "first", senderId = "viewer")
        val second = Message(id = "second", senderId = "other")
        val state = ChatRoomUiState(
            messages = listOf(first, second),
            selectedMessageIds = setOf("second", "missing", "first"),
        )

        assertTrue(state.selectionMode)
        assertEquals(listOf("first", "second"), state.selectedMessages.map(Message::id))
    }

    @Test
    fun `forward target search ignores case and forward keys include target type`() {
        val direct = ForwardTarget("same-id", ForwardTargetType.Direct, "Maya Singh")
        val club = ForwardTarget("same-id", ForwardTargetType.Club, "Physics Society")
        val state = ChatRoomUiState(
            forwardTargets = listOf(direct, club),
            forwardQuery = "PHYSICS",
        )

        assertEquals(listOf(club), state.visibleForwardTargets)
        assertEquals("Direct:same-id", direct.forwardKey())
        assertEquals("Club:same-id", club.forwardKey())
    }

    private fun roomItem(
        id: String,
        title: String? = null,
        message: String? = null,
        other: UserData? = null,
        archivedBy: List<String> = emptyList(),
        deletedBy: List<String> = emptyList(),
        pinnedBy: List<String> = emptyList(),
        mutedBy: List<String> = emptyList(),
        unreadBy: List<String> = emptyList(),
    ) = ChatRoomListItem(
        room = ChatRoom(
            id = id,
            participants = listOf("viewer", other?.uid ?: "other"),
            productTitle = title,
            lastMessage = message,
            archivedBy = archivedBy,
            deletedBy = deletedBy,
            pinnedBy = pinnedBy,
            mutedBy = mutedBy,
            unreadBy = unreadBy,
        ),
        otherUser = other,
        viewerId = "viewer",
    )

    private fun messageAt(id: String, epochMillis: Long) = com.nextbench.data.model.Message(
        id = id,
        createdAt = Timestamp(Date(epochMillis)),
    )
}
