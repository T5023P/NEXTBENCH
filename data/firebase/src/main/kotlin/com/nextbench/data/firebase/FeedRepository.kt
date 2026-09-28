package com.nextbench.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.nextbench.data.model.Poll
import com.nextbench.data.model.Post
import com.nextbench.data.model.PostImage
import com.nextbench.data.model.Product
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

enum class FeedMode(val raw: String) {
    ForYou("for-you"),
    Following("following"),
}

data class FeedCursor(
    val postCreatedAt: Long? = null,
    val productCreatedAt: Long? = null,
    val cursorIndex: Int? = null,
)

data class FeedPage(
    val posts: List<Post>,
    val products: List<Product>,
    val order: List<FeedOrderEntry>,
    val nextCursor: FeedCursor,
    val hasMorePosts: Boolean,
)

data class FeedOrderEntry(
    val id: String,
    val type: String,
)

data class FeedInteractions(
    val upvoteDocumentIds: Map<String, String> = emptyMap(),
    val downvoteDocumentIds: Map<String, String> = emptyMap(),
    val saveDocumentIds: Map<String, String> = emptyMap(),
) {
    val upvotedPostIds: Set<String> get() = upvoteDocumentIds.keys
    val downvotedPostIds: Set<String> get() = downvoteDocumentIds.keys
    val savedPostIds: Set<String> get() = saveDocumentIds.keys
}

enum class PostVote { Up, Down }

data class VoteDocumentIds(
    val upvoteId: String? = null,
    val downvoteId: String? = null,
)

@Singleton
class FeedRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
    private val postCache: PostMemoryCache,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()

    suspend fun loadPage(
        mode: FeedMode,
        cursor: FeedCursor? = null,
    ): Result<FeedPage> = runCatching {
        ensureConfigured()
        val response = functions.getDiscoveryFeed(discoveryPayload(mode, cursor))
        response.toFeedPage().also { page -> postCache.putAll(page.posts) }
    }

    suspend fun loadInteractions(uid: String): Result<FeedInteractions> = runCatching {
        ensureConfigured()
        require(uid.isNotBlank()) { "Sign in to load post interactions." }
        coroutineScope {
            val queries = listOf(refs.postUpvotes, refs.postDownvotes, refs.savedPosts).map { collection ->
                async { collection.whereEqualTo("userId", uid).get().await() }
            }.awaitAll()
            FeedInteractions(
                upvoteDocumentIds = queries[0].documents.interactionDocumentIds(),
                downvoteDocumentIds = queries[1].documents.interactionDocumentIds(),
                saveDocumentIds = queries[2].documents.interactionDocumentIds(),
            )
        }
    }

    suspend fun setVote(
        postId: String,
        vote: PostVote?,
        currentUpvoteId: String?,
        currentDownvoteId: String?,
    ): Result<VoteDocumentIds> = runCatching {
        ensureConfigured()
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to vote on posts." }
        val postRef = refs.post(postId)
        val hadUpvote = currentUpvoteId != null
        val hadDownvote = currentDownvoteId != null
        val upvoteRef = refs.postUpvotes.document(currentUpvoteId ?: interactionId(uid, postId))
        val downvoteRef = refs.postDownvotes.document(currentDownvoteId ?: interactionId(uid, postId))
        postRef.firestore.runTransaction { transaction ->
            val post = transaction.get(postRef)
            if (!post.exists()) throw FirebaseFirestoreException(
                "Post no longer exists.",
                FirebaseFirestoreException.Code.NOT_FOUND,
            )
            val nextUpvotes = adjustedCount(
                current = post.getLong("upvotesCount")?.toInt() ?: 0,
                hadInteraction = hadUpvote,
                wantsInteraction = vote == PostVote.Up,
            )
            val nextDownvotes = adjustedCount(
                current = post.getLong("downvotesCount")?.toInt() ?: 0,
                hadInteraction = hadDownvote,
                wantsInteraction = vote == PostVote.Down,
            )

            when {
                vote == PostVote.Up && !hadUpvote -> transaction.set(
                    upvoteRef,
                    mapOf("userId" to uid, "postId" to postId, "createdAt" to FieldValue.serverTimestamp()),
                )
                vote != PostVote.Up && hadUpvote -> transaction.delete(upvoteRef)
            }
            when {
                vote == PostVote.Down && !hadDownvote -> transaction.set(
                    downvoteRef,
                    mapOf("userId" to uid, "postId" to postId, "createdAt" to FieldValue.serverTimestamp()),
                )
                vote != PostVote.Down && hadDownvote -> transaction.delete(downvoteRef)
            }
            transaction.update(
                postRef,
                mapOf(
                    "upvotesCount" to nextUpvotes,
                    "downvotesCount" to nextDownvotes,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }.await()
        VoteDocumentIds(
            upvoteId = upvoteRef.id.takeIf { vote == PostVote.Up },
            downvoteId = downvoteRef.id.takeIf { vote == PostVote.Down },
        )
    }

    suspend fun setSaved(
        postId: String,
        saved: Boolean,
        currentSaveId: String?,
    ): Result<String?> = runCatching {
        ensureConfigured()
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in to save posts." }
        val saveRef = refs.savedPosts.document(currentSaveId ?: interactionId(uid, postId))
        if (saved) {
            saveRef.set(
                mapOf("userId" to uid, "postId" to postId, "savedAt" to FieldValue.serverTimestamp()),
            ).await()
        } else {
            saveRef.delete().await()
        }
        saveRef.id.takeIf { saved }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw FeedConfigurationException()
    }
}

internal fun discoveryPayload(mode: FeedMode, cursor: FeedCursor?): Map<String, Any> = buildMap {
    put("mode", mode.raw)
    cursor?.postCreatedAt?.let { put("postCreatedAt", it) }
    cursor?.productCreatedAt?.let { put("productCreatedAt", it) }
    cursor?.cursorIndex?.let { put("cursorIndex", it) }
}

internal fun Map<String, Any?>.toFeedPage(): FeedPage = FeedPage(
    posts = mapList("posts").mapNotNull(Map<String, Any?>::toPost),
    products = mapList("products").mapNotNull(Map<String, Any?>::toProduct),
    order = mapList("order").mapNotNull { item ->
        val id = item.string("id")
        val type = item.string("type")
        FeedOrderEntry(id = id, type = type).takeIf { id.isNotBlank() && type in FeedOrderTypes }
    },
    nextCursor = this["nextCursor"].asStringMap().let { cursor ->
        FeedCursor(
            postCreatedAt = cursor.long("postCreatedAt"),
            productCreatedAt = cursor.long("productCreatedAt"),
            cursorIndex = cursor.int("cursorIndex"),
        )
    },
    hasMorePosts = (this["hasMorePosts"] as? Boolean ?: false) ||
        (this["hasMoreProducts"] as? Boolean ?: false),
)

private val FeedOrderTypes = setOf("post", "product")

internal fun Map<String, Any?>.toPost(): Post? {
    val id = string("id")
    if (id.isBlank()) return null
    return Post(
        id = id,
        title = string("title"),
        content = string("content"),
        type = string("type"),
        isAnonymous = boolean("isAnonymous"),
        personaName = nullableString("personaName"),
        personaEmoji = nullableString("personaEmoji"),
        reactionsCount = intMap("reactionsCount"),
        city = nullableString("city"),
        school = string("school"),
        authorId = string("authorId"),
        authorName = string("authorName"),
        authorProfilePicture = nullableString("authorProfilePicture"),
        status = string("status", "approved"),
        privacy = string("privacy", "public"),
        imageUrl = nullableString("imageUrl"),
        imageUrls = stringList("imageUrls"),
        imageWidth = int("imageWidth"),
        imageHeight = int("imageHeight"),
        imagesDetailed = mapListValue("imagesDetailed").map { image ->
            PostImage(
                url = image.string("url"),
                w = image.int("w") ?: 0,
                h = image.int("h") ?: 0,
            )
        },
        pdfUrl = nullableString("pdfUrl"),
        pdfPages = int("pdfPages"),
        videoUrl = nullableString("videoUrl"),
        upvotesCount = int("upvotesCount") ?: 0,
        downvotesCount = int("downvotesCount") ?: 0,
        repliesCount = int("repliesCount") ?: 0,
        feedScore = double("feedScore"),
        isHot = boolean("isHot"),
        poll = nullableMap("poll")?.let { poll ->
            Poll(
                choices = poll.stringList("choices"),
                votes = poll.intMap("votes"),
                expiresAt = poll.timestamp("expiresAt"),
            )
        },
        createdAt = timestamp("createdAt"),
        updatedAt = timestamp("updatedAt"),
    )
}

private fun interactionId(uid: String, postId: String): String = "${uid}_$postId"

private fun List<com.google.firebase.firestore.DocumentSnapshot>.interactionDocumentIds(): Map<String, String> =
    mapNotNull { document ->
        document.getString("postId")?.takeIf(String::isNotBlank)?.let { it to document.id }
    }.toMap()

private fun adjustedCount(
    current: Int,
    hadInteraction: Boolean,
    wantsInteraction: Boolean,
): Int = (current + when {
    hadInteraction == wantsInteraction -> 0
    wantsInteraction -> 1
    else -> -1
}).coerceAtLeast(0)

private fun Map<String, Any?>.string(key: String, fallback: String = ""): String =
    get(key)?.toString()?.takeUnless { it == "null" } ?: fallback

private fun Map<String, Any?>.nullableString(key: String): String? =
    get(key)?.toString()?.takeUnless { it == "null" || it.isBlank() }

private fun Map<String, Any?>.boolean(key: String): Boolean = get(key) as? Boolean ?: false

private fun Map<String, Any?>.int(key: String): Int? = (get(key) as? Number)?.toInt()

private fun Map<String, Any?>.long(key: String): Long? = (get(key) as? Number)?.toLong()

private fun Map<String, Any?>.double(key: String): Double? = (get(key) as? Number)?.toDouble()

private fun Map<String, Any?>.stringList(key: String): List<String> =
    (get(key) as? List<*>)?.mapNotNull { it as? String }.orEmpty()

private fun Map<String, Any?>.intMap(key: String): Map<String, Int> =
    get(key).asStringMap().mapNotNull { (mapKey, value) ->
        (value as? Number)?.toInt()?.let { mapKey to it }
    }.toMap()

private fun Map<String, Any?>.nullableMap(key: String): Map<String, Any?>? =
    (get(key) as? Map<*, *>)?.entries?.associate { (mapKey, value) -> mapKey.toString() to value }

private fun Map<String, Any?>.mapListValue(key: String): List<Map<String, Any?>> =
    (get(key) as? List<*>)?.mapNotNull { it.asStringMap().takeIf(Map<*, *>::isNotEmpty) }.orEmpty()

private fun Map<String, Any?>.timestamp(key: String): Timestamp? = when (val value = get(key)) {
    is Timestamp -> value
    is Date -> Timestamp(value)
    is Number -> Timestamp(Date(value.toLong()))
    else -> null
}

private class FeedConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
