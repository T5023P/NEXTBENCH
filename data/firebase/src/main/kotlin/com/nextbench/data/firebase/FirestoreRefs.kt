package com.nextbench.data.firebase

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Typed Firestore collection/document reference accessors. Centralises path strings so
 * repositories never hard-code collection names.
 *
 * The Firestore instance is resolved lazily (`Provider`), matching every repository's
 * `ensureConfigured()` contract: an unconfigured build must reach the guard and fail with a
 * friendly error, not crash inside dependency injection.
 */
@Singleton
class FirestoreRefs @Inject constructor(private val dbProvider: Provider<FirebaseFirestore>) {

    private val db get() = dbProvider.get()

    val users: CollectionReference get() = db.collection("users")
    val usernames: CollectionReference get() = db.collection("usernames")
    val posts: CollectionReference get() = db.collection("posts")
    val postReplies: CollectionReference get() = db.collection("post_replies")
    val postUpvotes: CollectionReference get() = db.collection("post_upvotes")
    val postDownvotes: CollectionReference get() = db.collection("post_downvotes")
    val postReactions: CollectionReference get() = db.collection("post_reactions")
    val savedPosts: CollectionReference get() = db.collection("saved_posts")
    val products: CollectionReference get() = db.collection("products")
    val wishlists: CollectionReference get() = db.collection("wishlists")
    val reviews: CollectionReference get() = db.collection("reviews")
    val chatRooms: CollectionReference get() = db.collection("chatRooms")
    val clubs: CollectionReference get() = db.collection("clubs")
    val stories: CollectionReference get() = db.collection("stories")
    val notifications: CollectionReference get() = db.collection("notifications")
    val follows: CollectionReference get() = db.collection("follows")
    val followEdges: CollectionReference get() = db.collection("follow_edges")
    val blocks: CollectionReference get() = db.collection("blocks")
    val reports: CollectionReference get() = db.collection("reports")
    val schools: CollectionReference get() = db.collection("schools")
    val schoolRequests: CollectionReference get() = db.collection("school_requests")
    val referrals: CollectionReference get() = db.collection("referrals")
    val linkPreviews: CollectionReference get() = db.collection("linkPreviews")

    fun user(uid: String): DocumentReference = users.document(uid)
    fun post(id: String): DocumentReference = posts.document(id)
    fun product(id: String): DocumentReference = products.document(id)
    fun chatRoom(id: String): DocumentReference = chatRooms.document(id)
    fun messages(roomId: String): CollectionReference = chatRooms.document(roomId).collection("messages")
    fun club(id: String): DocumentReference = clubs.document(id)
    fun clubMessages(clubId: String): CollectionReference = clubs.document(clubId).collection("messages")
    fun story(id: String): DocumentReference = stories.document(id)
    fun storyViews(id: String): CollectionReference = story(id).collection("views")
    fun storyLikes(id: String): CollectionReference = story(id).collection("likes")
    fun storyReplies(id: String): CollectionReference = story(id).collection("replies")
    fun storySeen(uid: String): DocumentReference = user(uid).collection("private").document("storySeen")

    /**
     * The owner-only pointer to this user's identity evidence.
     *
     * Identity documents live under the `identity/{uid}` prefix in Storage, never as URLs on
     * the publicly readable `users/{uid}` document. The rules already restrict every document
     * in the owner's `private` subcollection to that owner, and admins read the evidence
     * through the `getVerificationAssetUrl` callable rather than this document.
     */
    fun verificationPointer(uid: String): DocumentReference =
        user(uid).collection("private").document("verification")

    /**
     * `users/{uid}/private/organization` — the owner-only pointer to an organization's private
     * registration document. Admin access goes through the `getOrganizationDocumentUrl`
     * callable instead, which mints a short-lived signed URL and never persists one.
     */
    fun organizationPointer(uid: String): DocumentReference =
        user(uid).collection("private").document("organization")
}
