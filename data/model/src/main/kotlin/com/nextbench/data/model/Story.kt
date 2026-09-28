package com.nextbench.data.model

import com.google.firebase.firestore.DocumentId

/**
 * `stories/{id}` — a 24-hour ephemeral story. Timestamps are epoch-milliseconds (Long) to
 * match the web schema; [expiresAt] is checked client-side to hide expired stories before
 * the Cloud Function purge runs.
 */
data class Story(
    @DocumentId val id: String = "",
    val authorId: String = "",
    val authorUsername: String = "",
    val authorPhotoURL: String? = null,
    val mediaType: String = StoryMediaType.Image.raw,
    val mediaUrl: String = "",
    val mediaPath: String = "",
    val posterUrl: String? = null,
    val posterPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val layers: List<Map<String, Any>> = emptyList(),
    val privacy: String = StoryPrivacy.Public.raw,
    val status: String = "active",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
)

/**
 * A collapsed entry in the stories tray — one per author, containing their most-recent
 * story metadata and a flag indicating whether the viewer has seen all stories.
 */
data class StoryTrayEntry(
    val authorId: String = "",
    val authorUsername: String = "",
    val authorPhotoURL: String? = null,
    val latestStoryId: String = "",
    val latestCreatedAt: Long = 0L,
    val allSeen: Boolean = false,
    val stories: List<Story> = emptyList(),
)
