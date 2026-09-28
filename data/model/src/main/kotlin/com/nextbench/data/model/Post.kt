package com.nextbench.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * `posts/{id}` — a feed post (confession/question/discussion). Anonymous posts carry a
 * [personaName]/[personaEmoji] instead of author identity. [reactionsCount] maps each
 * [ReactionType.id] to its tally.
 */
data class Post(
    @DocumentId val id: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "",
    val isAnonymous: Boolean = false,
    val personaName: String? = null,
    val personaEmoji: String? = null,
    val reactionsCount: Map<String, Int> = emptyMap(),
    val city: String? = null,
    val school: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorProfilePicture: String? = null,
    val status: String = PostStatus.Approved.raw,
    val privacy: String = ContentPrivacy.Public.raw,
    val imageUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val imagesDetailed: List<PostImage> = emptyList(),
    val pdfUrl: String? = null,
    val pdfPages: Int? = null,
    val videoUrl: String? = null,
    val upvotesCount: Int = 0,
    val downvotesCount: Int = 0,
    val repliesCount: Int = 0,
    val feedScore: Double? = null,
    val isHot: Boolean = false,
    val poll: Poll? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

/** Original dimensions captured during upload, used to reserve stable feed media space. */
data class PostImage(
    val url: String = "",
    val w: Int = 0,
    val h: Int = 0,
)

/** Embedded poll on a [Post]. [votes] maps a choice index (as a string) to its vote count. */
data class Poll(
    val choices: List<String> = emptyList(),
    val votes: Map<String, Int> = emptyMap(),
    val expiresAt: Timestamp? = null,
)

/** `post_replies/{id}` — a threaded reply to a [Post]. */
data class PostReply(
    @DocumentId val id: String = "",
    val postId: String = "",
    val content: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorSchool: String = "",
    val authorProfilePicture: String? = null,
    val imageUrl: String? = null,
    val parentId: String? = null,
    val upvotesCount: Int = 0,
    val repliesCount: Int = 0,
    val edited: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
