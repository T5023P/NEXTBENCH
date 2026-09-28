package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.nextbench.data.model.Post
import com.nextbench.data.model.PostReply
import com.nextbench.data.model.UserData
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class PostDetailRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
    private val postCache: PostMemoryCache,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun loadPost(postId: String): Result<Post> = runCatching {
        require(postId.isNotBlank()) { "Missing post id." }
        ensureConfigured()

        val post = if (auth.currentUser == null) {
            postCache.get(postId) ?: functions.getDiscoveryFeed(mapOf("mode" to FeedMode.ForYou.raw))
                .toFeedPage()
                .also { postCache.putAll(it.posts) }
                .posts
                .firstOrNull { it.id == postId }
        } else {
            refs.post(postId).get().await().toPost()
        } ?: throw FirebaseFirestoreException(
            "Post no longer exists or is not publicly available.",
            FirebaseFirestoreException.Code.NOT_FOUND,
        )

        postCache.put(post)
        post
    }

    suspend fun loadReplies(postId: String): Result<List<PostReply>> = runCatching {
        ensureConfigured()
        requireNotNull(auth.currentUser?.uid) { "Sign in to read the conversation." }
        functions.getPostReplies(postId)
            .mapNotNull(Map<String, Any?>::toPostReply)
            .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
    }

    suspend fun createReply(
        post: Post,
        author: UserData,
        content: String,
        parent: PostReply? = null,
    ): Result<CreatedPostReply> = runCatching {
        ensureConfigured()
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to reply." }
        require(author.uid == uid) { "Your account changed. Try again." }
        require(author.verified) { "Verify your student identity before replying." }
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Write a reply first." }
        require(normalizedContent.length <= ReplyCharacterLimit) {
            "Replies can be up to $ReplyCharacterLimit characters."
        }
        require(parent == null || parent.postId == post.id) { "That reply belongs to another post." }

        val replyRef = refs.postReplies.document()
        val postRef = refs.post(post.id)
        val parentRef = parent?.let { refs.postReplies.document(it.id) }
        val now = Timestamp.now()
        val draft = NewPostReply(
            id = replyRef.id,
            postId = post.id,
            content = normalizedContent,
            authorId = uid,
            authorName = author.name.ifBlank { author.email.substringBefore('@').ifBlank { "Student" } },
            authorSchool = author.school.ifBlank { "Unknown School" },
            authorProfilePicture = author.profilePicture,
            parentId = parent?.id,
        )

        val counts = postRef.firestore.runTransaction { transaction ->
            val postSnapshot = transaction.get(postRef)
            if (!postSnapshot.exists()) throw FirebaseFirestoreException(
                "Post no longer exists.",
                FirebaseFirestoreException.Code.NOT_FOUND,
            )
            val parentSnapshot = parentRef?.let(transaction::get)
            if (parentRef != null && (parentSnapshot == null || !parentSnapshot.exists())) {
                throw FirebaseFirestoreException(
                    "The reply you selected no longer exists.",
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )
            }
            if (parentSnapshot?.getString("postId")?.let { it != post.id } == true) {
                throw IllegalArgumentException("That reply belongs to another post.")
            }

            val serverTime = FieldValue.serverTimestamp()
            val postReplyCount = (postSnapshot.getLong("repliesCount") ?: 0L) + 1L
            val parentReplyCount = parentSnapshot?.let {
                (it.getLong("repliesCount") ?: 0L) + 1L
            }
            transaction.set(replyRef, draft.toWriteData(serverTime))
            transaction.update(
                postRef,
                mapOf(
                    "repliesCount" to postReplyCount,
                    "updatedAt" to serverTime,
                ),
            )
            if (parentRef != null && parentSnapshot != null) {
                transaction.update(
                    parentRef,
                    mapOf(
                        "repliesCount" to requireNotNull(parentReplyCount),
                        "updatedAt" to serverTime,
                    ),
                )
            }
            ReplyWriteCounts(postReplyCount.toInt(), parentReplyCount?.toInt())
        }.await()

        val reply = draft.toModel(now)
        postCache.put(post.copy(repliesCount = counts.postRepliesCount))
        notificationScope.launch { notifyReplyAuthors(post, parent, reply) }
        CreatedPostReply(
            reply = reply,
            postRepliesCount = counts.postRepliesCount,
            parentRepliesCount = counts.parentRepliesCount,
        )
    }

    private suspend fun notifyReplyAuthors(post: Post, parent: PostReply?, reply: PostReply) {
        val requests = replyNotificationPayloads(post, parent, reply)
        supervisorScope {
            requests.map { payload ->
                async {
                    withTimeoutOrNull(NotificationTimeoutMillis) {
                        runCatching { functions.createNotification(payload) }
                    }
                }
            }.awaitAll()
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw PostDetailConfigurationException()
    }

    companion object {
        const val ReplyCharacterLimit = 1_000
        private const val NotificationTimeoutMillis = 2_500L
    }
}

data class CreatedPostReply(
    val reply: PostReply,
    val postRepliesCount: Int,
    val parentRepliesCount: Int?,
)

private data class ReplyWriteCounts(
    val postRepliesCount: Int,
    val parentRepliesCount: Int?,
)

internal data class NewPostReply(
    val id: String,
    val postId: String,
    val content: String,
    val authorId: String,
    val authorName: String,
    val authorSchool: String,
    val authorProfilePicture: String?,
    val parentId: String?,
)

internal fun NewPostReply.toWriteData(timestamp: Any): Map<String, Any?> = buildMap {
    put("postId", postId)
    put("content", content)
    put("authorId", authorId)
    put("authorName", authorName)
    put("authorSchool", authorSchool)
    put("authorProfilePicture", authorProfilePicture)
    put("upvotesCount", 0)
    put("repliesCount", 0)
    put("edited", false)
    put("createdAt", timestamp)
    put("updatedAt", timestamp)
    parentId?.let { put("parentId", it) }
}

internal fun NewPostReply.toModel(createdAt: Timestamp): PostReply = PostReply(
    id = id,
    postId = postId,
    content = content,
    authorId = authorId,
    authorName = authorName,
    authorSchool = authorSchool,
    authorProfilePicture = authorProfilePicture,
    parentId = parentId,
    createdAt = createdAt,
    updatedAt = createdAt,
)

internal fun replyNotificationPayloads(
    post: Post,
    parent: PostReply?,
    reply: PostReply,
): List<Map<String, Any?>> = buildList {
    if (post.authorId.isNotBlank() && post.authorId != reply.authorId) {
        add(
            notificationPayload(
                userId = post.authorId,
                title = "New comment",
                message = "${reply.authorName} commented on your post",
                postId = post.id,
            ),
        )
    }
    if (parent != null && parent.authorId.isNotBlank() &&
        parent.authorId != reply.authorId && parent.authorId != post.authorId
    ) {
        add(
            notificationPayload(
                userId = parent.authorId,
                title = "New reply",
                message = "${reply.authorName} replied to your comment",
                postId = post.id,
            ),
        )
    }
}

private fun notificationPayload(
    userId: String,
    title: String,
    message: String,
    postId: String,
): Map<String, Any?> = mapOf(
    "userId" to userId,
    "type" to "new_post",
    "title" to title,
    "message" to message,
    "link" to "/post/$postId",
    "postId" to postId,
)

internal fun Map<String, Any?>.toPostReply(): PostReply? {
    val id = replyString("id")
    val postId = replyString("postId")
    if (id.isBlank() || postId.isBlank()) return null
    return PostReply(
        id = id,
        postId = postId,
        content = replyString("content"),
        authorId = replyString("authorId"),
        authorName = replyString("authorName"),
        authorSchool = replyString("authorSchool"),
        authorProfilePicture = replyNullableString("authorProfilePicture"),
        imageUrl = replyNullableString("imageUrl"),
        parentId = replyNullableString("parentId"),
        upvotesCount = replyInt("upvotesCount"),
        repliesCount = replyInt("repliesCount"),
        edited = this["edited"] as? Boolean ?: false,
        createdAt = replyTimestamp("createdAt"),
        updatedAt = replyTimestamp("updatedAt"),
    )
}

private fun DocumentSnapshot.toPost(): Post? =
    data?.plus("id" to id)?.toPost()

private fun Map<String, Any?>.replyString(key: String): String =
    get(key)?.toString()?.takeUnless { it == "null" } ?: ""

private fun Map<String, Any?>.replyNullableString(key: String): String? =
    replyString(key).takeIf(String::isNotBlank)

private fun Map<String, Any?>.replyInt(key: String): Int =
    (get(key) as? Number)?.toInt() ?: 0

private fun Map<String, Any?>.replyTimestamp(key: String): Timestamp? = when (val value = get(key)) {
    is Timestamp -> value
    is Date -> Timestamp(value)
    is Number -> Timestamp(Date(value.toLong()))
    else -> null
}

private class PostDetailConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
