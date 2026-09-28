package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.Timestamp
import com.nextbench.data.model.Club
import com.nextbench.data.model.ClubSettings
import com.nextbench.data.model.FileAttachment
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageStatus
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VideoAttachment
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@Singleton
class ClubRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
    private val uploader: CloudinaryUploader,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()

    fun observeMemberClubs(uid: String): Flow<List<Club>> = configuredFlow(uid) {
        refs.clubs
            .whereArrayContains("memberIds", uid)
            .snapshotFlow()
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { it.toClub() }
                    .sortedByDescending { it.clubActivityMillis() }
            }
    }

    fun observePublicClubs(uid: String, school: String?, city: String?): Flow<List<Club>> = configuredFlow(uid) {
        refs.clubs
            .whereEqualTo("type", "public")
            .limit(PublicClubQueryLimit)
            .snapshotFlow()
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { it.toClub() }
                    .filterNot { uid in it.memberIds }
                    .sortedWith(
                        compareByDescending<Club> { it.school.isNotBlank() && it.school.equals(school.orEmpty(), ignoreCase = true) }
                            .thenByDescending { it.city?.equals(city.orEmpty(), ignoreCase = true) == true }
                            .thenByDescending { it.memberCount }
                            .thenByDescending { it.clubActivityMillis() },
                    )
                    .take(PublicClubLimit)
            }
    }

    fun observeClub(clubId: String, uid: String): Flow<Club?> = configuredFlow(uid) {
        refs.club(clubId).snapshotFlow().map { it.toClub() }
    }

    suspend fun loadPublicMembers(uid: String, memberIds: List<String>): Result<List<UserData>> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        functions.getPublicUsers(memberIds).mapNotNull(Map<String, Any?>::toPublicUser)
    }

    /**
     * The club transcript the viewer is allowed to see. Blocked members' messages are removed here
     * (see [visibleClubMessages]) because a club message has no single recipient and Firestore can
     * only allow or deny a whole club-message list. The same block relationship is enforced
     * server-side for push by `functions-android`, which is why this is a read filter and not the
     * only line of defence.
     */
    fun observeMessages(clubId: String, uid: String): Flow<List<Message>> = configuredFlow(uid) {
        combine(
            refs.clubMessages(clubId)
                .orderBy("createdAt")
                .limitToLast(ClubMessageWindowSize)
                .snapshotFlow()
                .map { snapshot -> snapshot.documents.mapNotNull { it.toMessage() } },
            refs.blocks.whereEqualTo("blockerId", uid).snapshotFlow().map { snapshot ->
                snapshot.documents.mapNotNull { it.getString("blockedId") }
            },
            refs.blocks.whereEqualTo("blockedId", uid).snapshotFlow().map { snapshot ->
                snapshot.documents.mapNotNull { it.getString("blockerId") }
            },
        ) { messages, blockedOutgoing, blockedIncoming ->
            visibleClubMessages(messages, (blockedOutgoing + blockedIncoming).toSet(), uid)
        }
    }

    suspend fun createClub(
        creator: UserData,
        name: String,
        description: String,
        type: String,
    ): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(creator.uid)
        require(creator.verified) {
            "Verify your student account before creating a club."
        }
        val normalizedName = name.trim()
        val normalizedDescription = description.trim()
        require(normalizedName.length in 2..100) { "Club names must be between 2 and 100 characters." }
        require(normalizedDescription.length <= 500) { "Club descriptions can be up to 500 characters." }
        require(type == "public" || type == "private") { "Choose a valid club visibility." }

        val clubRef = refs.clubs.document()
        clubRef.set(
            clubCreationPayload(
                creator = creator,
                name = normalizedName,
                description = normalizedDescription,
                type = type,
                inviteCode = generateClubInviteCode(),
            ),
        ).await()
        clubRef.id
    }

    suspend fun joinByInviteCode(uid: String, code: String): Result<Club?> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val normalized = code.trim()
        require(normalized.isNotBlank()) { "Enter a club invite code first." }
        val query = refs.clubs.whereEqualTo("inviteCode", normalized).limit(1).get().await()
        val clubSnapshot = query.documents.firstOrNull() ?: return@runCatching null
        val clubRef = clubSnapshot.reference
        refs.clubs.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(clubRef)
            val club = snapshot.toClub() ?: return@runTransaction null
            if (uid in club.memberIds) return@runTransaction club
            transaction.update(clubRef, clubJoinUpdatePayload(uid))
            club.copy(memberIds = club.memberIds + uid, memberCount = club.memberCount + 1)
        }.await()
    }

    suspend fun findByInviteCode(uid: String, code: String): Result<Club?> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val normalized = code.trim()
        require(normalized.isNotBlank()) { "Enter a club invite code first." }
        refs.clubs.whereEqualTo("inviteCode", normalized).limit(1).get().await().documents.firstOrNull()?.toClub()
    }

    suspend fun joinPublicClub(uid: String, clubId: String): Result<Club?> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        refs.clubs.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(clubRef)
            val club = snapshot.toClub() ?: return@runTransaction null
            require(club.type.equals("public", ignoreCase = true)) { "This club is invite-only." }
            if (uid in club.memberIds) return@runTransaction club
            transaction.update(clubRef, clubJoinUpdatePayload(uid))
            club.copy(memberIds = club.memberIds + uid, memberCount = club.memberCount + 1)
        }.await()
    }

    suspend fun leaveClub(uid: String, clubId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        refs.clubs.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(clubRef)
            val club = snapshot.toClub() ?: error("This club is no longer available.")
            require(uid in club.memberIds) { "You are not a member of this club." }
            require(uid != club.leadId) { "Transfer leadership before leaving the club." }
            transaction.update(clubRef, clubLeaveUpdatePayload(uid, club.memberCount))
        }.await()
    }

    suspend fun updateSettings(uid: String, clubId: String, settings: ClubSettings): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId) { "Only the club lead can change shared settings." }
        clubRef.update(
            mapOf(
                "settings.hideMembersAbove50" to settings.hideMembersAbove50,
                "settings.onlyLeadsCanPost" to settings.onlyLeadsCanPost,
                "settings.slowMode" to settings.slowMode.coerceIn(0, 300),
                "settings.muteNotifications" to settings.muteNotifications,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun updateVisibility(uid: String, clubId: String, type: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(type == "public" || type == "private") { "Choose a valid club visibility." }
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId) { "Only the club lead can change shared settings." }
        clubRef.update(
            mapOf(
                "type" to type,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun promoteCoLead(uid: String, clubId: String, targetId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId) { "Only the club lead can manage roles." }
        require(targetId in club.memberIds) { "Choose a current club member." }
        require(targetId != club.leadId) { "The club lead already has the highest role." }
        clubRef.update(clubRoleUpdatePayload(targetId, RoleUpdate.Promote)).await()
    }

    suspend fun demoteCoLead(uid: String, clubId: String, targetId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId) { "Only the club lead can manage roles." }
        require(targetId in club.coLeadIds) { "That member is not a co-lead." }
        clubRef.update(clubRoleUpdatePayload(targetId, RoleUpdate.Demote)).await()
    }

    suspend fun transferLeadership(uid: String, clubId: String, targetId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId) { "Only the club lead can transfer leadership." }
        require(targetId in club.memberIds) { "Choose a current club member." }
        require(targetId != uid) { "Choose another member to receive leadership." }
        clubRef.update(clubLeadershipTransferPayload(targetId)).await()
    }

    suspend fun removeMember(uid: String, clubId: String, targetId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(uid == club.leadId || uid in club.coLeadIds) { "Only club leads can remove members." }
        require(targetId in club.memberIds) { "That member is no longer in the club." }
        require(targetId != club.leadId) { "The club lead cannot be removed." }
        require(targetId != uid) { "Use Leave club to remove yourself." }
        require(uid == club.leadId || targetId !in club.coLeadIds) { "Only the club lead can remove a co-lead." }
        clubRef.update(clubMemberRemovalPayload(targetId)).await()
    }

    suspend fun sendText(clubId: String, sender: UserData, text: String, replyTo: Message? = null): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Write a message first." }
        require(normalized.length <= ChatRepository.MessageCharacterLimit) {
            "Messages can be up to ${ChatRepository.MessageCharacterLimit} characters."
        }

        val clubRef = refs.club(clubId)
        val club = clubRef.get().await().toClub() ?: error("This club is no longer available.")
        require(sender.uid in club.memberIds) { "Join this club before posting." }
        val isLead = sender.uid == club.leadId || sender.uid in club.coLeadIds
        require(!club.settings.onlyLeadsCanPost || isLead) { "Only club leads can post right now." }

        val messageRef = refs.clubMessages(clubId).document()
        val messagePayload = textMessagePayload(sender, messageRef.id, normalized, replyTo)
        val recipients = club.memberIds.filter { it != sender.uid && it.isNotBlank() }
        val batch = clubRef.firestore.batch()
        batch.set(messageRef, messagePayload)
        batch.update(clubRef, clubMessageMetadataPayload(sender, normalized, recipients))
        batch.commit().await()

        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            text = normalized,
            type = MessageType.Text.raw,
            createdAt = com.google.firebase.Timestamp.now(),
            clientMessageId = "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.clubReplyPreview(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.type,
        )
    }

    suspend fun sendImage(clubId: String, sender: UserData, file: File, width: Int, height: Int, caption: String?, replyTo: Message?): Result<Message> =
        sendAttachment(clubId, sender, file, caption = caption, replyTo = replyTo, kind = ClubAttachmentKind.Image(width, height))

    suspend fun sendVideo(clubId: String, sender: UserData, file: File, width: Int, height: Int, durationMs: Long?, caption: String?, replyTo: Message?): Result<Message> =
        sendAttachment(clubId, sender, file, caption = caption, replyTo = replyTo, kind = ClubAttachmentKind.Video(width, height, durationMs))

    suspend fun sendFile(clubId: String, sender: UserData, file: File, displayName: String, mimeType: String, caption: String?, replyTo: Message?): Result<Message> =
        sendAttachment(clubId, sender, file, displayName = displayName, caption = caption, replyTo = replyTo, kind = ClubAttachmentKind.File(mimeType))

    suspend fun sendVoice(clubId: String, sender: UserData, file: File, durationSeconds: Long, mimeType: String, replyTo: Message?): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        voiceMessageValidationError(durationSeconds, file.length(), mimeType)?.let { throw IllegalArgumentException(it) }
        val club = refs.club(clubId).get().await().toClub() ?: error("This club is no longer available.")
        require(sender.uid in club.memberIds) { "Join this club before posting." }
        val lead = sender.uid == club.leadId || sender.uid in club.coLeadIds
        require(!club.settings.onlyLeadsCanPost || lead) { "Only club leads can post right now." }
        val uploaded = uploader.upload(file, "nextbench/club_voice/$clubId", CloudinaryResourceType.Video)
        val messageRef = refs.clubMessages(clubId).document()
        val payload = voiceMessagePayload(sender, messageRef.id, uploaded.url, durationSeconds, file.length(), mimeType, replyTo)
        val recipients = club.memberIds.filter { it != sender.uid && it.isNotBlank() }
        val batch = refs.club(clubId).firestore.batch()
        batch.set(messageRef, payload)
        batch.update(refs.club(clubId), clubMessageMetadataPayload(sender, "Voice message", recipients))
        batch.commit().await()
        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            type = MessageType.Voice.raw,
            audioUrl = uploaded.url,
            duration = durationSeconds,
            fileSize = file.length(),
            mimeType = mimeType,
            createdAt = Timestamp.now(),
            clientMessageId = "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.clubReplyPreview(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.type,
        )
    }

    suspend fun toggleReaction(clubId: String, messageId: String, uid: String, emoji: String): Result<Boolean> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(emoji.length <= 16) { "That reaction is not supported." }
        requireClubMember(clubId, uid)
        val ref = refs.clubMessages(clubId).document(messageId)
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

    suspend fun deleteForMe(clubId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        requireClubMember(clubId, uid)
        refs.clubMessages(clubId).document(messageId).update("deletedFor", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun deleteForEveryone(clubId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        requireClubMember(clubId, uid)
        val ref = refs.clubMessages(clubId).document(messageId)
        val message = ref.get().await().toMessage()
            ?: throw FirebaseFirestoreException("This message is no longer available.", FirebaseFirestoreException.Code.NOT_FOUND)
        require(message.senderId == uid) { "Only the sender can delete this message for everyone." }
        ref.update(clubDeletedForEveryonePayload()).await()
    }

    suspend fun markMessageRead(clubId: String, messageId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        if (isClubMember(clubId, uid)) refs.clubMessages(clubId).document(messageId).update("readBy", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun markRead(clubId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        refs.club(clubId).update(
            mapOf(
                "unreadBy" to FieldValue.arrayRemove(uid),
                "deletedBy" to FieldValue.arrayRemove(uid),
            ),
        ).await()
    }

    suspend fun setTyping(clubId: String, uid: String, typing: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        requireClubMember(clubId, uid)
        refs.club(clubId).update(
            "typingUsers.$uid",
            if (typing) FieldValue.serverTimestamp() else FieldValue.delete(),
        ).await()
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private suspend fun isClubMember(clubId: String, uid: String): Boolean =
        refs.club(clubId).get().await().toClub()?.memberIds?.contains(uid) == true

    private suspend fun requireClubMember(clubId: String, uid: String) {
        require(isClubMember(clubId, uid)) { "You are not a member of this club." }
    }

    private suspend fun sendAttachment(
        clubId: String,
        sender: UserData,
        file: File,
        displayName: String = file.name,
        caption: String?,
        replyTo: Message?,
        kind: ClubAttachmentKind,
    ): Result<Message> = runCatching {
        ensureConfigured()
        requireAuthenticated(sender.uid)
        require(file.isFile && file.length() > 0L) { "Choose a valid attachment." }
        val club = refs.club(clubId).get().await().toClub() ?: error("This club is no longer available.")
        require(sender.uid in club.memberIds) { "Join this club before posting." }
        val lead = sender.uid == club.leadId || sender.uid in club.coLeadIds
        require(!club.settings.onlyLeadsCanPost || lead) { "Only club leads can post right now." }
        val uploaded = when (kind) {
            is ClubAttachmentKind.Image -> uploader.upload(file, "nextbench/club_images/$clubId", CloudinaryResourceType.Image)
            is ClubAttachmentKind.Video -> uploader.upload(file, "nextbench/club_videos/$clubId", CloudinaryResourceType.Video)
            is ClubAttachmentKind.File -> uploader.upload(file, "nextbench/club_files/$clubId", if (kind.mime == "application/pdf") CloudinaryResourceType.Image else CloudinaryResourceType.Raw)
        }
        val messageRef = refs.clubMessages(clubId).document()
        val payload = clubAttachmentPayload(sender, messageRef.id, uploaded, kind, displayName, file.length(), caption, replyTo)
        val preview = caption?.trim().takeUnless { it.isNullOrBlank() } ?: kind.label
        val recipients = club.memberIds.filter { it != sender.uid && it.isNotBlank() }
        val batch = refs.club(clubId).firestore.batch()
        batch.set(messageRef, payload)
        batch.update(refs.club(clubId), clubMessageMetadataPayload(sender, preview, recipients))
        batch.commit().await()
        Message(
            id = messageRef.id,
            senderId = sender.uid,
            senderName = sender.name.ifBlank { "Student" },
            senderAvatar = sender.profilePicture,
            text = caption?.trim()?.takeIf(String::isNotBlank),
            image = uploaded.url.takeIf { kind is ClubAttachmentKind.Image },
            video = (kind as? ClubAttachmentKind.Video)?.let { VideoAttachment(uploaded.url, w = it.width, h = it.height, duration = it.durationMs ?: 0L) },
            file = (kind as? ClubAttachmentKind.File)?.let { FileAttachment(uploaded.url, displayName, file.length(), it.mime, uploaded.pages) },
            type = kind.type,
            createdAt = com.google.firebase.Timestamp.now(),
            clientMessageId = "android_${messageRef.id}",
            status = MessageStatus.Sent.raw,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.clubReplyPreview(),
            replyToSenderName = replyTo?.senderName,
            replyToType = replyTo?.type,
        )
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ClubConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }

    companion object {
        internal const val PublicClubLimit = 20
        internal const val PublicClubQueryLimit = 50L
        internal const val ClubMessageWindowSize = 100L
    }
}

internal sealed interface ClubAttachmentKind {
    val type: String
    val label: String
    data class Image(val width: Int, val height: Int) : ClubAttachmentKind { override val type = MessageType.Image.raw; override val label = "Photo" }
    data class Video(val width: Int, val height: Int, val durationMs: Long?) : ClubAttachmentKind { override val type = MessageType.Video.raw; override val label = "Video" }
    data class File(val mime: String) : ClubAttachmentKind { override val type = MessageType.File.raw; override val label = "Document" }
}

internal fun clubAttachmentPayload(
    sender: UserData,
    messageId: String,
    uploaded: CloudinaryResult,
    kind: ClubAttachmentKind,
    fileName: String,
    fileSize: Long,
    caption: String?,
    replyTo: Message?,
): Map<String, Any?> = buildMap {
    put("senderId", sender.uid)
    put("senderName", sender.name.ifBlank { "Student" })
    put("senderAvatar", sender.profilePicture)
    explicitAttachmentMessageType(kind.type)?.let { put("type", it) }
    put("createdAt", FieldValue.serverTimestamp())
    put("clientMessageId", "android_$messageId")
    put("status", MessageStatus.Sent.raw)
    caption?.trim()?.takeIf(String::isNotBlank)?.let { put("text", it) }
    when (kind) {
        is ClubAttachmentKind.Image -> put("image", mapOf("url" to uploaded.url, "w" to kind.width, "h" to kind.height))
        is ClubAttachmentKind.Video -> put("video", mapOf("url" to uploaded.url, "w" to kind.width, "h" to kind.height, "duration" to (kind.durationMs ?: 0L)))
        is ClubAttachmentKind.File -> put("file", buildMap<String, Any> {
            put("url", uploaded.url)
            put("name", fileName)
            put("size", fileSize)
            put("mime", kind.mime)
            uploaded.pages?.let { put("pages", it) }
        })
    }
    replyTo?.let {
        put("replyToMessageId", it.id)
        put("replyToText", it.clubReplyPreview())
        put("replyToSenderName", it.senderName)
        put("replyToType", it.type)
    }
}

internal fun clubDeletedForEveryonePayload(): Map<String, Any?> = mapOf(
    "isDeletedForEveryone" to true,
    "text" to "This message was deleted",
    "image" to FieldValue.delete(),
    "video" to FieldValue.delete(),
    "file" to FieldValue.delete(),
    "audioUrl" to FieldValue.delete(),
)

private fun Message.clubReplyPreview(): String = text?.takeIf(String::isNotBlank)
    ?: when (MessageType.from(type)) {
        MessageType.Image -> "Photo"
        MessageType.Video -> "Video"
        MessageType.File -> file?.name ?: "File"
        MessageType.Voice -> "Voice message"
        MessageType.Text -> "Message"
    }

internal fun clubJoinUpdatePayload(uid: String): Map<String, Any?> = mapOf(
    "memberIds" to FieldValue.arrayUnion(uid),
    "memberCount" to FieldValue.increment(1),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun clubLeaveUpdatePayload(uid: String, memberCount: Int): Map<String, Any?> = mapOf(
    "memberIds" to FieldValue.arrayRemove(uid),
    "coLeadIds" to FieldValue.arrayRemove(uid),
    "memberCount" to (memberCount - 1).coerceAtLeast(0),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal enum class RoleUpdate { Promote, Demote }

internal fun clubRoleUpdatePayload(targetId: String, update: RoleUpdate): Map<String, Any?> = mapOf(
    "coLeadIds" to when (update) {
        RoleUpdate.Promote -> FieldValue.arrayUnion(targetId)
        RoleUpdate.Demote -> FieldValue.arrayRemove(targetId)
    },
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun clubLeadershipTransferPayload(newLeadId: String): Map<String, Any?> = mapOf(
    "leadId" to newLeadId,
    "coLeadIds" to FieldValue.arrayRemove(newLeadId),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun clubMemberRemovalPayload(targetId: String): Map<String, Any?> = mapOf(
    "memberIds" to FieldValue.arrayRemove(targetId),
    "coLeadIds" to FieldValue.arrayRemove(targetId),
    "memberCount" to FieldValue.increment(-1),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun clubMessageMetadataPayload(
    sender: UserData,
    lastMessage: String,
    recipientIds: List<String>,
): Map<String, Any?> = buildMap {
    put("lastMessage", lastMessage)
    put("lastSenderId", sender.uid)
    put("lastSenderName", sender.name.ifBlank { "Student" })
    put("updatedAt", FieldValue.serverTimestamp())
    if (recipientIds.isNotEmpty()) {
        put("unreadBy", FieldValue.arrayUnion(*recipientIds.toTypedArray()))
    }
}

/**
 * Club messages the viewer may see.
 *
 * A club message has no single recipient, so `firestore.rules` cannot express "hide messages from a
 * member I blocked" for the club-message list — a rule can only allow or deny the whole query. The
 * invariant is therefore enforced here for reads: the viewer's own messages are never hidden by
 * their own blocks (a block is one-directional in intent even though the document is not), and
 * messages the viewer deleted for themselves stay hidden. Sorting is preserved so the transcript
 * order is unchanged for the messages that remain.
 */
internal fun visibleClubMessages(
    messages: List<Message>,
    blockedSenderIds: Set<String>,
    viewerId: String,
): List<Message> = messages
    .filterNot { viewerId in it.deletedFor }
    .filterNot { it.senderId != viewerId && it.senderId in blockedSenderIds }
    .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

internal fun Club.clubActivityMillis(): Long =
    updatedAt?.toDate()?.time ?: createdAt?.toDate()?.time ?: Long.MIN_VALUE

internal fun normalizeClubInviteCode(value: String): String =
    value.filterNot(Char::isWhitespace).take(ClubInviteCodeLength)

internal fun clubCreationPayload(
    creator: UserData,
    name: String,
    description: String,
    type: String,
    inviteCode: String,
): Map<String, Any?> = mapOf(
    "name" to name.trim(),
    "description" to description.trim(),
    "avatar" to null,
    "type" to type,
    "inviteCode" to inviteCode,
    "leadId" to creator.uid,
    "coLeadIds" to emptyList<String>(),
    "memberIds" to listOf(creator.uid),
    "settings" to mapOf(
        "hideMembersAbove50" to false,
        "onlyLeadsCanPost" to false,
        "slowMode" to 0,
        "muteNotifications" to false,
    ),
    "memberCount" to 1,
    "lastMessage" to "",
    "lastSenderId" to "",
    "lastSenderName" to "",
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
    "school" to creator.school.trim().ifBlank { null },
    "city" to creator.city.trim().ifBlank { null },
    "tags" to emptyList<String>(),
    "unreadBy" to emptyList<String>(),
    "mutedBy" to emptyList<String>(),
    "archivedBy" to emptyList<String>(),
    "pinnedBy" to emptyList<String>(),
    "deletedBy" to emptyList<String>(),
    "typingUsers" to emptyMap<String, Any>(),
)

internal fun generateClubInviteCode(random: kotlin.random.Random = kotlin.random.Random.Default): String =
    buildString(ClubInviteCodeLength) {
        repeat(ClubInviteCodeLength) { append(ClubInviteAlphabet[random.nextInt(ClubInviteAlphabet.length)]) }
    }

internal const val ClubInviteCodeLength = 8
private const val ClubInviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

private class ClubConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
