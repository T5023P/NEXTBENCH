package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.nextbench.data.model.UserData
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class NewPostDraft(
    val type: String,
    val title: String,
    val content: String,
    val privacy: String,
    val anonymous: Boolean,
    val images: List<File> = emptyList(),
)

data class PostUploadProgress(
    val completed: Int,
    val total: Int,
    val label: String,
) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

@Singleton
class PostComposerRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val uploader: CloudinaryUploader,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    suspend fun publish(
        user: UserData,
        draft: NewPostDraft,
        onProgress: suspend (PostUploadProgress) -> Unit = {},
    ): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(user.uid)
        validateDraft(user, draft)

        val imageUploads = mutableListOf<CloudinaryResult>()
        draft.images.forEachIndexed { index, file ->
            onProgress(PostUploadProgress(index, draft.images.size, "Uploading image ${index + 1} of ${draft.images.size}"))
            val upload = withContext(Dispatchers.IO) {
                uploader.upload(file, "nextbench/posts/${user.uid}", CloudinaryResourceType.Image)
            }
            imageUploads += upload
            onProgress(PostUploadProgress(index + 1, draft.images.size, "Uploading image ${index + 1} of ${draft.images.size}"))
        }

        val anonymous = draft.type == PostType.Confession.raw && draft.anonymous
        val postRef = refs.posts.document()
        postRef.set(
            newPostPayload(
                user = user,
                draft = draft,
                uploads = imageUploads,
                anonymous = anonymous,
            ),
        ).await()
        postRef.id
    }

    private fun validateDraft(user: UserData, draft: NewPostDraft) {
        require(user.verified) { "Your account must be verified before posting." }
        require(draft.type in PostType.entries.map(PostType::raw)) { "Choose a valid post type." }
        require(draft.privacy in PostPrivacy.entries.map(PostPrivacy::raw)) { "Choose a valid privacy setting." }
        require(draft.title.trim().length <= MaxTitleLength) { "Titles can be up to $MaxTitleLength characters." }
        require(draft.content.trim().length <= MaxContentLength) { "Posts can be up to $MaxContentLength characters." }
        require(draft.title.isNotBlank() || draft.content.isNotBlank() || draft.images.isNotEmpty()) {
            "Add a title, message, or image before publishing."
        }
        require(draft.images.size <= MaxImages) { "You can attach up to $MaxImages images." }
        if (draft.type == PostType.Confession.raw && draft.anonymous) {
            require(!user.anonymousPersonaName.isNullOrBlank()) { "Set an anonymous persona before posting anonymously." }
        }
        require(draft.images.all { it.isFile && it.length() > 0L }) { "One of the selected images is unavailable." }
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw PostComposerConfigurationException()
    }

    companion object {
        const val MaxImages = 4
        const val MaxTitleLength = 200
        const val MaxContentLength = 5_000
    }
}

enum class PostType(val raw: String, val label: String, val description: String) {
    Info("info", "School info", "Useful updates for your campus"),
    Notes("notes", "Notes", "Share study material and explainers"),
    Event("event", "Event", "Let people know what is happening"),
    Confession("confession", "Anonymous", "Say what you need to say privately"),
    Others("others", "Something else", "Start a conversation"),
}

enum class PostPrivacy(val raw: String, val label: String) {
    Public("public", "Everyone"),
    Private("private", "Friends only"),
}

internal fun newPostPayload(
    user: UserData,
    draft: NewPostDraft,
    uploads: List<CloudinaryResult>,
    anonymous: Boolean = draft.type == PostType.Confession.raw && draft.anonymous,
): Map<String, Any?> {
    val imageUrls = uploads.map(CloudinaryResult::url)
    val imageDetails = uploads.map { upload ->
        mapOf("url" to upload.url, "w" to upload.width, "h" to upload.height)
    }
    return buildMap {
        put("title", draft.title.trim())
        put("content", draft.content.trim())
        put("type", draft.type)
        put("isAnonymous", anonymous)
        put("personaName", if (anonymous) user.anonymousPersonaName?.takeIf(String::isNotBlank) else null)
        put("reactionsCount", if (draft.type == PostType.Confession.raw) emptyMap<String, Int>() else null)
        put("city", user.city.ifBlank { null })
        put("school", user.school.trim())
        put("authorId", user.uid)
        put("authorName", if (anonymous) user.anonymousPersonaName?.takeIf(String::isNotBlank) ?: "Anonymous" else user.name.ifBlank { "Student" })
        put("authorProfilePicture", if (anonymous) null else user.profilePicture)
        put("status", "pending")
        put("privacy", draft.privacy)
        put("imageUrls", imageUrls)
        put("imageWidth", uploads.firstOrNull()?.width)
        put("imageHeight", uploads.firstOrNull()?.height)
        put("imagesDetailed", imageDetails)
        put("upvotesCount", 0)
        put("downvotesCount", 0)
        put("repliesCount", 0)
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }
}

private class PostComposerConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
