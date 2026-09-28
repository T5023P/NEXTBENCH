package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.nextbench.data.model.ReportContentType
import com.nextbench.data.model.ReportReason
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Submits content reports to the shared `reports` collection.
 *
 * The document shape mirrors the website's `src/lib/reports.ts` exactly, because `firestore.rules`
 * only admits a client create when the reporter is the authenticated caller, `status` is `pending`,
 * and `createdAt` is the server timestamp. Reads are admin-only, so this repository deliberately
 * exposes no read path and the app can never display a report's outcome.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    suspend fun submitReport(
        reporterId: String,
        contentType: ReportContentType,
        contentId: String,
        reason: ReportReason,
        notes: String? = null,
    ): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(reporterId)
        // Validated up front so a doomed report fails with actionable copy instead of a raw
        // backend rejection. See requireReportable for why this checks email verification.
        val targetId = requireReportable(
            reporterId = reporterId,
            contentType = contentType,
            contentId = contentId,
            reporterVerified = auth.currentUser?.isEmailVerified == true,
        )
        refs.reports.add(reportPayload(reporterId, contentType, targetId, reason, notes)).await()
        Unit
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ReportConfigurationException()
    }
}

/**
 * Validates a report against `firestore.rules` and the in-app safety rules before any write is
 * attempted, and returns the normalised target id.
 *
 * [reporterVerified] is the Firebase Auth **email-verified** flag, because `reports` create is
 * gated by the rule helper `isVerified()`, which is `request.auth.token.email_verified == true`.
 * It is deliberately *not* [com.nextbench.data.model.UserData.verified]: that field records
 * NextBench identity verification (an approved student ID plus selfie) and is a strictly higher bar,
 * so requiring it here would silently stop unverified members from reporting abuse.
 */
internal fun requireReportable(
    reporterId: String,
    contentType: ReportContentType,
    contentId: String,
    reporterVerified: Boolean,
): String {
    require(reporterId.isNotBlank()) { "Your session expired. Sign in and try again." }
    require(reporterVerified) { "Verify your email address before reporting." }
    val targetId = contentId.trim()
    require(targetId.isNotBlank()) { "This content is no longer available to report." }
    // Only a member report can be a self-report; a post, listing, or message id may legitimately
    // equal the reporter's own uid, so the guard must not be widened to those content types.
    require(!(contentType == ReportContentType.User && targetId == reporterId)) {
        "You cannot report your own account."
    }
    return targetId
}

/**
 * The exact `reports/{id}` document the website writes. Blank notes are stored as `null` (the
 * website's `notes?.trim() || null`), `status` is pinned to the only value the rules admit, and the
 * timestamp must stay server-owned.
 */
internal fun reportPayload(
    reporterId: String,
    contentType: ReportContentType,
    contentId: String,
    reason: ReportReason,
    notes: String?,
): Map<String, Any?> = mapOf(
    "reporterId" to reporterId,
    "contentType" to contentType.raw,
    "contentId" to contentId.trim(),
    "reason" to reason.raw,
    "notes" to notes?.trim()?.takeIf(String::isNotBlank),
    "status" to ReportPendingStatus,
    "createdAt" to FieldValue.serverTimestamp(),
)

/** The only `status` `firestore.rules` admits on a client-side report create. */
internal const val ReportPendingStatus = "pending"

private class ReportConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
