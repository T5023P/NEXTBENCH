package com.nextbench.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * `users/{uid}` — a NextBench member (student or organization). Field names mirror the web
 * schema verbatim so the same live documents deserialize without a migration.
 */
data class UserData(
    @DocumentId val uid: String = "",
    val name: String = "",
    val email: String = "",
    val school: String = "",
    val city: String = "",
    val verified: Boolean = false,
    val verificationStatus: String = VerificationStatus.Pending.raw,
    val verificationRejectionReason: String? = null,
    val reputation: Double = 0.0,
    val isAdmin: Boolean = false,
    val role: String? = null,
    val profilePicture: String? = null,
    val coverPhoto: String? = null,
    val idCardUrl: String? = null,
    val selfieUrl: String? = null,
    val about: String? = null,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val anonymousPersonaName: String? = null,
    val lastUsernameChange: Timestamp? = null,
    val chatPrivacy: ChatPrivacy? = null,
    val accountType: String = AccountType.Student.raw,
    val orgName: String? = null,
    val orgType: String? = null,
    // Legacy read-only: no new code may write this. Registration documents are private Storage
    // evidence referenced by `users/{uid}/private/organization`; this field only survives so
    // pre-migration documents still deserialize until the migration clears them.
    val orgDocumentUrl: String? = null,
    val orgWebsite: String? = null,
    val orgDescription: String? = null,
    val orgVerified: Boolean = false,
    val referralCode: String? = null,
    val referredBy: String? = null,
    val referralCount: Int = 0,
    val online: Boolean = false,
    val lastSeen: Timestamp? = null,
    val hideLastSeen: Boolean = false,
    val fcmTokens: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

data class ChatPrivacy(
    val followersOnly: Boolean = false,
)
