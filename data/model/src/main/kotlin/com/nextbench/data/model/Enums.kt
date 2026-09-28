package com.nextbench.data.model

/**
 * String-backed enums for Firestore status/type fields. Firestore documents store the raw
 * lowercase/snake string (matching the web app); models keep the raw String, and these enums
 * are used by the repository/UI layer via [from]. This avoids Firestore's brittle name-based
 * enum deserialization while still giving strongly-typed values downstream.
 */

enum class VerificationStatus(val raw: String) {
    Pending("pending"),
    Approved("approved"),
    Rejected("rejected"),
    FlaggedManual("flagged_manual");

    companion object {
        fun from(raw: String?): VerificationStatus =
            entries.firstOrNull { it.raw == raw } ?: Pending
    }
}

enum class AccountType(val raw: String) {
    Student("student"),
    Organization("organization");

    companion object {
        fun from(raw: String?): AccountType =
            entries.firstOrNull { it.raw == raw } ?: Student
    }
}

enum class PostStatus(val raw: String) {
    Pending("pending"),
    Approved("approved"),
    Rejected("rejected");

    companion object {
        fun from(raw: String?): PostStatus =
            entries.firstOrNull { it.raw == raw } ?: Approved
    }
}

enum class ContentPrivacy(val raw: String) {
    Public("public"),
    School("school"),
    Private("private");

    companion object {
        fun from(raw: String?): ContentPrivacy =
            entries.firstOrNull { it.raw == raw } ?: Public
    }
}

enum class ProductStatus(val raw: String) {
    Pending("pending"),
    Available("available"),
    Reserved("reserved"),
    Sold("sold"),
    Rejected("rejected");

    companion object {
        fun from(raw: String?): ProductStatus =
            entries.firstOrNull { it.raw == raw } ?: Pending
    }
}

enum class MessageType(val raw: String) {
    Text("text"),
    Image("image"),
    Voice("voice"),
    Video("video"),
    File("file");

    companion object {
        fun from(raw: String?): MessageType =
            entries.firstOrNull { it.raw == raw } ?: Text
    }
}

enum class MessageStatus(val raw: String) {
    Pending("pending"),
    Failed("failed"),
    Sent("sent");

    companion object {
        fun from(raw: String?): MessageStatus =
            entries.firstOrNull { it.raw == raw } ?: Sent
    }
}

enum class StoryMediaType(val raw: String) {
    Image("image"),
    Video("video");

    companion object {
        fun from(raw: String?): StoryMediaType =
            entries.firstOrNull { it.raw == raw } ?: Image
    }
}

enum class StoryPrivacy(val raw: String) {
    Public("public"),
    Followers("followers"),
    CloseFriends("closeFriends");

    companion object {
        fun from(raw: String?): StoryPrivacy =
            entries.firstOrNull { it.raw == raw } ?: Public
    }
}

enum class ClubType(val raw: String) {
    Public("public"),
    Private("private");

    companion object {
        fun from(raw: String?): ClubType =
            entries.firstOrNull { it.raw == raw } ?: Public
    }
}

enum class NotificationType(val raw: String) {
    UserApproved("user_approved"),
    ListingApproved("listing_approved"),
    ListingRejected("listing_rejected"),
    NewMessage("new_message"),
    NewPost("new_post"),
    ItemReserved("item_reserved"),
    ItemSold("item_sold"),
    NewReview("new_review"),
    AdminPromoted("admin_promoted"),
    Mention("mention");

    companion object {
        fun from(raw: String?): NotificationType? =
            entries.firstOrNull { it.raw == raw }
    }
}

/**
 * `reports.contentType` — the reportable user-generated-content kinds the website's
 * `src/lib/reports.ts` accepts.
 *
 * The website also writes *support tickets* into the same `reports` collection, but with
 * `contentType: 'support_ticket'`, `contentId: 'general'`, a free-text `reason` that is not one of
 * [ReportReason], and a `details` field instead of `notes`. That is a help-centre surface with
 * different semantics and a different moderation owner, so it is deliberately **excluded** from
 * Android UGC reporting and [from] returns null for it.
 */
enum class ReportContentType(val raw: String) {
    Post("post"),
    Product("product"),
    User("user"),
    Message("message");

    companion object {
        fun from(raw: String?): ReportContentType? =
            entries.firstOrNull { it.raw == raw }
    }
}

/**
 * `reports.reason` — mirrors the website's `REPORT_REASONS` list and its order. Declared raw-first
 * because the stored value, not the label, is the backend contract.
 */
enum class ReportReason(val raw: String) {
    Inappropriate("inappropriate"),
    Harassment("harassment"),
    Spam("spam"),
    HateSpeech("hate_speech"),
    Violence("violence"),
    SelfHarm("self_harm"),
    Misinformation("misinformation"),
    Other("other");

    companion object {
        fun from(raw: String?): ReportReason? =
            entries.firstOrNull { it.raw == raw }
    }
}
