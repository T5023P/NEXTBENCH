package com.nextbench.data.firebase

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.nextbench.data.model.ChatRoom
import com.nextbench.data.model.Club
import com.nextbench.data.model.ClubSettings
import com.nextbench.data.model.FileAttachment
import com.nextbench.data.model.ForwardedFrom
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageStatus
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VideoAttachment
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class ChatRoomListItem(
    val room: ChatRoom,
    val otherUser: UserData?,
    val viewerId: String,
) {
    val hasUnreadActivity: Boolean get() = viewerId in room.unreadBy
    val unread: Boolean get() = hasUnreadActivity && !muted
    val muted: Boolean get() = viewerId in room.mutedBy
    val archived: Boolean get() = viewerId in room.archivedBy
    val pinned: Boolean get() = viewerId in room.pinnedBy
    val deleted: Boolean get() = viewerId in room.deletedBy && !hasUnreadActivity
}

data class ChatRoomDetail(
    val room: ChatRoom,
    val otherUser: UserData?,
)

data class ChatBlockState(
    val blockedByViewer: Boolean = false,
    val blockedViewer: Boolean = false,
) {
    val isBlocked: Boolean get() = blockedByViewer || blockedViewer
}

enum class ForwardTargetType { Direct, Club }

data class ForwardTarget(
    val id: String,
    val type: ForwardTargetType,
    val name: String,
    val avatar: String? = null,
)

data class ForwardResult(
    val deliveredTargets: Int,
    val failedTargets: Int,
)

enum class InboxBulkOperation {
    Pin,
    Unpin,
    MarkRead,
    MarkUnread,
    Mute,
    Unmute,
    Archive,
    Restore,
    Delete,
}

data class InboxBulkResult(
    val updatedRooms: Int,
    val failedRooms: Int,
)

/**
 * Shared Firestore boundary for direct conversations. The field names and write order mirror
 * the web client so a room created on one platform is immediately usable on the other.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val uploader: CloudinaryUploader,
    private val storageProvider: Provider<FirebaseStorage>,
) {
    private val storage get() = storageProvider.get()
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    fun observeRooms(uid: String): Flow<List<ChatRoomListItem>> = configuredFlow(uid) {
        combine(
            refs.chatRooms.whereArrayContains("participants", uid).snapshotFlow(),
            refs.blocks.whereEqualTo("blockerId", uid).snapshotFlow(),
            refs.blocks.whereEqualTo("blockedId", uid).snapshotFlow(),
        ) { roomSnapshot, blockedSnapshot, blockedBySnapshot ->
            val blockedIds = blockedSnapshot.documents.mapNotNull { it.getString("blockedId") }.toSet()
            val blockedByIds = blockedBySnapshot.documents.mapNotNull { it.getString("blockerId") }.toSet()
            Triple(roomSnapshot, blockedIds, blockedByIds)
        }.map { (snapshot, blockedIds, blockedByIds) ->
                val blocked = blockedIds + blockedByIds
                val rooms = snapshot.documents.mapNotNull(DocumentSnapshot::toChatRoom)
                val otherIds = rooms
                    .flatMap { it.participants }
                    .filter { it.isNotBlank() && it != uid && it !in blocked }
                    .distinct()
                val users = coroutineScope {
                    otherIds.map { id ->
                        async { id to runCatching { loadUser(id) }.getOrNull() }
                    }.awaitAll().toMap()
                }
                rooms
                    .filter { uid in it.participants }
                    .filter { room -> room.participants.none { it != uid && it in blocked } }
                    .map { room ->
                        ChatRoomListItem(
                            room = room,
                            otherUser = room.participants
                                .firstOrNull { it != uid }
                                ?.let(users::get),
                            viewerId = uid,
                        )
                    }
                    .sortedWith(
                        compareByDescending<ChatRoomListItem> {
                            it.room.pinnedBy.contains(uid)
                        }.thenByDescending {
                            it.room.updatedAt?.toDate()?.time ?: Long.MIN_VALUE
                        },
                    )
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRoom(roomId: String, viewerId: String): Flow<ChatRoomDetail?> = configuredFlow(viewerId) {
        refs.chatRoom(roomId)
            .snapshotFlow()
            .flatMapLatest { snapshot ->
                val room = snapshot.toChatRoom() ?: return@flatMapLatest flowOf(null)
                if (viewerId !in room.participants) return@flatMapLatest flowOf(null)
                val otherId = room.participants.firstOrNull { it != viewerId }
                if (otherId.isNullOrBlank()) {
                    flowOf(ChatRoomDetail(room = room, otherUser = null))
                } else {
                    refs.user(otherId).snapshotFlow().map { userSnapshot ->
                        ChatRoomDetail(
                            room = room,
                            otherUser = userSnapshot
                                .takeIf(DocumentSnapshot::exists)
                                ?.toObject(UserData::class.java)
                                ?.copy(uid = otherId),
                        )
                    }
                }
            }
    }

    fun observeMessages(roomId: String, viewerId: String): Flow<List<Message>> = configuredFlow(viewerId) {
        refs.messages(roomId)
            .orderBy("createdAt")
            .limitToLast(MessageWindowSize)
            .snapshotFlow()
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull(DocumentSnapshot::toMessage)
                    .filterNot { viewerId in it.deletedFor }
                    .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
            }
    }

    fun observeBlockState(viewerId: String, otherUserId: String): Flow<ChatBlockState> = configuredFlow(viewerId) {
        val viewerBlocked = refs.blocks.document("${viewerId}_${otherUserId}").snapshotFlow()
        val blockedViewer = refs.blocks.document("${otherUserId}_${viewerId}").snapshotFlow()
        combine(viewerBlocked, blockedViewer) { first, second ->
            ChatBlockState(
                blockedByViewer = first.exists(),
                blockedViewer = second.exists(),
            )
        }
    }

    suspend fun loadForwardTargets(uid: String): Result<List<ForwardTarget>> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        coroutineScope {
            val rooms = async {
                refs.chatRooms.whereArrayContains("participants", uid).get().await().documents.mapNotNull { snapshot ->
                    val room = snapshot.toChatRoom() ?: return@mapNotNull null
                    val otherId = room.participants.firstOrNull { it != uid } ?: return@mapNotNull null
                    val user = runCatching { loadUser(otherId) }.getOrNull()
                    ForwardTarget(
                        id = room.id,
                        type = ForwardTargetType.Direct,
                        name = user?.name?.ifBlank { null } ?: "NextBench member",
                        avatar = user?.profilePicture,
                    )
                }
            }
            val clubs = async {
                refs.clubs.whereArrayContains("memberIds", uid).get().await().documents.mapNotNull { snapshot ->
                    val club = snapshot.toClub() ?: return@mapNotNull null
                    ForwardTarget(
                        id = club.id,
                        type = ForwardTargetType.Club,
                        name = club.name.ifBlank { "Campus club" },
                        avatar = club.avatar,
                    )
                }
            }
            (rooms.await() + clubs.await()).sortedWith(compareBy<ForwardTarget> { it.type }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun forwardMessages(
        sender: UserData,
        messages: List<Message>,
        targets: List<ForwardTarget>,
    ): Result<ForwardResult> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        require(messages.isNotEmpty()) { "Select at least one message to forward." }
        require(messages.size <= MaxForwardMessages) { "You can forward up to $MaxForwardMessages messages at once." }
        require(targets.isNotEmpty()) { "Choose at least one conversation." }
        require(targets.size <= MaxForwardTargets) { "You can forward to up to $MaxForwardTargets conversations at once." }

        var deliveredTargets = 0
        var failedTargets = 0
        targets.distinctBy { it.type to it.id }.forEach { target ->
            val delivered = runCatching { forwardToTarget(sender, messages, target) }.getOrDefault(false)
            if (delivered) deliveredTargets++ else failedTargets++
        }
        ForwardResult(deliveredTargets, failedTargets)
    }

    suspend fun deleteForMeBulk(roomId: String, messageIds: List<String>, uid: String): Result<Int> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        val ids = normalizedMessageIds(messageIds)
        ids.chunked(FirestoreWriteBatchLimit).forEach { chunk ->
            val batch = refs.chatRooms.firestore.batch()
            chunk.forEach { id -> batch.update(refs.messages(roomId).document(id), "deletedFor", FieldValue.arrayUnion(uid)) }
            batch.commit().await()
        }
        ids.size
    }

    suspend fun deleteForEveryoneBulk(roomId: String, messageIds: List<String>, uid: String): Result<Int> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        val ids = normalizedMessageIds(messageIds)
        val messages = ids.map { id ->
            refs.messages(roomId).document(id).get().await().toMessage()
                ?: throw FirebaseFirestoreException("A selected message is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        }
        require(messages.all { it.senderId == uid }) { "You can only delete your own messages for everyone." }
        ids.chunked(FirestoreWriteBatchLimit).forEach { chunk ->
            val batch = refs.chatRooms.firestore.batch()
            chunk.forEach { id -> batch.update(refs.messages(roomId).document(id), deletedForEveryonePayload()) }
            batch.commit().await()
        }
        ids.size
    }

    suspend fun sendText(
        roomId: String,
        sender: UserData,
        text: String,
        replyTo: Message? = null,
        clientMessageId: String? = null,
    ): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Write a message first." }
        require(normalized.length <= MessageCharacterLimit) {
            "Messages can be up to $MessageCharacterLimit characters."
        }

        val roomRef = refs.chatRoom(roomId)
        val roomSnapshot = roomRef.get().await()
        val room = roomSnapshot.toChatRoom()
            ?: throw FirebaseFirestoreException("This conversation is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(sender.uid in room.participants) { "You are not a member of this conversation." }
        require(room.status != "pending") { "Accept this chat request before replying." }
        val otherIds = room.participants.filter { it != sender.uid && it.isNotBlank() }
        require(!hasBlockRelationship(sender.uid, otherIds)) { "Cannot message this user." }

        val stableClientId = clientMessageId?.trim()?.takeIf(String::isNotBlank)
        require(stableClientId == null || isValidMessageDocumentId(stableClientId)) { "Message retry identifier is invalid." }
        val messageRef = stableClientId?.let { refs.messages(roomId).document(it) } ?: refs.messages(roomId).document()
        val payload = textMessagePayload(
            sender = sender,
            messageId = messageRef.id,
            text = normalized,
            replyTo = replyTo,
            clientMessageId = stableClientId ?: "android_${messageRef.id}",
        )
        val roomUpdate = roomMetadataPayload(
            senderId = sender.uid,
            lastMessage = normalized,
            recipientIds = otherIds,
        )
        val batch = roomRef.firestore.batch()
        batch.set(messageRef, payload)
        batch.update(roomRef, roomUpdate)
        batch.commit().await()

        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            text = normalized,
            type = MessageType.Text.raw,
            createdAt = Timestamp.now(),
            clientMessageId = stableClientId ?: "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.replyPreviewText(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.replyPreviewType(),
        )
    }

    suspend fun sendImage(roomId: String, sender: UserData, file: File, width: Int, height: Int, caption: String? = null, replyTo: Message? = null): Result<Message> =
        sendAttachment(roomId, sender, file, caption, replyTo, AttachmentKind.Image(width, height))

    suspend fun sendVideo(roomId: String, sender: UserData, file: File, width: Int, height: Int, durationMs: Long? = null, caption: String? = null, replyTo: Message? = null): Result<Message> =
        sendAttachment(roomId, sender, file, caption, replyTo, AttachmentKind.Video(width, height, durationMs))

    suspend fun sendFile(roomId: String, sender: UserData, file: File, mime: String, caption: String? = null, pages: Int? = null, replyTo: Message? = null, displayName: String = file.name): Result<Message> =
        sendAttachment(roomId, sender, file, caption, replyTo, AttachmentKind.File(mime, pages), normalizedAttachmentDisplayName(displayName, file.name))

    suspend fun sendVoice(
        roomId: String,
        sender: UserData,
        file: File,
        durationSeconds: Long,
        mimeType: String,
        replyTo: Message? = null,
        onProgress: (Int) -> Unit = {},
    ): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        require(file.isFile) { "Recording file is missing." }
        voiceMessageValidationError(durationSeconds, file.length(), mimeType)?.let { throw IllegalArgumentException(it) }

        val roomRef = refs.chatRoom(roomId)
        val room = roomRef.get().await().toChatRoom()
            ?: throw FirebaseFirestoreException("This conversation is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(sender.uid in room.participants) { "You are not a member of this conversation." }
        require(room.status != "pending") { "Accept this chat request before replying." }
        require(!hasBlockRelationship(sender.uid, room.participants.filter { it != sender.uid })) { "Cannot message this user." }

        val messageRef = refs.messages(roomId).document()
        val extension = file.extension.ifBlank { "m4a" }.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "m4a" }
        val storageRef = storage.reference.child("voice/$roomId/${messageRef.id}.$extension")
        val metadata = StorageMetadata.Builder().setContentType(mimeType).build()
        suspend fun upload() {
            storageRef.putFile(Uri.fromFile(file), metadata)
                .addOnProgressListener { snapshot ->
                    if (snapshot.totalByteCount > 0L) {
                        onProgress(((snapshot.bytesTransferred * 100L) / snapshot.totalByteCount).toInt().coerceIn(0, 100))
                    }
                }
                .await()
        }

        runCatching { upload() }.recoverCatching { upload() }.getOrThrow()
        val audioUrl = try {
            storageRef.downloadUrl.await().toString()
        } catch (error: Exception) {
            runCatching { storageRef.delete().await() }
            throw error
        }
        val payload = voiceMessagePayload(
            sender = sender,
            messageId = messageRef.id,
            audioUrl = audioUrl,
            durationSeconds = durationSeconds,
            fileSize = file.length(),
            mimeType = mimeType,
            replyTo = replyTo,
        )
        try {
            val batch = roomRef.firestore.batch()
            batch.set(messageRef, payload)
            batch.update(
                roomRef,
                roomMetadataPayload(
                    senderId = sender.uid,
                    lastMessage = "Voice message",
                    recipientIds = room.participants.filter { it != sender.uid },
                    lastMessageType = MessageType.Voice.raw,
                ),
            )
            batch.commit().await()
        } catch (error: Exception) {
            runCatching { storageRef.delete().await() }
            throw error
        }
        onProgress(100)
        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            type = MessageType.Voice.raw,
            audioUrl = audioUrl,
            duration = durationSeconds,
            createdAt = Timestamp.now(),
            clientMessageId = "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.replyPreviewText(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.replyPreviewType(),
        )
    }

    suspend fun toggleReaction(roomId: String, messageId: String, uid: String, emoji: String): Result<Boolean> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(emoji.length <= 16) { "That reaction is not supported." }
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        val ref = refs.messages(roomId).document(messageId)
        val message = ref.get().await().toMessage()
            ?: throw FirebaseFirestoreException("This message is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        val reactions = message.reactions.toMutableMap()
        val users = reactions[emoji].orEmpty().toMutableList()
        val added = uid !in users
        if (added) users += uid else users -= uid
        if (users.isEmpty()) reactions.remove(emoji) else reactions[emoji] = users.distinct()
        ref.update("reactions", reactions).await()
        added
    }

    suspend fun deleteForMe(roomId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        refs.messages(roomId).document(messageId).update("deletedFor", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun deleteForEveryone(roomId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        val ref = refs.messages(roomId).document(messageId)
        val message = ref.get().await().toMessage()
            ?: throw FirebaseFirestoreException("This message is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(message.senderId == uid) { "Only the sender can delete this message for everyone." }
        ref.update(deletedForEveryonePayload()).await()
    }

    suspend fun markMessageRead(roomId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        if (isRoomParticipant(roomId, uid)) refs.messages(roomId).document(messageId).update("readBy", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun markRead(roomId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        refs.chatRoom(roomId).update(
            mapOf(
                "unreadBy" to FieldValue.arrayRemove(uid),
                "deletedBy" to FieldValue.arrayRemove(uid),
            ),
        ).await()
    }

    suspend fun setTyping(roomId: String, uid: String, typing: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(isRoomParticipant(roomId, uid)) { "You are not a member of this conversation." }
        refs.chatRoom(roomId).update(
            "typingUsers.$uid",
            if (typing) FieldValue.serverTimestamp() else FieldValue.delete(),
        ).await()
    }

    suspend fun setArchived(roomId: String, uid: String, archived: Boolean): Result<Unit> =
        updateViewerFlag(roomId, uid, "archivedBy", archived)

    suspend fun setMuted(roomId: String, uid: String, muted: Boolean): Result<Unit> =
        updateViewerFlag(roomId, uid, "mutedBy", muted)

    suspend fun setPinned(roomId: String, uid: String, pinned: Boolean): Result<Unit> =
        updateViewerFlag(roomId, uid, "pinnedBy", pinned)

    suspend fun setUnread(roomId: String, uid: String, unread: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        if (unread) {
            refs.chatRoom(roomId).update("unreadBy", FieldValue.arrayUnion(uid)).await()
        } else {
            refs.chatRoom(roomId).update(
                mapOf(
                    "unreadBy" to FieldValue.arrayRemove(uid),
                    "deletedBy" to FieldValue.arrayRemove(uid),
                ),
            ).await()
        }
    }

    suspend fun deleteForUser(roomId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        refs.chatRoom(roomId).update(
            mapOf(
                "deletedBy" to FieldValue.arrayUnion(uid),
                "unreadBy" to FieldValue.arrayRemove(uid),
            ),
        ).await()
    }

    suspend fun updateInboxBulk(
        roomIds: Collection<String>,
        uid: String,
        operation: InboxBulkOperation,
    ): Result<InboxBulkResult> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val ids = roomIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(ids.isNotEmpty()) { "Select at least one conversation." }
        require(ids.size <= MaxInboxSelection) { "Select up to $MaxInboxSelection conversations at once." }
        val payload = inboxBulkPayload(uid, operation)
        var updated = 0
        var failed = 0
        ids.chunked(FirestoreWriteBatchLimit).forEach { chunk ->
            val batchSucceeded = runCatching {
                val batch = refs.chatRooms.firestore.batch()
                chunk.forEach { roomId -> batch.update(refs.chatRoom(roomId), payload) }
                batch.commit().await()
            }.isSuccess
            if (batchSucceeded) {
                updated += chunk.size
            } else {
                chunk.forEach { roomId ->
                    if (runCatching { refs.chatRoom(roomId).update(payload).await() }.isSuccess) updated++ else failed++
                }
            }
        }
        InboxBulkResult(updatedRooms = updated, failedRooms = failed)
    }

    suspend fun acceptRequest(roomId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val roomRef = refs.chatRoom(roomId)
        val room = roomRef.get().await().toChatRoom()
            ?: throw FirebaseFirestoreException("This conversation is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(uid in room.participants && room.status == "pending" && room.requestedBy != uid) {
            "This chat request is no longer waiting for you."
        }
        roomRef.update(
            mapOf(
                "status" to "active",
                "requestedBy" to FieldValue.delete(),
                "unreadBy" to FieldValue.arrayRemove(uid),
                "deletedBy" to FieldValue.arrayRemove(uid),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun declineRequest(roomId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val roomRef = refs.chatRoom(roomId)
        val room = roomRef.get().await().toChatRoom()
            ?: throw FirebaseFirestoreException("This conversation is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(uid in room.participants && room.status == "pending" && room.requestedBy != uid) {
            "This chat request is no longer waiting for you."
        }
        val batch = roomRef.firestore.batch()
        refs.messages(roomId).get().await().documents.forEach { batch.delete(it.reference) }
        batch.delete(roomRef)
        batch.commit().await()
    }

    suspend fun getOrCreateDirectRoom(viewer: UserData, otherUserId: String): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(viewer.uid)
        require(viewer.verified) { "Verify your profile before sending direct messages." }
        require(otherUserId.isNotBlank() && otherUserId != viewer.uid) { "This conversation cannot be started." }
        require(!hasBlockRelationship(viewer.uid, listOf(otherUserId))) { "Cannot message this member." }

        val existing = refs.chatRooms
            .whereArrayContains("participants", viewer.uid)
            .get()
            .await()
            .documents
            .firstOrNull { snapshot ->
                val room = snapshot.toChatRoom()
                room?.type == "dm" && otherUserId in room.participants
            }
        if (existing != null) return@runCatching existing.id

        val target = refs.user(otherUserId).get().await()
        require(target.exists()) { "This member is no longer available." }
        val followersOnly = target.get("chatPrivacy.followersOnly") as? Boolean ?: false
        val followsTarget = refs.follows
            .whereEqualTo("followerId", viewer.uid)
            .whereEqualTo("followingId", otherUserId)
            .limit(1)
            .get()
            .await()
            .documents
            .isNotEmpty()
        val pending = followersOnly && !followsTarget
        val roomRef = refs.chatRooms.document()
        roomRef.set(
            buildMap<String, Any> {
                put("participants", listOf(viewer.uid, otherUserId))
                put("type", "dm")
                put("lastMessage", "")
                put("lastSenderId", "")
                put("status", if (pending) "pending" else "active")
                put("updatedAt", FieldValue.serverTimestamp())
                if (pending) put("requestedBy", viewer.uid)
            },
        ).await()
        roomRef.id
    }

    private suspend fun loadUser(uid: String): UserData? =
        refs.user(uid).get().await().takeIf { it.exists() }?.toObject(UserData::class.java)?.copy(uid = uid)

    private suspend fun hasBlockRelationship(viewerId: String, otherIds: List<String>): Boolean =
        otherIds.any { otherId ->
            refs.blocks.document("${viewerId}_${otherId}").get().await().exists() ||
                refs.blocks.document("${otherId}_${viewerId}").get().await().exists()
        }

    private suspend fun forwardToTarget(sender: UserData, messages: List<Message>, target: ForwardTarget): Boolean {
        val roomRef = when (target.type) {
            ForwardTargetType.Direct -> refs.chatRoom(target.id)
            ForwardTargetType.Club -> refs.club(target.id)
        }
        val snapshot = roomRef.get().await()
        if (!snapshot.exists()) return false
        val recipientIds = when (target.type) {
            ForwardTargetType.Direct -> {
                val room = snapshot.toChatRoom() ?: return false
                if (sender.uid !in room.participants || room.status == "pending") return false
                if (hasBlockRelationship(sender.uid, room.participants.filter { it != sender.uid })) return false
                room.participants.filter { it != sender.uid }
            }
            ForwardTargetType.Club -> {
                val club = snapshot.toClub() ?: return false
                val canPost = sender.uid in club.memberIds && (!club.settings.onlyLeadsCanPost || sender.uid == club.leadId || sender.uid in club.coLeadIds)
                if (!canPost) return false
                club.memberIds.filter { it != sender.uid }
            }
        }
        val messageCollection = when (target.type) {
            ForwardTargetType.Direct -> refs.messages(target.id)
            ForwardTargetType.Club -> refs.clubMessages(target.id)
        }
        var lastPreview = "Forwarded message"
        var delivered = 0
        messages.forEach { source ->
            runCatching {
                val messageRef = messageCollection.document()
                messageRef.set(forwardedMessagePayload(sender, source, messageRef.id)).await()
                lastPreview = source.forwardPreview()
                delivered++
            }
        }
        if (delivered == 0) return false
        runCatching {
            val metadata = when (target.type) {
                ForwardTargetType.Direct -> roomMetadataPayload(sender.uid, lastPreview, recipientIds, "forwarded")
                ForwardTargetType.Club -> clubMessageMetadataPayload(sender, lastPreview, recipientIds)
            }
            roomRef.update(metadata).await()
        }
        return true
    }

    private fun requireAuthenticated(uid: String) {
        require(auth.currentUser?.uid == uid) { "Your session expired. Sign in and try again." }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ChatConfigurationException()
    }

    private suspend fun isRoomParticipant(roomId: String, uid: String): Boolean =
        refs.chatRoom(roomId).get().await().toChatRoom()?.participants?.contains(uid) == true

    private suspend fun updateViewerFlag(roomId: String, uid: String, field: String, enabled: Boolean): Result<Unit> =
        runCatching {
            ensureConfigured()
            requireAuthenticated(uid)
            refs.chatRoom(roomId).update(field, if (enabled) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)).await()
        }

    private suspend fun sendAttachment(
        roomId: String,
        sender: UserData,
        file: File,
        caption: String?,
        replyTo: Message?,
        kind: AttachmentKind,
        displayName: String = file.name,
    ): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        require(file.isFile && file.length() > 0L) { "Choose a valid attachment." }
        val roomRef = refs.chatRoom(roomId)
        val room = roomRef.get().await().toChatRoom()
            ?: throw FirebaseFirestoreException("This conversation is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(sender.uid in room.participants) { "You are not a member of this conversation." }
        require(room.status != "pending") { "Accept this chat request before replying." }
        require(!hasBlockRelationship(sender.uid, room.participants.filter { it != sender.uid })) { "Cannot message this user." }
        val uploaded = when (kind) {
            is AttachmentKind.Image -> uploader.upload(file, "nextbench/chats/$roomId", CloudinaryResourceType.Image)
            is AttachmentKind.Video -> uploader.upload(file, "nextbench/chat_videos/$roomId", CloudinaryResourceType.Video)
            is AttachmentKind.File -> uploader.upload(file, "nextbench/chat_files/$roomId", if (kind.mime == "application/pdf") CloudinaryResourceType.Image else CloudinaryResourceType.Raw)
        }
        val messageRef = refs.messages(roomId).document()
        val payload = attachmentMessagePayload(sender, messageRef.id, uploaded, kind, displayName, file.length(), caption, replyTo)
        val label = kind.label
        val batch = roomRef.firestore.batch()
        batch.set(messageRef, payload)
        batch.update(roomRef, roomMetadataPayload(sender.uid, caption?.trim().takeUnless { it.isNullOrBlank() } ?: "[$label]", room.participants.filter { it != sender.uid }, label.lowercase()))
        batch.commit().await()
        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            text = caption?.trim()?.takeIf(String::isNotBlank),
            type = kind.type,
            image = (kind as? AttachmentKind.Image)?.let { uploaded.url },
            video = (kind as? AttachmentKind.Video)?.let { VideoAttachment(uploaded.url, w = it.width, h = it.height, duration = it.durationMs ?: 0L) },
            file = (kind as? AttachmentKind.File)?.let { FileAttachment(uploaded.url, displayName, file.length(), it.mime, uploaded.pages ?: it.pages) },
            createdAt = Timestamp.now(),
            clientMessageId = "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.replyPreviewText(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.replyPreviewType(),
        )
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }

    companion object {
        const val MessageCharacterLimit = 2_000
        const val MessageWindowSize = 100L
        const val MaxForwardMessages = 20
        const val MaxForwardTargets = 10
        const val MaxInboxSelection = 100
        private const val FirestoreWriteBatchLimit = 450
    }
}

private sealed interface AttachmentKind {
    val type: String
    val label: String
    data class Image(val width: Int, val height: Int) : AttachmentKind { override val type = MessageType.Image.raw; override val label = "Photo" }
    data class Video(val width: Int, val height: Int, val durationMs: Long?) : AttachmentKind { override val type = MessageType.Video.raw; override val label = "Video" }
    data class File(val mime: String, val pages: Int?) : AttachmentKind { override val type = MessageType.File.raw; override val label = "File" }
}

internal fun DocumentSnapshot.toChatRoom(): ChatRoom? =
    takeIf(DocumentSnapshot::exists)?.data?.toChatRoom(id)

internal fun DocumentSnapshot.toMessage(): Message? =
    takeIf(DocumentSnapshot::exists)?.data?.toChatMessage(id)

internal fun Map<String, Any?>.toChatRoom(id: String): ChatRoom = ChatRoom(
    id = id,
    participants = chatStringList("participants"),
    type = chatString("type", "dm"),
    productId = chatNullableString("productId"),
    productTitle = chatNullableString("productTitle"),
    lastMessage = chatNullableString("lastMessage"),
    lastSenderId = chatNullableString("lastSenderId"),
    status = chatString("status", "active"),
    requestedBy = chatNullableString("requestedBy"),
    updatedAt = chatTimestamp("updatedAt"),
    unreadBy = chatStringList("unreadBy"),
    mutedBy = chatStringList("mutedBy"),
    archivedBy = chatStringList("archivedBy"),
    pinnedBy = chatStringList("pinnedBy"),
    deletedBy = chatStringList("deletedBy"),
)

internal fun DocumentSnapshot.toClub(): Club? =
    takeIf(DocumentSnapshot::exists)?.data?.toClub(id)

internal fun Map<String, Any?>.toClub(id: String): Club = Club(
    id = id,
    name = chatString("name"),
    description = chatNullableString("description"),
    avatar = chatNullableString("avatar") ?: chatNullableString("imageUrl"),
    school = chatString("school"),
    city = chatNullableString("city"),
    type = chatString("type", "public"),
    leadId = chatNullableString("leadId") ?: chatStringList("leadIds").firstOrNull(),
    coLeadIds = chatStringList("coLeadIds"),
    memberIds = chatStringList("memberIds"),
    inviteCode = chatNullableString("inviteCode"),
    memberCount = (get("memberCount") as? Number)?.toInt()?.coerceAtLeast(0)
        ?: chatStringList("memberIds").size,
    lastMessage = chatNullableString("lastMessage"),
    lastSenderId = chatNullableString("lastSenderId"),
    lastSenderName = chatNullableString("lastSenderName"),
    tags = chatStringList("tags"),
    pinnedMessageId = chatNullableString("pinnedMessageId"),
    pinnedMessageText = chatNullableString("pinnedMessageText"),
    updatedAt = chatTimestamp("updatedAt"),
    unreadBy = chatStringList("unreadBy"),
    mutedBy = chatStringList("mutedBy"),
    archivedBy = chatStringList("archivedBy"),
    pinnedBy = chatStringList("pinnedBy"),
    deletedBy = chatStringList("deletedBy"),
    typingUsers = chatTimestampMap("typingUsers"),
    settings = chatMap("settings").toClubSettings(),
    createdAt = chatTimestamp("createdAt"),
)

private fun Map<String, Any?>.toClubSettings(): ClubSettings = ClubSettings(
    hideMembersAbove50 = get("hideMembersAbove50") as? Boolean ?: false,
    onlyLeadsCanPost = get("onlyLeadsCanPost") as? Boolean ?: false,
    slowMode = (get("slowMode") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
    muteNotifications = get("muteNotifications") as? Boolean ?: false,
)

internal fun Map<String, Any?>.toChatMessage(id: String): Message? {
    val senderId = chatString("senderId")
    if (senderId.isBlank()) return null
    val videoMap = chatMap("video")
    val fileMap = chatMap("file")
    val imageValue = get("image")
    val imageUrl = when (imageValue) {
        is String -> imageValue.takeIf(String::isNotBlank)
        is Map<*, *> -> imageValue.entries
            .firstOrNull { it.key?.toString() == "url" }
            ?.value?.toString()?.takeIf(String::isNotBlank)
        else -> null
    }
    return Message(
        id = id,
        senderId = senderId,
        senderName = chatNullableString("senderName"),
        senderAvatar = chatNullableString("senderAvatar"),
        text = chatNullableString("text"),
        image = imageUrl,
        type = chatString(
            "type",
            when {
                videoMap.isNotEmpty() -> MessageType.Video.raw
                fileMap.isNotEmpty() -> MessageType.File.raw
                chatNullableString("audioUrl") != null -> MessageType.Voice.raw
                imageUrl != null -> MessageType.Image.raw
                else -> MessageType.Text.raw
            },
        ),
        audioUrl = chatNullableString("audioUrl"),
        duration = chatLong("duration"),
        fileSize = chatLong("fileSize"),
        mimeType = chatNullableString("mimeType"),
        video = videoMap.takeIf(Map<*, *>::isNotEmpty)?.let { video ->
            VideoAttachment(
                url = video.chatString("url"),
                poster = video.chatNullableString("poster"),
                w = video.chatInt("w") ?: 0,
                h = video.chatInt("h") ?: 0,
                duration = video.chatLong("duration") ?: 0L,
            )
        },
        file = fileMap.takeIf(Map<*, *>::isNotEmpty)?.let { file ->
            FileAttachment(
                url = file.chatString("url"),
                name = file.chatString("name"),
                size = file.chatLong("size") ?: 0L,
                mime = file.chatString("mime"),
                pages = file.chatInt("pages"),
            )
        },
        createdAt = chatTimestamp("createdAt"),
        replyToMessageId = chatNullableString("replyToMessageId") ?: chatNullableString("replyToId"),
        replyToText = chatNullableString("replyToText"),
        replyToSenderName = chatNullableString("replyToSenderName"),
        replyToType = chatNullableString("replyToType"),
        deletedFor = chatStringList("deletedFor"),
        isDeletedForEveryone = get("isDeletedForEveryone") as? Boolean ?: false,
        reactions = chatReactions("reactions"),
        readBy = chatStringList("readBy"),
        clientMessageId = chatNullableString("clientMessageId"),
        status = chatString("status", MessageStatus.Sent.raw),
        forwardedFrom = chatForwardedFrom("forwardedFrom"),
    )
}

internal fun textMessagePayload(
    sender: UserData,
    messageId: String,
    text: String,
    replyTo: Message? = null,
    clientMessageId: String = "android_$messageId",
): Map<String, Any?> = mapOf(
    "senderId" to sender.uid,
    "senderName" to sender.name.ifBlank { "Student" },
    "senderAvatar" to sender.profilePicture,
    "text" to text,
    "type" to MessageType.Text.raw,
    "createdAt" to FieldValue.serverTimestamp(),
    "clientMessageId" to clientMessageId,
    "status" to MessageStatus.Sent.raw,
) + replyPayload(replyTo)

internal fun voiceMessagePayload(
    sender: UserData,
    messageId: String,
    audioUrl: String,
    durationSeconds: Long,
    fileSize: Long,
    mimeType: String,
    replyTo: Message? = null,
): Map<String, Any?> = mapOf(
    "senderId" to sender.uid,
    "senderName" to sender.name.ifBlank { "Student" },
    "senderAvatar" to sender.profilePicture,
    "type" to MessageType.Voice.raw,
    "audioUrl" to audioUrl,
    "duration" to durationSeconds,
    "fileSize" to fileSize,
    "mimeType" to mimeType,
    "createdAt" to FieldValue.serverTimestamp(),
    "clientMessageId" to "android_$messageId",
    "status" to MessageStatus.Sent.raw,
) + replyPayload(replyTo)

internal fun voiceMessageValidationError(durationSeconds: Long, fileSize: Long, mimeType: String): String? = when {
    durationSeconds < 1L -> "Recording is too short. Record for at least 1 second."
    durationSeconds > 300L -> "Voice messages can be up to 5 minutes."
    fileSize <= 0L -> "Recording file is empty."
    fileSize > 10L * 1024L * 1024L -> "Voice messages must be smaller than 10 MB."
    !mimeType.startsWith("audio/") -> "Recording format is not supported."
    else -> null
}

internal fun forwardedMessagePayload(
    sender: UserData,
    source: Message,
    messageId: String,
): Map<String, Any?> = buildMap {
    require(!source.isDeletedForEveryone) { "Deleted messages cannot be forwarded." }
    put("senderId", sender.uid)
    put("senderName", sender.name.ifBlank { "Student" })
    put("senderAvatar", sender.profilePicture)
    put("type", MessageType.from(source.type).raw)
    put("createdAt", FieldValue.serverTimestamp())
    put("clientMessageId", "android_$messageId")
    put("status", MessageStatus.Sent.raw)
    val original = source.forwardedFrom ?: ForwardedFrom(source.senderId, source.senderName)
    put("forwardedFrom", mapOf("senderId" to original.senderId, "senderName" to original.senderName))
    source.text?.takeIf(String::isNotBlank)?.let { put("text", it) }
    source.image?.takeIf(String::isNotBlank)?.let { put("image", mapOf("url" to it)) }
    source.video?.let { put("video", mapOf("url" to it.url, "poster" to it.poster, "w" to it.w, "h" to it.h, "duration" to it.duration)) }
    source.file?.let { file ->
        put("file", buildMap<String, Any> {
            put("url", file.url)
            put("name", file.name)
            put("size", file.size)
            put("mime", file.mime)
            file.pages?.let { put("pages", it) }
        })
    }
    source.audioUrl?.takeIf(String::isNotBlank)?.let {
        put("audioUrl", it)
        source.duration?.let { duration -> put("duration", duration) }
        source.fileSize?.let { size -> put("fileSize", size) }
        source.mimeType?.let { mime -> put("mimeType", mime) }
    }
    require(keys.any { it in ForwardableContentKeys }) { "This message cannot be forwarded." }
}

internal fun deletedForEveryonePayload(): Map<String, Any?> = mapOf(
    "isDeletedForEveryone" to true,
    "text" to "This message was deleted",
    "image" to FieldValue.delete(),
    "video" to FieldValue.delete(),
    "file" to FieldValue.delete(),
    "audioUrl" to FieldValue.delete(),
    "duration" to FieldValue.delete(),
    "fileSize" to FieldValue.delete(),
    "mimeType" to FieldValue.delete(),
)

internal fun inboxBulkPayload(uid: String, operation: InboxBulkOperation): Map<String, Any?> = when (operation) {
    InboxBulkOperation.Pin -> mapOf("pinnedBy" to FieldValue.arrayUnion(uid))
    InboxBulkOperation.Unpin -> mapOf("pinnedBy" to FieldValue.arrayRemove(uid))
    InboxBulkOperation.MarkRead -> mapOf(
        "unreadBy" to FieldValue.arrayRemove(uid),
        "deletedBy" to FieldValue.arrayRemove(uid),
    )
    InboxBulkOperation.MarkUnread -> mapOf("unreadBy" to FieldValue.arrayUnion(uid))
    InboxBulkOperation.Mute -> mapOf("mutedBy" to FieldValue.arrayUnion(uid))
    InboxBulkOperation.Unmute -> mapOf("mutedBy" to FieldValue.arrayRemove(uid))
    InboxBulkOperation.Archive -> mapOf("archivedBy" to FieldValue.arrayUnion(uid))
    InboxBulkOperation.Restore -> mapOf("archivedBy" to FieldValue.arrayRemove(uid))
    InboxBulkOperation.Delete -> mapOf(
        "deletedBy" to FieldValue.arrayUnion(uid),
        "unreadBy" to FieldValue.arrayRemove(uid),
    )
}

private fun normalizedMessageIds(ids: List<String>): List<String> {
    val normalized = ids.map(String::trim).filter(String::isNotBlank).distinct()
    require(normalized.isNotEmpty()) { "Select at least one message." }
    require(normalized.size <= ChatRepository.MaxForwardMessages) { "Select up to ${ChatRepository.MaxForwardMessages} messages at once." }
    return normalized
}

private fun Message.forwardPreview(): String = when (MessageType.from(type)) {
    MessageType.Image -> "Photo"
    MessageType.Video -> "Video"
    MessageType.File -> file?.name?.takeIf(String::isNotBlank) ?: "File"
    MessageType.Voice -> "Voice message"
    MessageType.Text -> text?.take(120)?.takeIf(String::isNotBlank) ?: "Forwarded message"
}

private fun attachmentMessagePayload(
    sender: UserData,
    messageId: String,
    uploaded: CloudinaryResult,
    kind: AttachmentKind,
    fileName: String,
    fileSize: Long,
    caption: String?,
    replyTo: Message?,
): Map<String, Any?> = buildMap {
    val safeFileName = normalizedAttachmentDisplayName(fileName, "Document")
    put("senderId", sender.uid)
    put("senderName", sender.name.ifBlank { "Student" })
    put("senderAvatar", sender.profilePicture)
    explicitAttachmentMessageType(kind.type)?.let { put("type", it) }
    put("createdAt", FieldValue.serverTimestamp())
    put("clientMessageId", "android_$messageId")
    put("status", MessageStatus.Sent.raw)
    caption?.trim()?.takeIf(String::isNotBlank)?.let { put("text", it) }
    when (kind) {
        is AttachmentKind.Image -> put("image", mapOf("url" to uploaded.url, "w" to kind.width, "h" to kind.height))
        is AttachmentKind.Video -> put("video", mapOf("url" to uploaded.url, "w" to kind.width, "h" to kind.height, "duration" to (kind.durationMs ?: 0L)))
        is AttachmentKind.File -> {
            val fileMap = mutableMapOf<String, Any>("url" to uploaded.url, "name" to safeFileName, "size" to fileSize, "mime" to kind.mime)
            (uploaded.pages ?: kind.pages)?.let { fileMap["pages"] = it }
            put("file", fileMap)
        }
    }
    putAll(replyPayload(replyTo))
}

internal fun normalizedAttachmentDisplayName(name: String, fallback: String = "Document"): String =
    name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .take(180)
        .ifBlank { fallback.substringAfterLast('/').substringAfterLast('\\').trim().take(180).ifBlank { "Document" } }

private fun replyPayload(replyTo: Message?): Map<String, Any?> = replyTo?.let {
    mapOf(
        "replyToMessageId" to it.id,
        "replyToText" to it.replyPreviewText(),
        "replyToSenderName" to it.senderName,
        "replyToType" to it.replyPreviewType(),
    )
}.orEmpty()

private fun Message.replyPreviewType(): String = MessageType.from(type).raw

internal fun explicitAttachmentMessageType(type: String): String? =
    MessageType.from(type).raw.takeUnless { it == MessageType.Image.raw }

internal fun isValidMessageDocumentId(value: String): Boolean =
    value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

private fun Message.replyPreviewText(): String = text?.takeIf(String::isNotBlank)
    ?: when (MessageType.from(type)) {
        MessageType.Image -> "Photo"
        MessageType.Video -> "Video"
        MessageType.File -> file?.name ?: "File"
        MessageType.Voice -> "Voice message"
        MessageType.Text -> "Message"
    }

internal fun roomMetadataPayload(
    senderId: String,
    lastMessage: String,
    recipientIds: List<String>,
    lastMessageType: String? = null,
): Map<String, Any?> = buildMap {
    put("lastMessage", lastMessage)
    put("lastSenderId", senderId)
    put("updatedAt", FieldValue.serverTimestamp())
    put("deletedBy", FieldValue.arrayRemove(senderId))
    lastMessageType?.let { put("lastMessageType", it) }
    if (recipientIds.isNotEmpty()) {
        put("unreadBy", FieldValue.arrayUnion(*recipientIds.toTypedArray()))
    }
}

private class ChatConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)

private fun Map<String, Any?>.chatString(key: String, fallback: String = ""): String =
    get(key)?.toString()?.takeUnless { it == "null" } ?: fallback

private fun Map<String, Any?>.chatNullableString(key: String): String? =
    chatString(key).takeIf(String::isNotBlank)

private fun Map<String, Any?>.chatStringList(key: String): List<String> =
    (get(key) as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()

private fun Map<String, Any?>.chatLong(key: String): Long? = (get(key) as? Number)?.toLong()

private fun Map<String, Any?>.chatInt(key: String): Int? = (get(key) as? Number)?.toInt()

private fun Map<String, Any?>.chatTimestamp(key: String): Timestamp? = when (val value = get(key)) {
    is Timestamp -> value
    is Date -> Timestamp(value)
    is Number -> Timestamp(Date(value.toLong()))
    else -> null
}

private fun Map<String, Any?>.chatMap(key: String): Map<String, Any?> =
    (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

private fun Map<String, Any?>.chatReactions(key: String): Map<String, List<String>> =
    chatMap(key).mapValues { (_, value) ->
        (value as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()
    }

private fun Map<String, Any?>.chatTimestampMap(key: String): Map<String, Timestamp> =
    chatMap(key).mapNotNull { (mapKey, value) ->
        val timestamp = when (value) {
            is Timestamp -> value
            is Date -> Timestamp(value)
            is Number -> Timestamp(Date(value.toLong()))
            else -> null
        }
        timestamp?.let { mapKey to it }
    }.toMap()

private fun Map<String, Any?>.chatForwardedFrom(key: String): ForwardedFrom? = when (val value = get(key)) {
    is String -> value.takeIf(String::isNotBlank)?.let { ForwardedFrom(senderName = it) }
    is Map<*, *> -> ForwardedFrom(
        senderId = value.entries.firstOrNull { it.key?.toString() == "senderId" }?.value?.toString().orEmpty(),
        senderName = value.entries.firstOrNull { it.key?.toString() == "senderName" }?.value?.toString()?.takeIf(String::isNotBlank),
    ).takeIf { it.senderId.isNotBlank() || !it.senderName.isNullOrBlank() }
    else -> null
}

private val ForwardableContentKeys = setOf("text", "image", "video", "file", "audioUrl")
