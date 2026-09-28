package com.nextbench.app.post

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.app.feed.previewVote
import com.nextbench.data.firebase.FeedRepository
import com.nextbench.data.firebase.PostDetailRepository
import com.nextbench.data.firebase.PostVote
import com.nextbench.data.model.Post
import com.nextbench.data.model.PostReply
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PostDetailViewer(
    val uid: String? = null,
    val verified: Boolean = false,
) {
    val signedIn: Boolean get() = !uid.isNullOrBlank()
}

@Immutable
data class ReplyTarget(
    val id: String,
    val authorName: String,
)

enum class PostDetailNoticeKind { Info, Success, Error }

@Immutable
data class PostDetailNotice(
    val id: Long,
    val message: String,
    val kind: PostDetailNoticeKind,
)

@Immutable
data class PostDetailUiState(
    val post: Post? = null,
    val replies: List<PostReply> = emptyList(),
    val composerText: String = "",
    val replyTarget: ReplyTarget? = null,
    val upvoted: Boolean = false,
    val downvoted: Boolean = false,
    val saved: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingReplies: Boolean = false,
    val isLoadingInteractions: Boolean = false,
    val interactionsReady: Boolean = true,
    val interactionBusy: Boolean = false,
    val isSubmitting: Boolean = false,
    val initialError: String? = null,
    val repliesError: String? = null,
    val notice: PostDetailNotice? = null,
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val detailRepository: PostDetailRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {
    private val postId: String = requireNotNull(savedStateHandle["postId"]) {
        "Post detail requires a postId navigation argument."
    }
    private val _state = MutableStateFlow(PostDetailUiState())
    val state: StateFlow<PostDetailUiState> = _state.asStateFlow()

    private var viewer = PostDetailViewer()
    private var viewerUser: UserData? = null
    private var viewerSynced = false
    private var loadGeneration = 0
    private var replyGeneration = 0
    private var interactionGeneration = 0
    private var noticeId = 0L
    private var postJob: Job? = null
    private var replyJob: Job? = null
    private var interactionJob: Job? = null
    private var upvoteDocumentId: String? = null
    private var downvoteDocumentId: String? = null
    private var saveDocumentId: String? = null

    init {
        loadPost(clearContent = true)
    }

    fun syncViewer(user: UserData?) {
        val nextViewer = PostDetailViewer(user?.uid, user?.verified == true)
        if (viewerSynced && viewer == nextViewer && viewerUser == user) return
        val identityChanged = !viewerSynced || viewer.uid != nextViewer.uid
        viewer = nextViewer
        viewerUser = user
        viewerSynced = true
        if (!identityChanged) return

        resetInteractions(nextViewer.signedIn)
        if (nextViewer.signedIn) {
            loadReplies()
            loadInteractions(nextViewer.uid.orEmpty())
            if (state.value.post == null && state.value.initialError != null) loadPost(clearContent = true)
        } else {
            replyJob?.cancel()
            replyGeneration++
            _state.update {
                it.copy(
                    replies = emptyList(),
                    isLoadingReplies = false,
                    repliesError = null,
                    composerText = "",
                    replyTarget = null,
                )
            }
        }
    }

    fun refresh() {
        loadPost(clearContent = false)
        if (viewer.signedIn) loadReplies()
    }

    fun retryPost() = loadPost(clearContent = true)

    fun retryReplies() {
        if (viewer.signedIn) loadReplies()
    }

    fun setComposerText(value: String) {
        _state.update { it.copy(composerText = value.take(PostDetailRepository.ReplyCharacterLimit)) }
    }

    fun startReply(reply: PostReply) {
        _state.update {
            it.copy(replyTarget = ReplyTarget(reply.id, reply.authorName.ifBlank { "Student" }))
        }
    }

    fun clearReplyTarget() {
        _state.update { it.copy(replyTarget = null) }
    }

    fun submitReply(): Boolean {
        val snapshot = state.value
        val post = snapshot.post ?: return false
        val author = viewerUser ?: return false
        if (!viewer.signedIn || !viewer.verified || snapshot.isSubmitting) return false
        val content = snapshot.composerText.trim()
        if (content.isEmpty()) return false
        val parent = snapshot.replyTarget?.id?.let { targetId ->
            snapshot.replies.firstOrNull { it.id == targetId }
        }
        if (snapshot.replyTarget != null && parent == null) {
            showNotice("That reply is no longer available.", PostDetailNoticeKind.Error)
            _state.update { it.copy(replyTarget = null) }
            return false
        }

        _state.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            detailRepository.createReply(post, author, content, parent).fold(
                onSuccess = { created ->
                    _state.update { current ->
                        val updatedConversation = appendCreatedReply(
                            replies = current.replies,
                            created = created.reply,
                            parentRepliesCount = created.parentRepliesCount,
                        )
                        current.copy(
                            post = current.post?.copy(repliesCount = created.postRepliesCount),
                            replies = updatedConversation,
                            composerText = "",
                            replyTarget = null,
                            isSubmitting = false,
                            repliesError = null,
                        )
                    }
                    showNotice("Reply posted", PostDetailNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isSubmitting = false) }
                    showNotice(error.postDetailMessage(), PostDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun toggleVote(requestedVote: PostVote): Boolean {
        val snapshot = state.value
        val post = snapshot.post ?: return false
        if (!viewer.signedIn || !viewer.verified || !snapshot.interactionsReady || snapshot.interactionBusy) {
            return false
        }
        val preview = previewVote(post, snapshot.upvoted, snapshot.downvoted, requestedVote)
        val originalUpvoteId = upvoteDocumentId
        val originalDownvoteId = downvoteDocumentId
        val operationUid = viewer.uid
        _state.update {
            it.copy(
                post = preview.post,
                upvoted = preview.upvoted,
                downvoted = preview.downvoted,
                interactionBusy = true,
            )
        }
        viewModelScope.launch {
            feedRepository.setVote(
                postId = post.id,
                vote = preview.vote,
                currentUpvoteId = originalUpvoteId,
                currentDownvoteId = originalDownvoteId,
            ).fold(
                onSuccess = { ids ->
                    if (viewer.uid != operationUid) return@fold
                    upvoteDocumentId = ids.upvoteId
                    downvoteDocumentId = ids.downvoteId
                    _state.update { it.copy(interactionBusy = false) }
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid) return@fold
                    _state.update { current ->
                        val unchanged = current.post?.upvotesCount == preview.post.upvotesCount &&
                            current.post.downvotesCount == preview.post.downvotesCount
                        current.copy(
                            post = if (unchanged) post else current.post,
                            upvoted = if (unchanged) snapshot.upvoted else current.upvoted,
                            downvoted = if (unchanged) snapshot.downvoted else current.downvoted,
                            interactionBusy = false,
                        )
                    }
                    showNotice(error.postDetailMessage(), PostDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun toggleSave(): Boolean {
        val snapshot = state.value
        val post = snapshot.post ?: return false
        if (!viewer.signedIn || !snapshot.interactionsReady || snapshot.interactionBusy) return false
        val wasSaved = snapshot.saved
        val willSave = !wasSaved
        val originalSaveId = saveDocumentId
        val operationUid = viewer.uid
        _state.update { it.copy(saved = willSave, interactionBusy = true) }
        viewModelScope.launch {
            feedRepository.setSaved(post.id, willSave, originalSaveId).fold(
                onSuccess = { id ->
                    if (viewer.uid != operationUid) return@fold
                    saveDocumentId = id
                    _state.update { it.copy(interactionBusy = false) }
                    showNotice(
                        if (willSave) "Post saved" else "Removed from saved posts",
                        if (willSave) PostDetailNoticeKind.Success else PostDetailNoticeKind.Info,
                    )
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid) return@fold
                    _state.update { current ->
                        current.copy(
                            saved = if (current.saved == willSave) wasSaved else current.saved,
                            interactionBusy = false,
                        )
                    }
                    showNotice(error.postDetailMessage(), PostDetailNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun dismissNotice(id: Long) {
        _state.update { current ->
            if (current.notice?.id == id) current.copy(notice = null) else current
        }
    }

    private fun loadPost(clearContent: Boolean) {
        postJob?.cancel()
        val generation = ++loadGeneration
        val hasContent = !clearContent && state.value.post != null
        _state.update {
            it.copy(
                post = if (clearContent) null else it.post,
                isInitialLoading = !hasContent,
                isRefreshing = hasContent,
                initialError = null,
            )
        }
        postJob = viewModelScope.launch {
            detailRepository.loadPost(postId).fold(
                onSuccess = { post ->
                    if (generation != loadGeneration) return@fold
                    _state.update {
                        it.copy(
                            post = post,
                            isInitialLoading = false,
                            isRefreshing = false,
                            initialError = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != loadGeneration) return@fold
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            initialError = error.postDetailMessage().takeUnless { hasContent },
                        )
                    }
                    if (hasContent) showNotice(error.postDetailMessage(), PostDetailNoticeKind.Error)
                },
            )
        }
    }

    private fun loadReplies() {
        replyJob?.cancel()
        val generation = ++replyGeneration
        val operationUid = viewer.uid
        _state.update { it.copy(isLoadingReplies = true, repliesError = null) }
        replyJob = viewModelScope.launch {
            detailRepository.loadReplies(postId).fold(
                onSuccess = { replies ->
                    if (viewer.uid != operationUid || generation != replyGeneration) return@fold
                    _state.update {
                        it.copy(replies = replies, isLoadingReplies = false, repliesError = null)
                    }
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid || generation != replyGeneration) return@fold
                    _state.update {
                        it.copy(isLoadingReplies = false, repliesError = error.postDetailMessage())
                    }
                },
            )
        }
    }

    private fun loadInteractions(uid: String) {
        interactionJob?.cancel()
        val generation = ++interactionGeneration
        _state.update { it.copy(interactionsReady = false, isLoadingInteractions = true) }
        interactionJob = viewModelScope.launch {
            feedRepository.loadInteractions(uid).fold(
                onSuccess = { interactions ->
                    if (viewer.uid != uid || generation != interactionGeneration) return@fold
                    upvoteDocumentId = interactions.upvoteDocumentIds[postId]
                    downvoteDocumentId = interactions.downvoteDocumentIds[postId]
                    saveDocumentId = interactions.saveDocumentIds[postId]
                    _state.update {
                        it.copy(
                            upvoted = upvoteDocumentId != null,
                            downvoted = downvoteDocumentId != null,
                            saved = saveDocumentId != null,
                            interactionsReady = true,
                            isLoadingInteractions = false,
                        )
                    }
                },
                onFailure = { error ->
                    if (viewer.uid != uid || generation != interactionGeneration) return@fold
                    _state.update { it.copy(interactionsReady = false, isLoadingInteractions = false) }
                    showNotice(error.postDetailMessage(), PostDetailNoticeKind.Error)
                },
            )
        }
    }

    private fun resetInteractions(signedIn: Boolean) {
        interactionJob?.cancel()
        interactionGeneration++
        upvoteDocumentId = null
        downvoteDocumentId = null
        saveDocumentId = null
        _state.update {
            it.copy(
                upvoted = false,
                downvoted = false,
                saved = false,
                interactionBusy = false,
                interactionsReady = !signedIn,
                isLoadingInteractions = false,
            )
        }
    }

    private fun showNotice(message: String, kind: PostDetailNoticeKind) {
        _state.update { it.copy(notice = PostDetailNotice(++noticeId, message, kind)) }
    }
}

@Immutable
data class ReplyRow(
    val reply: PostReply,
    val depth: Int,
    val parentAuthorName: String? = null,
)

internal fun flattenReplies(replies: List<PostReply>): List<ReplyRow> {
    if (replies.isEmpty()) return emptyList()
    val ordered = replies.sortedWith(compareBy<PostReply> { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }.thenBy { it.id })
    val byId = ordered.associateBy(PostReply::id)
    val children = ordered.groupBy(PostReply::parentId)
    val emitted = mutableSetOf<String>()
    val rows = mutableListOf<ReplyRow>()

    fun emit(reply: PostReply, depth: Int, lineage: Set<String>) {
        if (!emitted.add(reply.id)) return
        val parent = reply.parentId?.let(byId::get)
        rows += ReplyRow(
            reply = reply,
            depth = depth.coerceAtMost(MaxReplyIndent),
            parentAuthorName = parent?.authorName?.takeIf(String::isNotBlank),
        )
        if (reply.id in lineage) return
        children[reply.id].orEmpty().forEach { child -> emit(child, depth + 1, lineage + reply.id) }
    }

    ordered.filter { it.parentId == null || it.parentId !in byId }.forEach { emit(it, 0, emptySet()) }
    ordered.filterNot { it.id in emitted }.forEach { emit(it, 0, emptySet()) }
    return rows
}

internal fun appendCreatedReply(
    replies: List<PostReply>,
    created: PostReply,
    parentRepliesCount: Int? = null,
): List<PostReply> =
    replies.map { reply ->
        if (reply.id == created.parentId) {
            reply.copy(repliesCount = parentRepliesCount ?: (reply.repliesCount + 1))
        } else {
            reply
        }
    } + created

internal fun Throwable.postDetailMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load this post."
        raw.contains("UNAVAILABLE", ignoreCase = true) || raw.contains("network", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "This post is not available to your account."
        raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
            "Too many requests. Wait a moment and try again."
        raw.contains("UNAUTHENTICATED", ignoreCase = true) ||
            raw.contains("Sign in", ignoreCase = true) ->
            "Sign in to view and join this conversation."
        raw.contains("NOT_FOUND", ignoreCase = true) ||
            raw.contains("no longer exists", ignoreCase = true) ||
            raw.contains("not publicly available", ignoreCase = true) ->
            "This post is no longer available."
        raw.contains("up to", ignoreCase = true) || raw.contains("Write a reply", ignoreCase = true) ||
            raw.contains("Verify", ignoreCase = true) -> raw
        else -> "Something went wrong. Please try again."
    }
}

private const val MaxReplyIndent = 3
