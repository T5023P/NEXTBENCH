package com.nextbench.data.model

/**
 * The fixed set of "special reactions" a user can place on a post (one per user per post,
 * toggled/swapped). Weights mirror the web app's `reactions.ts` and feed the post's feed score.
 * The Firestore `post_reactions` doc stores [id] as its `reaction` field.
 */
enum class ReactionType(
    val id: String,
    val emoji: String,
    val weight: Int,
    val label: String,
) {
    Dead("dead", "💀", 2, "Dead"),
    TooReal("too_real", "😭", 3, "Too Real"),
    Exposed("exposed", "👀", 2, "Exposed"),
    Crazy("crazy", "🤯", 2, "Crazy"),
    Wholesome("wholesome", "❤️", 3, "Wholesome"),
    SpillMore("spill_more", "☕", 4, "Spill More"),
    Respect("respect", "🫡", 2, "Respect");

    companion object {
        fun from(id: String?): ReactionType? =
            entries.firstOrNull { it.id == id }
    }
}
