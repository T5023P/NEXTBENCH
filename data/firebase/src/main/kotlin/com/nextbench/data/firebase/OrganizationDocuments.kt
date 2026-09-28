package com.nextbench.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import java.util.UUID

/**
 * Which private Storage object holds an account's organization registration document.
 *
 * Paths, never URLs. An organization registration document (GSTIN, UDISE, registration
 * certificate) used to be uploaded through the unsigned Cloudinary preset and stored as a
 * public URL on `users/{uid}` — a document any signed-in, non-blocked user can read. It is now
 * kept at `organization/{uid}/document-{submissionId}.{ext}` in Firebase Storage, owner-only
 * per `storage.rules`, referenced only by the owner-only pointer at
 * `users/{uid}/private/organization`. The admin verification queue reads the object with the
 * Admin SDK through the `getOrganizationDocumentUrl` callable, which mints a short-lived
 * signed URL on demand.
 */
data class OrganizationDocumentSubmission(
    val documentPath: String,
    val contentType: String,
    val submissionId: String,
)

/**
 * The content type actually sent to Storage.
 *
 * The picker accepts images and PDFs; anything else is coerced to JPEG rather than forwarded
 * blindly, so the Storage rule — which only accepts an image content type or `application/pdf` —
 * can never be bypassed by a spoofed or missing MIME type.
 */
internal fun organizationContentType(mimeType: String?): String = when (mimeType?.lowercase()) {
    "application/pdf" -> "application/pdf"
    "image/png" -> "image/png"
    "image/webp" -> "image/webp"
    else -> "image/jpeg"
}

private fun organizationDocumentExtension(contentType: String): String = when (contentType) {
    "application/pdf" -> "pdf"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}

/**
 * The private Storage path for one registration document: `organization/{uid}/document-{id}.{ext}`.
 *
 * The extension comes from the resolved content type, never from the user-supplied file name,
 * so a name cannot introduce a path segment or an unexpected key.
 */
internal fun organizationObjectPath(
    uid: String,
    submissionId: String,
    mimeType: String?,
): String {
    require(uid.isNotBlank()) { "A signed-in account is required to store an organization document." }
    require(submissionId.isNotBlank()) { "A submission id is required to store an organization document." }
    val contentType = organizationContentType(mimeType)
    return "organization/$uid/document-$submissionId.${organizationDocumentExtension(contentType)}"
}

/** A fresh, sortable submission id so a re-upload never overwrites in-flight evidence. */
internal fun organizationSubmissionId(): String =
    "android_" + UUID.randomUUID().toString().replace("-", "")

/**
 * The pointer written to `users/{uid}/private/organization`.
 *
 * Carries only the private path and its type — never a public URL, and never the server-owned
 * `verificationStatus` / `orgVerified` fields, which stay with the admin review.
 */
internal fun organizationPointerPayload(
    documentPath: String,
    contentType: String,
    submissionId: String,
): Map<String, Any?> = mapOf(
    "documentPath" to documentPath,
    "contentType" to contentType,
    "submissionId" to submissionId,
    "updatedAt" to FieldValue.serverTimestamp(),
)

/** Reads a pointer document, or null when nothing has been stored (or it is malformed). */
internal fun DocumentSnapshot.toOrganizationSubmission(): OrganizationDocumentSubmission? {
    if (!exists()) return null
    val path = getString("documentPath")
    val contentType = getString("contentType")
    val submissionId = getString("submissionId")
    if (path.isNullOrBlank() || contentType.isNullOrBlank() || submissionId.isNullOrBlank()) return null
    return OrganizationDocumentSubmission(path, contentType, submissionId)
}

/**
 * The objects a confirmed replacement makes obsolete.
 *
 * Used after a successful write so a re-submission does not accumulate registration
 * certificates in Storage; the current submission is never returned.
 */
internal fun supersededOrganizationObjects(
    previous: OrganizationDocumentSubmission?,
    keepSubmissionId: String,
): List<String> {
    if (previous == null || previous.submissionId == keepSubmissionId) return emptyList()
    return listOf(previous.documentPath).filter(String::isNotBlank)
}
