package com.nextbench.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * `notifications/{id}` — an in-app notification for a user. [type] drives the action
 * (deep link) and presentation. Web dispatches these via the `createNotification` callable;
 * the Android app only reads and marks them read.
 */
data class Notification(
    @DocumentId val id: String = "",
    val userId: String = "",
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val link: String? = null,
    val postId: String? = null,
    val read: Boolean = false,
    val createdAt: Timestamp? = null,
)

/** `follows/{id}` — a user-to-user follow relationship. */
data class Follow(
    @DocumentId val id: String = "",
    val followerId: String = "",
    val followingId: String = "",
    val createdAt: Timestamp? = null,
)

/** `blocks/{id}` — a user-to-user block (directional). */
data class Block(
    @DocumentId val id: String = "",
    val blockerId: String = "",
    val blockedId: String = "",
    val createdAt: Timestamp? = null,
)

/** `reports/{id}` — a user's report of another user or content. */
data class Report(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val reportedId: String? = null,
    val postId: String? = null,
    val productId: String? = null,
    val reason: String = "",
    val description: String? = null,
    val createdAt: Timestamp? = null,
)

/** `schools/{id}` — a verified school/institution. */
data class School(
    @DocumentId val id: String = "",
    val name: String = "",
    val city: String = "",
    val verified: Boolean = false,
)

/** `school_requests/{id}` — a pending request to add a new school. */
data class SchoolRequest(
    @DocumentId val id: String = "",
    val name: String = "",
    val city: String = "",
    val requestedBy: String = "",
    val status: String = "pending",
    val createdAt: Timestamp? = null,
)

/** `referrals/{id}` — a referral code and its owner. */
data class Referral(
    @DocumentId val id: String = "",
    val code: String = "",
    val userId: String = "",
    val createdAt: Timestamp? = null,
)

/** `saved_posts/{id}` — a user's bookmarked post. */
data class SavedPost(
    @DocumentId val id: String = "",
    val userId: String = "",
    val postId: String = "",
    val createdAt: Timestamp? = null,
)

/** `post_upvotes/{id}` — a user's upvote on a post (1 per user per post, toggle). */
data class PostUpvote(
    @DocumentId val id: String = "",
    val postId: String = "",
    val userId: String = "",
    val createdAt: Timestamp? = null,
)

/** `post_downvotes/{id}` — a user's downvote on a post (1 per user per post, toggle). */
data class PostDownvote(
    @DocumentId val id: String = "",
    val postId: String = "",
    val userId: String = "",
    val createdAt: Timestamp? = null,
)

/**
 * `post_reactions/{id}` — a user's special reaction on a post. Only one reaction per user
 * per post; toggling/swapping is handled in the repository layer via
 * [ReactionType] constraints.
 */
data class PostReaction(
    @DocumentId val id: String = "",
    val postId: String = "",
    val userId: String = "",
    val reaction: String = "",
    val createdAt: Timestamp? = null,
)
