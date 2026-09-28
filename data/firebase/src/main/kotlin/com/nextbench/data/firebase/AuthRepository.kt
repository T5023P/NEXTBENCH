package com.nextbench.data.firebase

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.nextbench.data.model.School
import com.nextbench.data.model.UserData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

data class StudentSignupData(
    val name: String,
    val school: String,
    val city: String,
    val referralCode: String = "",
)

data class OrganizationSignupData(
    val name: String,
    val type: String,
    val city: String,
    val website: String = "",
    val description: String = "",
    val referralCode: String = "",
)

data class AuthSession(
    val firebaseUser: FirebaseUser,
    val isNewUser: Boolean,
)

enum class AuthFailureKind {
    Validation,
    NotFound,
    RateLimited,
    Network,
    Configuration,

    /**
     * The server refused an irreversible request because the session is not recent enough and
     * re-verifying the user's identity is the only way to proceed (it cannot be retried in place).
     */
    ReauthRequired,
    Unknown,
}

data class AuthFailure(
    val kind: AuthFailureKind,
    val message: String,
)

sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Failure(val error: AuthFailure) : AuthResult<Nothing>
}

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(
        val firebaseUser: FirebaseUser,
        val userData: UserData?,
    ) : SessionState
}

@Singleton
class AuthRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
    private val storageProvider: Provider<FirebaseStorage>,
) {

    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()
    private val storage get() = storageProvider.get()

    val sessionState: Flow<SessionState> = if (!BuildConfig.FIREBASE_CONFIGURED) {
        flowOf(SessionState.SignedOut)
    } else {
        callbackFlow {
            var profileListener: ListenerRegistration? = null
            val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                profileListener?.remove()
                val user = firebaseAuth.currentUser
                if (user == null) {
                    trySend(SessionState.SignedOut)
                } else {
                    trySend(SessionState.Loading)
                    profileListener = refs.user(user.uid).addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        trySend(
                            SessionState.SignedIn(
                                firebaseUser = user,
                                userData = snapshot?.takeIf { it.exists() }?.toObject(UserData::class.java),
                            ),
                        )
                    }
                }
            }
            auth.addAuthStateListener(authListener)
            awaitClose {
                profileListener?.remove()
                auth.removeAuthStateListener(authListener)
            }
        }.distinctUntilChanged()
    }

    val authState: Flow<FirebaseUser?> = sessionState.map { state ->
        (state as? SessionState.SignedIn)?.firebaseUser
    }.distinctUntilChanged()

    fun userData(uid: String): Flow<UserData?> = if (!BuildConfig.FIREBASE_CONFIGURED) {
        flowOf(null)
    } else {
        refs.user(uid).snapshotFlow()
            .map { snapshot -> if (snapshot.exists()) snapshot.toObject(UserData::class.java) else null }
            .distinctUntilChanged()
    }

    suspend fun schools(): AuthResult<List<School>> = authResult {
        ensureConfigured()
        refs.schools.orderBy("name").get().await().toObjects(School::class.java)
    }

    suspend fun sendOtp(email: String): AuthResult<Unit> = authResult {
        ensureConfigured()
        require(email.isValidEmail()) { "Please enter a valid email address." }
        functions.sendAuthOtpEmail(email.normalizedEmail())
        Unit
    }

    suspend fun verifyOtp(
        email: String,
        otp: String,
        signupData: StudentSignupData? = null,
    ): AuthResult<AuthSession> = authResult {
        ensureConfigured()
        val response = functions.verifyAuthOtpEmail(
            otpVerificationPayload(email, otp, signupData),
        )
        val session = signInFromResponse(response)
        if (signupData == null && !refs.user(session.firebaseUser.uid).get().await().exists()) {
            auth.signOut()
            throw NoProfileException()
        }
        session
    }

    suspend fun signInWithGoogleIdToken(idToken: String): AuthResult<AuthSession> = authResult {
        ensureConfigured()
        val result = googleAuth(idToken)
        val firebaseUser = requireNotNull(result.user) { "Google sign-in did not return a user." }
        if (!refs.user(firebaseUser.uid).get().await().exists()) {
            auth.signOut()
            throw NoProfileException()
        }
        AuthSession(firebaseUser, result.additionalUserInfo?.isNewUser == true)
    }

    suspend fun signUpStudentWithGoogleIdToken(
        idToken: String,
        signupData: StudentSignupData,
    ): AuthResult<AuthSession> = authResult {
        ensureConfigured()
        require(signupData.name.trim().length >= 2) { "Enter your full name." }
        require(signupData.school.isNotBlank()) { "Select your school." }
        val result = googleAuth(idToken)
        val firebaseUser = requireNotNull(result.user) { "Google sign-in did not return a user." }
        ensureStudentProfile(firebaseUser, signupData)
        AuthSession(firebaseUser, result.additionalUserInfo?.isNewUser == true)
    }

    /**
     * Registers an organization from its Google identity.
     *
     * The registration document is uploaded into THIS account's private Storage folder, so
     * sign-in has to happen before the upload — the uid does not exist any earlier. The order is
     * therefore: authenticate → upload privately → write the profile (without any document URL)
     * and the owner-only pointer → only then clean up a superseded document. If the profile write
     * fails, the freshly uploaded object is removed again so a failed signup cannot leave an
     * orphaned registration certificate behind.
     */
    suspend fun signUpOrganizationWithGoogleIdToken(
        idToken: String,
        signupData: OrganizationSignupData,
        document: File,
        documentName: String,
        documentMimeType: String?,
    ): AuthResult<AuthSession> = authResult {
        ensureConfigured()
        require(signupData.name.trim().length >= 2) { "Enter the organization name." }
        require(signupData.city.isNotBlank()) { "Enter the organization city." }
        require(signupData.type in OrganizationTypes) { "Select a valid organization type." }
        require(document.isFile && document.length() > 0L) { "Upload a verification document." }
        val result = googleAuth(idToken)
        val firebaseUser = requireNotNull(result.user) { "Google sign-in did not return a user." }

        val previous = refs.organizationPointer(firebaseUser.uid)
            .get().await().toOrganizationSubmission()
        val submissionId = organizationSubmissionId()
        val documentPath = organizationObjectPath(firebaseUser.uid, submissionId, documentMimeType)
        val contentType = organizationContentType(documentMimeType)

        uploadOrganizationDocument(documentPath, document, contentType)

        try {
            ensureOrganizationProfile(firebaseUser, signupData)
            refs.organizationPointer(firebaseUser.uid)
                .set(organizationPointerPayload(documentPath, contentType, submissionId))
                .await()
        } catch (error: Exception) {
            // Best effort: the account was not registered, so the evidence must not linger.
            runCatching { storage.reference.child(documentPath).delete().await() }
            throw error
        }

        // Confirmed written: a superseded attempt can be removed now.
        supersededOrganizationObjects(previous, submissionId).forEach { obsolete ->
            runCatching { storage.reference.child(obsolete).delete().await() }
        }

        AuthSession(firebaseUser, result.additionalUserInfo?.isNewUser == true)
    }

    /**
     * Uploads one organization registration document to the private folder.
     * No download URL is requested, because none may be persisted.
     */
    private suspend fun uploadOrganizationDocument(path: String, file: File, contentType: String) {
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        storage.reference.child(path).putFile(Uri.fromFile(file), metadata).await()
    }

    /**
     * Deletes the signed-in account through the server-authoritative callable, then signs out.
     *
     * The client never deletes data itself: the rules deny client deletes, and a partial
     * client-side cascade could look successful while leaving artifacts (including identity
     * evidence) behind.
     *
     * The callable only accepts the request when `auth_time` is recent. Because the app signs in
     * with email OTP / custom tokens, that means a fresh sign-in (see [deletionSessionIsStale]):
     * the request is refused locally as [RecentAuthRequiredException] when the session is already
     * known to be too old, and the server's own `failed-precondition` is surfaced the same way if
     * the two ever disagree. The session is deliberately left intact here — the caller decides
     * whether to sign out, because a failed sign-out must not look like a completed deletion.
     */
    suspend fun deleteAccount(confirmation: String): AuthResult<Unit> = authResult {
        ensureConfigured()
        require(confirmation.trim().isNotBlank()) { "Type DELETE to confirm account deletion." }
        if (deletionSessionIsStale(auth.currentUser?.metadata?.lastSignInTimestamp, System.currentTimeMillis())) {
            throw RecentAuthRequiredException()
        }
        functions.requestAccountDeletion(confirmation)
        auth.signOut()
        Unit
    }

    suspend fun signOut(): AuthResult<Unit> = authResult {
        ensureConfigured()
        auth.currentUser?.uid?.let { uid ->
            runCatching {
                refs.user(uid).update(
                    mapOf(
                        "online" to false,
                        "lastSeen" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            }
        }
        auth.signOut()
        Unit
    }

    suspend fun setPresence(uid: String, online: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
        refs.user(uid).update(
            mapOf(
                "online" to online,
                "lastSeen" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /**
     * Records which private Storage objects hold this user's identity evidence.
     *
     * Written to `users/{uid}/private/verification` (owner-only) instead of the public
     * profile document, because that document is readable by every signed-in user and an
     * identity document must never be reachable from it.
     */
    suspend fun saveVerificationPointer(
        uid: String,
        idCardPath: String,
        selfiePath: String,
        submissionId: String,
    ): AuthResult<Unit> = authResult {
        ensureConfigured()
        require(idCardPath.isNotBlank() && selfiePath.isNotBlank()) { "Verification photos are required." }
        refs.verificationPointer(uid)
            .set(verificationPointerPayload(idCardPath, selfiePath, submissionId))
            .await()
        Unit
    }

    private suspend fun googleAuth(idToken: String) =
        auth.signInWithCredential(
            GoogleAuthProvider.getCredential(
                idToken.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("Google sign-in did not return an identity token."),
                null,
            ),
        ).await()

    private suspend fun ensureStudentProfile(user: FirebaseUser, data: StudentSignupData) {
        val userRef = refs.user(user.uid)
        if (userRef.get().await().exists()) return

        val referrerId = data.referralCode.trim()
            .takeIf(String::isNotBlank)
            ?.let { functions.lookupReferralCode(it.uppercase()) }
        val batch = userRef.firestore.batch()
        val userData = mutableMapOf<String, Any?>(
            "name" to data.name.trim(),
            "email" to user.email.orEmpty(),
            "school" to data.school.trim(),
            "city" to data.city.trim().ifBlank { "Lucknow" },
            "verified" to false,
            "verificationStatus" to "pending",
            "reputation" to 5.0,
            "isAdmin" to false,
            "profilePicture" to user.photoUrl?.toString(),
            // Identity evidence is deliberately absent: it lives at identity/{uid}/** in
            // private Storage with an owner-only pointer, never on this public document.
            "about" to null,
            "accountType" to "student",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (referrerId != null) {
            userData["referredBy"] = referrerId
            batch.set(
                refs.users.document(referrerId).collection("referrals").document(user.uid),
                mapOf("timestamp" to FieldValue.serverTimestamp()),
            )
        }
        batch.set(userRef, userData)
        batch.commit().await()
    }

    private suspend fun ensureOrganizationProfile(user: FirebaseUser, data: OrganizationSignupData) {
        val userRef = refs.user(user.uid)
        if (userRef.get().await().exists()) return

        val referrerId = data.referralCode.trim()
            .takeIf(String::isNotBlank)
            ?.let { functions.lookupReferralCode(it.uppercase()) }
        val batch = userRef.firestore.batch()
        val userData = mutableMapOf<String, Any?>(
            "name" to data.name.trim(),
            "email" to user.email.orEmpty(),
            "school" to data.name.trim(),
            "city" to data.city.trim(),
            "verified" to false,
            "verificationStatus" to "pending",
            "reputation" to 5.0,
            "isAdmin" to false,
            "profilePicture" to user.photoUrl?.toString(),
            // Identity evidence is deliberately absent (see ensureStudentProfile).
            "about" to data.description.trim().ifBlank { null },
            "accountType" to "organization",
            "orgName" to data.name.trim(),
            "orgType" to data.type,
            // No orgDocumentUrl: the registration document is private Storage evidence,
            // referenced only by users/{uid}/private/organization.
            "orgWebsite" to data.website.trim().ifBlank { null },
            "orgDescription" to data.description.trim().ifBlank { null },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (referrerId != null) {
            userData["referredBy"] = referrerId
            batch.set(
                refs.users.document(referrerId).collection("referrals").document(user.uid),
                mapOf("timestamp" to FieldValue.serverTimestamp()),
            )
        }
        batch.set(userRef, userData)
        batch.commit().await()
    }

    private suspend fun signInFromResponse(response: Map<String, Any?>): AuthSession {
        val customToken = response["customToken"]?.toString().orEmpty()
        val result = if (customToken.isNotBlank()) {
            auth.signInWithCustomToken(customToken).await()
        } else {
            val email = response["email"]?.toString().orEmpty()
            val password = response["loginPassword"]?.toString().orEmpty()
            require(email.isNotBlank() && password.isNotBlank()) { "Authentication service returned an incomplete response." }
            auth.signInWithEmailAndPassword(email, password).await()
        }
        return AuthSession(
            firebaseUser = requireNotNull(result.user) { "Authentication did not return a user." },
            isNewUser = response["isNewUser"] as? Boolean ?: false,
        )
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            throw FirebaseConfigurationException()
        }
    }
}

/**
 * Fields the client may persist for a verification submission.
 *
 * Stores object *paths* for the private Storage evidence, never a public URL: identity
 * documents must not have a durable public link. `verificationStatus`, `verified`, and
 * `verificationRejectionReason` are owned by the website's `/api/verify` endpoint, and
 * `firestore.rules` only permits a client-side status transition of
 * rejected/flagged_manual -> pending. Writing a status here would therefore either
 * clobber a server verdict or be rejected with PERMISSION_DENIED.
 */
internal fun verificationPointerPayload(
    idCardPath: String,
    selfiePath: String,
    submissionId: String,
): Map<String, Any?> = mapOf(
    "idCardPath" to idCardPath,
    "selfiePath" to selfiePath,
    "submissionId" to submissionId,
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun otpVerificationPayload(
    email: String,
    otp: String,
    signupData: StudentSignupData?,
): Map<String, Any?> {
    val digits = otp.filter(Char::isDigit)
    require(email.isValidEmail()) { "Please enter a valid email address." }
    require(digits.length == 6) { "Enter the 6-digit code." }
    return buildMap {
        put("email", email.normalizedEmail())
        put("otp", digits)
        if (signupData != null) {
            require(signupData.name.trim().length >= 2) { "Enter your full name." }
            require(signupData.school.isNotBlank()) { "Select your school." }
            put("isSignup", true)
            put(
                "signupData",
                mapOf(
                    "name" to signupData.name.trim(),
                    "school" to signupData.school.trim(),
                    "city" to signupData.city.trim().ifBlank { "Lucknow" },
                    "referralCode" to signupData.referralCode.trim(),
                ),
            )
        }
    }
}

private suspend fun <T> authResult(block: suspend () -> T): AuthResult<T> = try {
    AuthResult.Success(block())
} catch (error: Exception) {
    AuthResult.Failure(error.toAuthFailure())
}

private fun Exception.toAuthFailure(): AuthFailure {
    val detailsMessage = (this as? FirebaseFunctionsException)
        ?.details
        .let { it as? Map<*, *> }
        ?.get("message")
        ?.toString()
    val rawMessage = detailsMessage ?: message
    val message = rawMessage
        ?.removePrefix("Error: ")
        ?.substringAfter("] ")
        ?.takeIf(String::isNotBlank)
        ?: "Something went wrong. Please try again."
    val kind = authFailureKindFor(
        localError = this,
        functionsCodeName = (this as? FirebaseFunctionsException)?.code?.name,
        authCode = (this as? FirebaseAuthException)?.errorCode.orEmpty(),
    )
    return AuthFailure(kind, message)
}

// `FirebaseFunctionsException.Code` cannot be loaded in a JVM unit test (its static initializer
// needs the Android runtime), so the mapping compares code names. If Firebase ever renames one,
// the failure degrades to Unknown — the UI still shows the server's own message and nothing is
// signed out or deleted on a guess, which is the safe direction to fail in.
private const val CodeFailedPrecondition = "FAILED_PRECONDITION"
private const val CodeNotFound = "NOT_FOUND"
private const val CodeResourceExhausted = "RESOURCE_EXHAUSTED"
private const val CodeUnavailable = "UNAVAILABLE"

/**
 * Maps a failure to a kind without needing the exception to be constructed.
 *
 * Pure so the mapping can be unit tested — in particular `failed-precondition`, which is how
 * `requestAccountDeletion` reports the recent-sign-in gate. It must never be reported as a
 * generic failure: the user has to know that signing in again is what unblocks them.
 */
internal fun authFailureKindFor(
    localError: Exception? = null,
    functionsCodeName: String? = null,
    authCode: String = "",
): AuthFailureKind = when {
    localError is IllegalArgumentException -> AuthFailureKind.Validation
    localError is FirebaseConfigurationException -> AuthFailureKind.Configuration
    localError is NoProfileException -> AuthFailureKind.NotFound
    localError is RecentAuthRequiredException -> AuthFailureKind.ReauthRequired
    functionsCodeName == CodeFailedPrecondition -> AuthFailureKind.ReauthRequired
    functionsCodeName == CodeNotFound -> AuthFailureKind.NotFound
    functionsCodeName == CodeResourceExhausted -> AuthFailureKind.RateLimited
    functionsCodeName == CodeUnavailable -> AuthFailureKind.Network
    authCode.contains("NETWORK", ignoreCase = true) -> AuthFailureKind.Network
    else -> AuthFailureKind.Unknown
}

/**
 * The callable's recent-sign-in window. Mirrors `RECENT_AUTH_MS` in `requestAccountDeletion`
 * (`functions/src/index.ts`); keep the two in sync, because the client uses it to avoid a
 * request the server is guaranteed to reject.
 */
internal const val RECENT_AUTH_WINDOW_MILLIS: Long = 5 * 60 * 1000L

/**
 * True when the server will refuse an irreversible request from this session.
 *
 * `auth_time` is the original sign-in time: `getIdToken(true)` refreshes the token but does not
 * make it current, and neither does any silent refresh — which is exactly why it is a usable
 * recent-auth signal. The check is a client-side courtesy, never the security boundary: the
 * callable still enforces the window, and a `failed-precondition` answer is handled as well.
 *
 * An unknown timestamp is treated as stale. Firebase reports 0 when it has no sign-in record for
 * the user, and asking for a fresh sign-in is the safe direction to fail in.
 */
internal fun deletionSessionIsStale(
    lastSignInMillis: Long?,
    nowMillis: Long,
    windowMillis: Long = RECENT_AUTH_WINDOW_MILLIS,
): Boolean {
    if (lastSignInMillis == null || lastSignInMillis <= 0L) return true
    return nowMillis - lastSignInMillis > windowMillis
}

/**
 * Raised when deletion (or another irreversible action) needs a fresh sign-in first.
 *
 * Distinct from a validation or network failure so the UI can route the user through the
 * existing sign-in flow instead of offering an in-place retry that can never succeed.
 */
internal class RecentAuthRequiredException : IllegalStateException(
    "Please sign in again to continue deleting your account.",
)

private fun String.normalizedEmail(): String = trim().lowercase()

private fun String.isValidEmail(): Boolean =
    Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalizedEmail())

private class NoProfileException : IllegalStateException(
    "No NextBench profile exists for this Google account. Sign up first.",
)

private class FirebaseConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)

private val OrganizationTypes = setOf("company", "school", "coaching", "ngo", "other")
