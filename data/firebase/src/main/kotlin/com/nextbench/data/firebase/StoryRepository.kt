package com.nextbench.data.firebase

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.nextbench.data.model.Story
import com.nextbench.data.model.StoryMediaType
import com.nextbench.data.model.StoryPrivacy
import com.nextbench.data.model.StoryTrayEntry
import com.nextbench.data.model.UserData
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

data class StoryMediaDraft(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
)

@Singleton
class StoryRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refs: FirestoreRefs,
    private val storageProvider: Provider<FirebaseStorage>,
) {
    // Resolved lazily so an unconfigured build fails in ensureConfigured() with user copy,
    // never inside dependency injection (see FirestoreRefs for the full rationale).
    private val auth get() = authProvider.get()
    private val storage get() = storageProvider.get()

    suspend fun loadTray(): Result<List<StoryTrayEntry>> = runCatching {
        ensureConfigured()
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to view stories." }
        val followingIds = refs.follows
            .whereEqualTo("followerId", uid)
            .limit(MaxFollowing)
            .get()
            .await()
            .documents
            .mapNotNull { it.getString("followingId") }
        val stories = fetchActiveStories(listOf(uid) + followingIds)
        val seen = refs.storySeen(uid).get().await().data?.get("seen").toSeenState()
        assembleStoryTray(uid, stories, seen)
    }

    suspend fun markSeen(entry: StoryTrayEntry): Result<Unit> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to view stories." }
        val latest = entry.stories.maxByOrNull(Story::createdAt) ?: return@runCatching
        refs.storySeen(uid).set(
            mapOf(
                "seen" to mapOf(
                    entry.authorId to mapOf(
                        "lastSeenAt" to Timestamp(Date(latest.createdAt)),
                        "lastSeenStoryId" to latest.id,
                    ),
                ),
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }

    suspend fun recordView(story: Story): Result<Unit> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to view stories." }
        if (uid == story.authorId) return@runCatching
        val view = refs.storyViews(story.id).document(uid)
        if (view.get().await().exists()) {
            view.update("lastViewedAt", FieldValue.serverTimestamp()).await()
        } else {
            view.set(
                mapOf(
                    "viewerId" to uid,
                    "firstViewedAt" to FieldValue.serverTimestamp(),
                    "lastViewedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }

    suspend fun hasLiked(storyId: String): Result<Boolean> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to like stories." }
        refs.storyLikes(storyId).document(uid).get().await().exists()
    }

    suspend fun toggleLike(storyId: String): Result<Boolean> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to like stories." }
        val like = refs.storyLikes(storyId).document(uid)
        if (like.get().await().exists()) {
            like.delete().await()
            false
        } else {
            like.set(mapOf("userId" to uid, "createdAt" to FieldValue.serverTimestamp())).await()
            true
        }
    }

    suspend fun reply(storyId: String, username: String, content: String): Result<Unit> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to reply to stories." }
        val body = content.trim()
        require(body.isNotBlank()) { "Write a reply first." }
        require(body.length <= MaxReplyLength) { "Story replies can be up to $MaxReplyLength characters." }
        refs.storyReplies(storyId).add(
            mapOf(
                "storyId" to storyId,
                "authorId" to uid,
                "authorUsername" to username.take(MaxUsernameLength),
                "content" to body,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun publish(
        user: UserData,
        draft: StoryMediaDraft,
        privacy: StoryPrivacy,
    ): Result<Story> = runCatching {
        ensureConfigured()
        val firebaseUser = requireNotNull(auth.currentUser) { "Sign in to share a story." }
        require(firebaseUser.uid == user.uid) { "Your session changed. Try again." }
        require(user.verified) { "Verify your profile before sharing stories." }
        require(draft.file.exists() && draft.file.length() > 0L) { "Choose a valid photo or video." }

        val mediaType = if (draft.mimeType.startsWith("video/")) StoryMediaType.Video else StoryMediaType.Image
        val maxBytes = if (mediaType == StoryMediaType.Video) MaxVideoBytes else MaxImageBytes
        require(draft.file.length() <= maxBytes) {
            if (mediaType == StoryMediaType.Video) "Choose a video smaller than 100 MB." else "Choose an image smaller than 15 MB."
        }
        val storyRef = refs.stories.document()
        val extension = draft.file.extension.ifBlank { if (mediaType == StoryMediaType.Video) "mp4" else "jpg" }
        val mediaPath = "stories/${user.uid}/${storyRef.id}/media.$extension"
        val mediaRef = storage.reference.child(mediaPath)
        mediaRef.putFile(Uri.fromFile(draft.file)).await()
        val mediaUrl = mediaRef.downloadUrl.await().toString()
        val now = System.currentTimeMillis()
        val payload = mutableMapOf<String, Any?>(
            "authorId" to user.uid,
            "authorUsername" to (user.username?.takeIf(String::isNotBlank) ?: user.name),
            "authorPhotoURL" to user.profilePicture,
            "mediaType" to mediaType.raw,
            "mediaUrl" to mediaUrl,
            "mediaPath" to mediaPath,
            "posterUrl" to null,
            "posterPath" to null,
            "width" to draft.width,
            "height" to draft.height,
            "layers" to emptyList<Map<String, Any>>(),
            "privacy" to privacy.raw,
            "status" to "active",
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(now + StoryTtlMs)),
        )
        draft.durationMs?.let { payload["durationMs"] = it }
        try {
            storyRef.set(payload).await()
        } catch (error: Exception) {
            runCatching { mediaRef.delete().await() }
            throw error
        }
        Story(
            id = storyRef.id,
            authorId = user.uid,
            authorUsername = payload["authorUsername"].toString(),
            authorPhotoURL = user.profilePicture,
            mediaType = mediaType.raw,
            mediaUrl = mediaUrl,
            mediaPath = mediaPath,
            width = draft.width,
            height = draft.height,
            durationMs = draft.durationMs,
            privacy = privacy.raw,
            createdAt = now,
            expiresAt = now + StoryTtlMs,
        )
    }

    suspend fun delete(story: Story): Result<Unit> = runCatching {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to delete stories." }
        require(uid == story.authorId) { "Only the story owner can delete it." }
        refs.story(story.id).delete().await()
        runCatching { storage.reference.child(story.mediaPath).delete().await() }
    }

    private suspend fun fetchActiveStories(authorIds: List<String>): List<Story> {
        val cutoff = Timestamp(Date(System.currentTimeMillis() - StoryTtlMs))
        return authorIds.distinct().filter(String::isNotBlank).chunked(FirestoreInLimit).flatMap { ids ->
            refs.stories
                .whereIn("authorId", ids)
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(MaxStoriesPerChunk)
                .get()
                .await()
                .documents
                .mapNotNull(DocumentSnapshot::toStory)
        }.filter { it.status == "active" && it.expiresAt > System.currentTimeMillis() }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw StoryConfigurationException()
    }

    companion object {
        const val StoryTtlMs = 24L * 60L * 60L * 1_000L
        const val MaxReplyLength = 500
        private const val MaxUsernameLength = 100
        private const val FirestoreInLimit = 10
        private const val MaxFollowing = 200L
        private const val MaxStoriesPerChunk = 100L
        private const val MaxImageBytes = 15L * 1024L * 1024L
        private const val MaxVideoBytes = 100L * 1024L * 1024L
    }
}

internal data class StorySeenEntry(val lastSeenAt: Long, val lastSeenStoryId: String)

internal fun assembleStoryTray(
    currentUid: String,
    stories: List<Story>,
    seen: Map<String, StorySeenEntry>,
): List<StoryTrayEntry> = stories
    .groupBy(Story::authorId)
    .mapNotNull { (authorId, authorStories) ->
        val ordered = authorStories.sortedBy(Story::createdAt)
        val latest = ordered.lastOrNull() ?: return@mapNotNull null
        StoryTrayEntry(
            authorId = authorId,
            authorUsername = latest.authorUsername,
            authorPhotoURL = latest.authorPhotoURL,
            latestStoryId = latest.id,
            latestCreatedAt = latest.createdAt,
            allSeen = latest.createdAt <= (seen[authorId]?.lastSeenAt ?: 0L),
            stories = ordered,
        )
    }
    .sortedWith(
        compareByDescending<StoryTrayEntry> { it.authorId == currentUid }
            .thenBy { it.allSeen }
            .thenByDescending(StoryTrayEntry::latestCreatedAt),
    )

internal fun DocumentSnapshot.toStory(): Story? = data?.toStory(id)

internal fun Map<String, Any?>.toStory(id: String): Story? {
    val authorId = this["authorId"]?.toString().orEmpty()
    val mediaUrl = this["mediaUrl"]?.toString().orEmpty()
    if (id.isBlank() || authorId.isBlank() || mediaUrl.isBlank()) return null
    val createdAt = storyMillis(this["createdAt"])
    return Story(
        id = id,
        authorId = authorId,
        authorUsername = this["authorUsername"]?.toString().orEmpty(),
        authorPhotoURL = this["authorPhotoURL"]?.toString()?.takeUnless { it == "null" || it.isBlank() },
        mediaType = StoryMediaType.from(this["mediaType"]?.toString()).raw,
        mediaUrl = mediaUrl,
        mediaPath = this["mediaPath"]?.toString().orEmpty(),
        posterUrl = this["posterUrl"]?.toString()?.takeUnless { it == "null" || it.isBlank() },
        posterPath = this["posterPath"]?.toString()?.takeUnless { it == "null" || it.isBlank() },
        width = (this["width"] as? Number)?.toInt() ?: 0,
        height = (this["height"] as? Number)?.toInt() ?: 0,
        durationMs = (this["durationMs"] as? Number)?.toLong(),
        layers = (this["layers"] as? List<*>)?.mapNotNull { layer ->
            (layer as? Map<*, *>)?.entries
                ?.mapNotNull { (key, value) -> value?.let { key.toString() to it } }
                ?.toMap()
        }.orEmpty(),
        privacy = StoryPrivacy.from(this["privacy"]?.toString()).raw,
        status = this["status"]?.toString() ?: "active",
        createdAt = createdAt,
        expiresAt = storyMillis(this["expiresAt"]).takeIf { it > 0L } ?: createdAt + StoryRepository.StoryTtlMs,
    )
}

private fun Any?.toSeenState(): Map<String, StorySeenEntry> =
    (this as? Map<*, *>)?.mapNotNull { (authorId, rawEntry) ->
        val entry = rawEntry as? Map<*, *> ?: return@mapNotNull null
        authorId?.toString()?.takeIf(String::isNotBlank)?.let { id ->
            id to StorySeenEntry(
                lastSeenAt = storyMillis(entry["lastSeenAt"]),
                lastSeenStoryId = entry["lastSeenStoryId"]?.toString().orEmpty(),
            )
        }
    }?.toMap().orEmpty()

private fun storyMillis(value: Any?): Long = when (value) {
    is Timestamp -> value.toDate().time
    is Date -> value.time
    is Number -> value.toLong()
    else -> 0L
}

private class StoryConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
