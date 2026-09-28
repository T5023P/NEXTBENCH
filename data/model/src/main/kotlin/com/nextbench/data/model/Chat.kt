package com.nextbench.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * `chatRooms/{id}` — a 1-to-1 or group direct-message room.
 * Per-user state (mute/archive/pin/delete) is stored as uid arrays so a single document
 * update never races with another user's state change.
 */
data class ChatRoom(
    @DocumentId val id: String = "",
    val participants: List<String> = emptyList(),
    val type: String = "dm",
    val productId: String? = null,
    val productTitle: String? = null,
    val lastMessage: String? = null,
    val lastSenderId: String? = null,
    val status: String = "active",
    val requestedBy: String? = null,
    val updatedAt: Timestamp? = null,
    val unreadBy: List<String> = emptyList(),
    val mutedBy: List<String> = emptyList(),
    val archivedBy: List<String> = emptyList(),
    val pinnedBy: List<String> = emptyList(),
    val deletedBy: List<String> = emptyList(),
    val typingUsers: Map<String, Timestamp> = emptyMap(),
)

/**
 * `clubs/{id}` — a school community group. Extends the chat-room shape with membership
 * roles, visibility settings, and moderation controls.
 */
data class Club(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String? = null,
    /** Website writes this field as `avatar`; kept separate from user profile images. */
    val avatar: String? = null,
    val school: String = "",
    val city: String? = null,
    val type: String = ClubType.Public.raw,
    val leadId: String? = null,
    val coLeadIds: List<String> = emptyList(),
    val memberIds: List<String> = emptyList(),
    val inviteCode: String? = null,
    val memberCount: Int = 0,
    val lastMessage: String? = null,
    val lastSenderId: String? = null,
    val lastSenderName: String? = null,
    val tags: List<String> = emptyList(),
    val pinnedMessageId: String? = null,
    val pinnedMessageText: String? = null,
    val updatedAt: Timestamp? = null,
    val unreadBy: List<String> = emptyList(),
    val mutedBy: List<String> = emptyList(),
    val archivedBy: List<String> = emptyList(),
    val pinnedBy: List<String> = emptyList(),
    val deletedBy: List<String> = emptyList(),
    val typingUsers: Map<String, Timestamp> = emptyMap(),
    val settings: ClubSettings = ClubSettings(),
    val createdAt: Timestamp? = null,
)

data class ClubSettings(
    val hideMembersAbove50: Boolean = false,
    val onlyLeadsCanPost: Boolean = false,
    /** Website stores this as a number of seconds; zero means disabled. */
    val slowMode: Int = 0,
    val muteNotifications: Boolean = false,
)

/**
 * `chatRooms/{roomId}/messages/{id}` or `clubs/{clubId}/messages/{id}`.
 * [type] drives which media fields are populated. [reactions] maps an emoji string to the
 * list of uids that placed it.
 */
data class Message(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val senderName: String? = null,
    val senderAvatar: String? = null,
    val text: String? = null,
    val image: String? = null,
    val type: String = MessageType.Text.raw,
    val audioUrl: String? = null,
    val duration: Long? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val video: VideoAttachment? = null,
    val file: FileAttachment? = null,
    val createdAt: Timestamp? = null,
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSenderName: String? = null,
    val replyToType: String? = null,
    val deletedFor: List<String> = emptyList(),
    val isDeletedForEveryone: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val readBy: List<String> = emptyList(),
    val clientMessageId: String? = null,
    val status: String = MessageStatus.Sent.raw,
    val forwardedFrom: ForwardedFrom? = null,
)

data class VideoAttachment(
    val url: String = "",
    val poster: String? = null,
    val w: Int = 0,
    val h: Int = 0,
    val duration: Long = 0,
)

data class FileAttachment(
    val url: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val pages: Int? = null,
)

data class ForwardedFrom(
    val senderId: String = "",
    val senderName: String? = null,
)
