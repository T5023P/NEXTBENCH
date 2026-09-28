package com.nextbench.data.firebase

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class VerificationStage {
    Uploading,
    Reviewing,
    Complete,
}

data class VerificationOutcome(
    val status: String,
    val reason: String? = null,
    val automated: Boolean = false,
)

/**
 * Which private Storage objects hold this user's identity evidence.
 *
 * Paths, never URLs: the objects are owner-only, so there is no public link to persist, and
 * the review endpoint reads them with the Admin SDK.
 */
data class VerificationSubmission(
    val idCardPath: String,
    val selfiePath: String,
    val submissionId: String,
)

/** The website review endpoint answered with a non-2xx status. */
internal class VerificationServiceException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

/** The review request never reached the server or never received a reply. */
internal class VerificationTransportException(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Bridges the web verification endpoint while keeping upload and review states observable. */
@Singleton
class VerificationRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val storageProvider: Provider<FirebaseStorage>,
    private val authRepository: AuthRepository,
) {
    // Resolved lazily so an unconfigured build fails in ensureConfigured() with user copy,
    // never inside dependency injection (see FirestoreRefs for the full rationale).
    private val storage get() = storageProvider.get()

    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    /**
     * Live view of whether identity evidence is on file for this user.
     *
     * Reads the owner-only pointer rather than a field on `users/{uid}`, because identity
     * evidence must never live on the publicly readable profile document.
     */
    fun observeSubmission(uid: String): Flow<VerificationSubmission?> = flow {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            emit(null)
            return@flow
        }
        emitAll(
            refs.verificationPointer(uid).snapshotFlow()
                .map { snapshot -> snapshot.toVerificationSubmission() },
        )
    }

    suspend fun submit(
        user: UserData,
        idCard: File,
        selfie: File,
        onStage: suspend (VerificationStage) -> Unit = {},
    ): Result<VerificationOutcome> = runCatching {
        require(user.uid.isNotBlank()) { "Your session has expired. Please sign in again." }
        require(idCard.isFile && idCard.length() > 0) { "Choose a student ID photo." }
        require(selfie.isFile && selfie.length() > 0) { "Choose a selfie holding your ID." }
        // The endpoint answers 400 when profileName or schoolName is blank, so validate the identity
        // it will receive before spending two Cloudinary uploads on a submission it must reject.
        val (profileName, schoolName) = verificationReviewIdentity(user)

        // The previous attempt's objects, so they can be removed once this one is confirmed.
        // A failed or unconfirmed review must leave the existing evidence in place.
        val previous = currentSubmission(user.uid)

        onStage(VerificationStage.Uploading)
        val submissionId = identitySubmissionId()
        val idCardPath = identityObjectPath(user.uid, "id", submissionId, idCard.name)
        val selfiePath = identityObjectPath(user.uid, "selfie", submissionId, selfie.name)
        coroutineScope {
            val id = async { uploadIdentityObject(idCardPath, idCard) }
            val selfieResult = async { uploadIdentityObject(selfiePath, selfie) }
            id.await()
            selfieResult.await()
        }
        // Refresh the ID token before the review call so the Authorization header is current.
        auth.currentUser?.getIdToken(true)?.await()
        onStage(VerificationStage.Reviewing)
        val token = requireNotNull(auth.currentUser) { "Your session has expired. Please sign in again." }
            .getIdToken(false)
            .await()
            .token
            .orEmpty()
        // A failed review throws out of this block, so a rejected submission is never persisted
        // and never reported back as an accepted or queued submission.
        val outcome = review(
            token = token,
            uid = user.uid,
            profileName = profileName,
            schoolName = schoolName,
            idCardPath = idCardPath,
            selfiePath = selfiePath,
        )

        // Only a confirmed review response may persist the submission. The verdict itself stays
        // server-owned, so the client still only records that photos were accepted for review.
        when (val update = authRepository.saveVerificationPointer(user.uid, idCardPath, selfiePath, submissionId)) {
            is AuthResult.Success -> Unit
            is AuthResult.Failure -> error(update.error.message)
        }

        // Confirmed submission: the superseded evidence can now be removed so rejected and
        // resubmitted attempts do not accumulate identity documents in Storage.
        deleteSupersededObjects(previous, keepSubmissionId = submissionId)

        onStage(VerificationStage.Complete)
        outcome
    }

    /** Uploads one identity object. No download URL is requested, because none may be persisted. */
    private suspend fun uploadIdentityObject(path: String, file: File) {
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        storage.reference.child(path).putFile(Uri.fromFile(file), metadata).await()
    }

    private suspend fun currentSubmission(uid: String): VerificationSubmission? = runCatching {
        refs.verificationPointer(uid).get().await().toVerificationSubmission()
    }.getOrNull()

    /** Best-effort removal of the previous attempt's objects. Owner-writable per storage rules. */
    private suspend fun deleteSupersededObjects(
        previous: VerificationSubmission?,
        keepSubmissionId: String,
    ) {
        if (previous == null || previous.submissionId == keepSubmissionId) return
        listOf(previous.idCardPath, previous.selfiePath)
            .filter(String::isNotBlank)
            .forEach { path -> runCatching { storage.reference.child(path).delete().await() } }
    }

    private suspend fun review(
        token: String,
        uid: String,
        profileName: String,
        schoolName: String,
        idCardPath: String,
        selfiePath: String,
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        val connection = (URL("https://nextbench.in/api/verify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            // Private Storage paths, not public URLs. The endpoint re-scopes the path to the
            // authenticated uid before reading the object with the Admin SDK.
            val body = JSONObject()
                .put("uid", uid)
                .put("profileName", profileName)
                .put("schoolName", schoolName)
                .put("idCardPath", idCardPath)
                .put("selfiePath", selfiePath)
                .toString()
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            verificationReviewOutcome(code, response.toReviewResponse())
        } catch (error: VerificationServiceException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw VerificationTransportException(verificationTransportFailureMessage(error), error)
        } catch (error: IOException) {
            throw VerificationTransportException(verificationTransportFailureMessage(error), error)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The profile identity the review endpoint requires. `/api/verify` answers 400 when `profileName`
 * or `schoolName` is blank, so a blank profile is rejected here before any upload is attempted.
 */
internal fun verificationReviewIdentity(user: UserData): Pair<String, String> {
    require(user.name.isNotBlank()) {
        "Add your full name to your profile before submitting verification."
    }
    require(user.school.isNotBlank()) {
        "Add your school to your profile before submitting verification."
    }
    return user.name to user.school
}

/**
 * The private Storage path for one identity document: `identity/{uid}/{kind}-{submissionId}.{ext}`.
 *
 * Matches the shape the website writes and the prefix `/api/verify` re-checks before reading
 * the object, so a client can never point the server at another user's evidence.
 */
internal fun identityObjectPath(
    uid: String,
    kind: String,
    submissionId: String,
    fileName: String,
): String {
    val rawExtension = fileName.substringAfterLast('.', "jpg").lowercase()
    val extension = rawExtension.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
    return "identity/$uid/$kind-$submissionId.$extension"
}

/**
 * A fresh, path-safe submission id. A resubmission gets a new id so it can never overwrite the
 * objects an in-flight review is reading.
 */
internal fun identitySubmissionId(): String = "android_" + UUID.randomUUID().toString().replace("-", "")

/** Reads the owner-only verification pointer. Blank or partial pointers are treated as absent. */
internal fun DocumentSnapshot.toVerificationSubmission(): VerificationSubmission? {
    if (!exists()) return null
    val idCardPath = getString("idCardPath").orEmpty()
    val selfiePath = getString("selfiePath").orEmpty()
    val submissionId = getString("submissionId").orEmpty()
    if (idCardPath.isBlank() || selfiePath.isBlank() || submissionId.isBlank()) return null
    return VerificationSubmission(idCardPath, selfiePath, submissionId)
}

/**
 * Reads the review response fields this client depends on. Unknown keys and JSON nulls are dropped
 * so the mapper below always sees a sparse contract map. Parsing never throws: an empty, HTML, or
 * truncated body yields an empty map, which makes the mapper fail closed instead of reporting a
 * success the server never sent.
 */
internal fun String.toReviewResponse(): Map<String, Any?> = runCatching {
    val json = JSONObject(this)
    buildMap {
        ReviewResponseKeys.forEach { key ->
            if (json.has(key) && json.opt(key) != JSONObject.NULL) put(key, json.opt(key))
        }
    }
}.getOrDefault(emptyMap())

/**
 * Maps the website review response to an outcome. A non-2xx response raises
 * [VerificationServiceException], so an unsuccessful review can never be mistaken for an
 * accepted submission and nothing reaches Firestore for it.
 */
internal fun verificationReviewOutcome(responseCode: Int, responseBody: Map<String, Any?>): VerificationOutcome {
    if (responseCode !in 200..299) {
        throw VerificationServiceException(
            statusCode = responseCode,
            message = verificationServiceFailureMessage(responseCode, responseBody.reviewString("error")),
        )
    }
    // Every 2xx path on /api/verify carries a verificationStatus, so a 2xx without a recognized one
    // is an unconfirmed reply (empty body, HTML interstitial, truncated JSON). Failing closed here
    // keeps a malformed success from being persisted as a queued submission the server never saw.
    val status = responseBody.reviewString("verificationStatus")
        ?.let { raw -> VerificationStatus.entries.firstOrNull { it.raw == raw } }
        ?: throw VerificationServiceException(
            statusCode = responseCode,
            message = VerificationUnconfirmedResponseMessage,
        )
    return VerificationOutcome(
        status = status.raw,
        reason = responseBody.reviewString("rejectionReason") ?: responseBody.reviewString("reason"),
        automated = responseBody["success"] as? Boolean ?: false,
    )
}

/**
 * Copy for a 2xx review reply carrying no verification status this client recognizes, such as an
 * empty body or an HTML interstitial served by a proxy. The submission stays unconfirmed.
 */
internal const val VerificationUnconfirmedResponseMessage: String =
    "The verification service returned an unexpected response, so your submission was not confirmed. " +
        "Your photos were not submitted. Please try again."

/** User-facing copy for a rejected review request. Never promises a manual-review queue. */
internal fun verificationServiceFailureMessage(responseCode: Int, serverMessage: String?): String = when {
    responseCode == 401 -> "Your session expired before the review finished. Sign in again and resubmit your photos."
    responseCode == 403 -> "This account cannot submit verification right now. Contact support if this continues."
    responseCode >= 500 -> "The verification service is temporarily unavailable. Your photos were not submitted. Please try again."
    responseCode == 404 || responseCode == 405 -> "The verification service could not be reached. Your photos were not submitted. Please try again."
    serverMessage != null -> serverMessage
    else -> "Verification could not be submitted (service error $responseCode). Your photos were not submitted. Please try again."
}

/** User-facing copy for a request that never received a confirmed reply. */
internal fun verificationTransportFailureMessage(error: Throwable): String = when (error) {
    is SocketTimeoutException ->
        "Verification timed out before the server confirmed your submission. Your photos were not submitted. Please try again."
    is IOException ->
        "No internet connection. Your photos were not submitted. Check your network and try again."
    else -> "Verification could not reach the server. Your photos were not submitted. Please try again."
}

private fun Map<String, Any?>.reviewString(key: String): String? =
    get(key)?.toString()?.trim()?.takeIf(String::isNotBlank)

private val ReviewResponseKeys = listOf("verificationStatus", "rejectionReason", "reason", "error", "success")
