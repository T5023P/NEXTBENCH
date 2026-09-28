package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentSnapshot
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class ProfileContent(
    val user: UserData?,
    val listings: List<Product>,
    val posts: List<Post>,
    val followersCount: Int = 0,
    val followingCount: Int = 0,
)

data class ProfileUpdateDraft(
    val name: String,
    val about: String,
    val username: String,
    val profilePictureFile: File? = null,
    val removeProfilePicture: Boolean = false,
)

data class ProfileUpdateResult(
    val name: String,
    val about: String?,
    val username: String,
    val profilePicture: String?,
)

data class UsernameValidation(
    val valid: Boolean,
    val error: String? = null,
)

private val UsernamePattern = Regex("^[a-z][a-z0-9_.]{2,19}$")
private val ReservedUsernames = setOf(
    "dashboard", "login", "signup", "admin", "sell", "search", "profile",
    "messages", "chat", "wishlist", "notifications", "community", "verification",
    "terms", "privacy", "careers", "settings", "product", "feed",
    "nextbench", "help", "support", "about", "contact", "api", "app",
    "www", "mail", "blog", "explore", "trending", "marketplace",
    "null", "undefined", "mod", "moderator",
)

fun normalizeUsernameInput(value: String): String = value
    .lowercase()
    .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }
    .take(ProfileRepository.MaxUsernameLength)

fun validateUsername(username: String): UsernameValidation {
    val normalized = username.lowercase()
    return when {
        normalized.length < ProfileRepository.MinUsernameLength ->
            UsernameValidation(false, "Username must be at least 3 characters.")
        normalized.length > ProfileRepository.MaxUsernameLength ->
            UsernameValidation(false, "Username must be 20 characters or less.")
        !UsernamePattern.matches(normalized) ->
            UsernameValidation(false, "Use letters, numbers, underscores, or dots, starting with a letter.")
        ".." in normalized || "__" in normalized ->
            UsernameValidation(false, "Consecutive dots or underscores are not allowed.")
        normalized.endsWith('.') || normalized.endsWith('_') ->
            UsernameValidation(false, "A username cannot end with a dot or underscore.")
        normalized in ReservedUsernames -> UsernameValidation(false, "This username is reserved.")
        else -> UsernameValidation(true)
    }
}

@Singleton
class ProfileRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val uploader: CloudinaryUploader,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    fun observeProfile(uid: String): Flow<ProfileContent> = configuredFlow(uid) {
        val identityAndActivity = combine(
            refs.user(uid).snapshotFlow(),
            refs.products.whereEqualTo("sellerId", uid).snapshotFlow(),
            refs.posts.whereEqualTo("authorId", uid).snapshotFlow(),
        ) { userSnapshot, listingSnapshot, postSnapshot ->
            Triple(
                userSnapshot.takeIf(DocumentSnapshot::exists)
                    ?.toObject(UserData::class.java)
                    ?.copy(uid = uid),
                listingSnapshot.documents.mapNotNull(DocumentSnapshot::toMarketplaceProduct),
                postSnapshot.documents.mapNotNull(DocumentSnapshot::toProfilePost),
            )
        }
        val connections = combine(
            refs.follows.whereEqualTo("followingId", uid).snapshotFlow(),
            refs.follows.whereEqualTo("followerId", uid).snapshotFlow(),
        ) { followers, following -> followers.size() to following.size() }
        combine(identityAndActivity, connections) { content, counts ->
            buildProfileContent(
                user = content.first,
                listings = content.second,
                posts = content.third,
                followersCount = counts.first,
                followingCount = counts.second,
            )
        }
    }

    suspend fun updateFollowersOnly(uid: String, enabled: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        refs.user(uid).update("chatPrivacy.followersOnly", enabled).await()
    }

    /**
     * Persists the user's last-seen visibility preference. When [hidden] is true callers
     * reading another user's [UserData.lastSeen] or [UserData.online] should suppress
     * that information in the UI.
     */
    suspend fun updateHideLastSeen(uid: String, hidden: Boolean): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        refs.user(uid).update("hideLastSeen", hidden).await()
    }

    suspend fun isUsernameAvailable(uid: String, username: String): Result<Boolean> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val normalized = normalizeUsernameInput(username)
        require(validateUsername(normalized).valid) { validateUsername(normalized).error.orEmpty() }
        val snapshot = refs.usernames.document(normalized).get().await()
        !snapshot.exists() || snapshot.getString("userId") == uid
    }

    suspend fun updateProfile(uid: String, draft: ProfileUpdateDraft): Result<ProfileUpdateResult> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        validateProfileUpdate(draft)?.let { throw IllegalArgumentException(it) }

        val uploadedProfilePicture = draft.profilePictureFile?.let { file ->
            uploader.upload(
                file = file,
                folder = "nextbench/profiles/$uid",
                resourceType = CloudinaryResourceType.Image,
            ).url
        }
        val normalizedUsername = normalizeUsernameInput(draft.username)
        val cleanName = draft.name.trim()
        val cleanAbout = draft.about.trim().takeIf(String::isNotBlank)
        val userRef = refs.user(uid)

        userRef.firestore.runTransaction { transaction ->
            val userSnapshot = transaction.get(userRef)
            require(userSnapshot.exists()) { "Your profile no longer exists." }
            val currentUsername = userSnapshot.getString("username")
                ?.let(::normalizeUsernameInput)
                ?.takeIf(String::isNotBlank)
            val usernameChanged = currentUsername != normalizedUsername
            val targetUsernameRef = refs.usernames.document(normalizedUsername)

            if (usernameChanged) {
                val targetSnapshot = transaction.get(targetUsernameRef)
                require(!targetSnapshot.exists() || targetSnapshot.getString("userId") == uid) {
                    "Username is already taken."
                }
                val lastChange = userSnapshot.getTimestamp("lastUsernameChange")?.toDate()?.time
                val remaining = usernameCooldownRemaining(lastChange)
                require(currentUsername == null || remaining == 0L) {
                    "You can only change your username once every 30 days."
                }
            }

            if (usernameChanged) {
                currentUsername?.let { transaction.delete(refs.usernames.document(it)) }
                transaction.set(
                    targetUsernameRef,
                    mapOf(
                        "userId" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }

            val profilePicture = when {
                uploadedProfilePicture != null -> uploadedProfilePicture
                draft.removeProfilePicture -> null
                else -> userSnapshot.getString("profilePicture")
            }
            val updates = mutableMapOf<String, Any?>(
                "name" to cleanName,
                "about" to cleanAbout,
                "profilePicture" to profilePicture,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (usernameChanged) {
                updates["username"] = normalizedUsername
                updates["lastUsernameChange"] = FieldValue.serverTimestamp()
            }
            transaction.update(userRef, updates)
            ProfileUpdateResult(
                name = cleanName,
                about = cleanAbout,
                username = normalizedUsername,
                profilePicture = profilePicture,
            )
        }.await()
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw ProfileConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }

    companion object {
        const val MaxNameLength = 100
        const val MaxAboutLength = 500
        const val MinUsernameLength = 3
        const val MaxUsernameLength = 20
        const val UsernameCooldownMillis = 30L * 24L * 60L * 60L * 1_000L
    }
}

fun validateProfileUpdate(draft: ProfileUpdateDraft): String? = when {
    draft.name.isBlank() -> "Display name is required."
    draft.name.trim().length > ProfileRepository.MaxNameLength ->
        "Display name can be up to ${ProfileRepository.MaxNameLength} characters."
    draft.about.trim().length > ProfileRepository.MaxAboutLength ->
        "About can be up to ${ProfileRepository.MaxAboutLength} characters."
    draft.profilePictureFile != null && (!draft.profilePictureFile.isFile || draft.profilePictureFile.length() <= 0L) ->
        "The selected profile picture is unavailable."
    else -> validateUsername(normalizeUsernameInput(draft.username)).error
}

fun usernameCooldownRemaining(
    lastChangeMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): Long {
    if (lastChangeMillis == null) return 0L
    return (lastChangeMillis + ProfileRepository.UsernameCooldownMillis - nowMillis).coerceAtLeast(0L)
}

internal fun buildProfileContent(
    user: UserData?,
    listings: List<Product>,
    posts: List<Post>,
    followersCount: Int = 0,
    followingCount: Int = 0,
): ProfileContent = ProfileContent(
    user = user,
    listings = listings
        .sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }
        .distinctBy(Product::id),
    posts = posts
        .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }
        .distinctBy(Post::id),
    followersCount = followersCount.coerceAtLeast(0),
    followingCount = followingCount.coerceAtLeast(0),
)

private fun DocumentSnapshot.toProfilePost(): Post? = data?.plus("id" to id)?.toPost()

private class ProfileConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
