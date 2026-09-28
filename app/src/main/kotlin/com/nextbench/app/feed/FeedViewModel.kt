package com.nextbench.app.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.FeedCursor
import com.nextbench.data.firebase.FeedMode
import com.nextbench.data.firebase.FeedOrderEntry
import com.nextbench.data.firebase.FeedRepository
import com.nextbench.data.firebase.PostVote
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FeedViewer(
    val uid: String? = null,
    val verified: Boolean = false,
) {
    val signedIn: Boolean get() = !uid.isNullOrBlank()
}

enum class FeedNoticeKind { Info, Success, Error }

enum class FeedDisplayMode { Editorial, List }

@Immutable
data class FeedNotice(
    val id: Long,
    val message: String,
    val kind: FeedNoticeKind,
)

@Immutable
data class FeedUiState(
    val posts: List<Post> = emptyList(),
    val products: List<Product> = emptyList(),
    val feedOrder: List<FeedOrderEntry> = emptyList(),
    val mode: FeedMode = FeedMode.ForYou,
    val displayMode: FeedDisplayMode = FeedDisplayMode.Editorial,
    val upvotedPostIds: Set<String> = emptySet(),
    val downvotedPostIds: Set<String> = emptySet(),
    val savedPostIds: Set<String> = emptySet(),
    val busyPostIds: Set<String> = emptySet(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingInteractions: Boolean = false,
    val interactionsReady: Boolean = true,
    val hasMore: Boolean = false,
    val initialError: String? = null,
    val paginationError: Boolean = false,
    val notice: FeedNotice? = null,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    private var viewer = FeedViewer()
    private var viewerSynced = false
    private var cursor: FeedCursor? = null
    private var contentGeneration = 0
    private var interactionGeneration = 0
    private var noticeId = 0L
    private var pageJob: Job? = null
    private var interactionJob: Job? = null
    private var upvoteDocumentIds: Map<String, String> = emptyMap()
    private var downvoteDocumentIds: Map<String, String> = emptyMap()
    private var saveDocumentIds: Map<String, String> = emptyMap()

    fun syncViewer(nextViewer: FeedViewer) {
        if (viewerSynced && viewer == nextViewer) return
        val identityChanged = !viewerSynced || viewer.uid != nextViewer.uid
        val wasSignedIn = viewer.signedIn
        viewer = nextViewer
        viewerSynced = true
        if (identityChanged) {
            resetInteractions(nextViewer.signedIn)
            if (!nextViewer.signedIn && wasSignedIn && state.value.mode == FeedMode.Following) {
                _state.update { it.copy(mode = FeedMode.ForYou) }
            }
            loadFirstPage(clearContent = true)
        }
        if (nextViewer.signedIn) loadInteractions(nextViewer.uid.orEmpty())
    }

    fun selectMode(mode: FeedMode) {
        if (mode == state.value.mode) return
        if (mode == FeedMode.Following && !viewer.signedIn) return
        _state.update { it.copy(mode = mode) }
        loadFirstPage(clearContent = true)
    }

    fun selectDisplayMode(displayMode: FeedDisplayMode) {
        if (state.value.displayMode == displayMode) return
        _state.update { it.copy(displayMode = displayMode) }
    }

    fun refresh() = loadFirstPage(clearContent = false)

    private fun loadFirstPage(clearContent: Boolean) {
        pageJob?.cancel()
        cursor = null
        val generation = ++contentGeneration
        val hasContent = !clearContent && (state.value.posts.isNotEmpty() || state.value.products.isNotEmpty())
        _state.update {
            it.copy(
                posts = if (clearContent) emptyList() else it.posts,
                products = if (clearContent) emptyList() else it.products,
                feedOrder = if (clearContent) emptyList() else it.feedOrder,
                isInitialLoading = !hasContent,
                isRefreshing = hasContent,
                isLoadingMore = false,
                initialError = null,
                paginationError = false,
            )
        }
        pageJob = viewModelScope.launch {
            repository.loadPage(state.value.mode).fold(
                onSuccess = { page ->
                    if (generation != contentGeneration) return@fold
                    val posts = if (viewer.signedIn) page.posts else page.posts.take(GuestPreviewLimit)
                    val products = if (viewer.signedIn) page.products else page.products.take(GuestProductPreviewLimit)
                    cursor = page.nextCursor
                    _state.update {
                        it.copy(
                            posts = posts.distinctBy(Post::id),
                            products = products.distinctBy(Product::id),
                            feedOrder = page.order.filter { entry ->
                                posts.any { it.id == entry.id } || products.any { it.id == entry.id }
                            },
                            isInitialLoading = false,
                            isRefreshing = false,
                            hasMore = viewer.signedIn && page.hasMorePosts,
                            initialError = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != contentGeneration) return@fold
                    val message = error.feedMessage()
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            hasMore = false,
                            initialError = message.takeUnless { hasContent },
                        )
                    }
                    if (hasContent) showNotice(message, FeedNoticeKind.Error)
                },
            )
        }
    }

    fun loadMore() {
        val snapshot = state.value
        val pageCursor = cursor ?: return
        if (!viewer.signedIn || snapshot.isInitialLoading || snapshot.isRefreshing ||
            snapshot.isLoadingMore || !snapshot.hasMore
        ) return
        val generation = contentGeneration
        _state.update { it.copy(isLoadingMore = true, paginationError = false) }
        pageJob = viewModelScope.launch {
            repository.loadPage(snapshot.mode, pageCursor).fold(
                onSuccess = { page ->
                    if (generation != contentGeneration) return@fold
                    val cursorAdvanced = page.nextCursor != pageCursor
                    cursor = page.nextCursor
                    _state.update {
                        it.copy(
                            posts = mergePosts(it.posts, page.posts),
                            products = mergeProducts(it.products, page.products),
                            feedOrder = mergeFeedOrder(it.feedOrder, page.order),
                            isLoadingMore = false,
                            hasMore = page.hasMorePosts &&
                                (cursorAdvanced || page.posts.isNotEmpty() || page.products.isNotEmpty()),
                            paginationError = false,
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != contentGeneration) return@fold
                    _state.update { it.copy(isLoadingMore = false, paginationError = true) }
                    showNotice(error.feedMessage(), FeedNoticeKind.Error)
                },
            )
        }
    }

    fun toggleVote(postId: String, requestedVote: PostVote): Boolean {
        val snapshot = state.value
        val post = snapshot.posts.firstOrNull { it.id == postId } ?: return false
        if (!viewer.signedIn || !viewer.verified || !snapshot.interactionsReady ||
            postId in snapshot.busyPostIds
        ) return false

        val preview = previewVote(
            post = post,
            wasUpvoted = postId in snapshot.upvotedPostIds,
            wasDownvoted = postId in snapshot.downvotedPostIds,
            requestedVote = requestedVote,
        )
        val originalUpvoteId = upvoteDocumentIds[postId]
        val originalDownvoteId = downvoteDocumentIds[postId]
        val operationUid = viewer.uid
        _state.update { current ->
            current.copy(
                posts = current.posts.replacePost(preview.post),
                upvotedPostIds = current.upvotedPostIds.withMembership(postId, preview.upvoted),
                downvotedPostIds = current.downvotedPostIds.withMembership(postId, preview.downvoted),
                busyPostIds = current.busyPostIds + postId,
            )
        }
        viewModelScope.launch {
            repository.setVote(
                postId = postId,
                vote = preview.vote,
                currentUpvoteId = originalUpvoteId,
                currentDownvoteId = originalDownvoteId,
            ).fold(
                onSuccess = { documents ->
                    if (viewer.uid != operationUid) return@fold
                    upvoteDocumentIds = upvoteDocumentIds.withEntry(postId, documents.upvoteId)
                    downvoteDocumentIds = downvoteDocumentIds.withEntry(postId, documents.downvoteId)
                    _state.update { it.copy(busyPostIds = it.busyPostIds - postId) }
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid) return@fold
                    _state.update { current ->
                        current.copy(
                            posts = current.posts.rollbackPost(preview.post, post),
                            upvotedPostIds = current.upvotedPostIds.rollbackMembership(
                                postId,
                                optimistic = preview.upvoted,
                                original = postId in snapshot.upvotedPostIds,
                            ),
                            downvotedPostIds = current.downvotedPostIds.rollbackMembership(
                                postId,
                                optimistic = preview.downvoted,
                                original = postId in snapshot.downvotedPostIds,
                            ),
                            busyPostIds = current.busyPostIds - postId,
                        )
                    }
                    showNotice(error.feedMessage(), FeedNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun toggleSave(postId: String): Boolean {
        val snapshot = state.value
        if (!viewer.signedIn || !snapshot.interactionsReady || postId in snapshot.busyPostIds) return false
        if (snapshot.posts.none { it.id == postId }) return false
        val wasSaved = postId in snapshot.savedPostIds
        val willSave = !wasSaved
        val originalSaveId = saveDocumentIds[postId]
        val operationUid = viewer.uid
        _state.update {
            it.copy(
                savedPostIds = it.savedPostIds.withMembership(postId, willSave),
                busyPostIds = it.busyPostIds + postId,
            )
        }
        viewModelScope.launch {
            repository.setSaved(postId, willSave, originalSaveId).fold(
                onSuccess = { saveId ->
                    if (viewer.uid != operationUid) return@fold
                    saveDocumentIds = saveDocumentIds.withEntry(postId, saveId)
                    _state.update { it.copy(busyPostIds = it.busyPostIds - postId) }
                    showNotice(
                        if (willSave) "Post saved" else "Removed from saved posts",
                        if (willSave) FeedNoticeKind.Success else FeedNoticeKind.Info,
                    )
                },
                onFailure = { error ->
                    if (viewer.uid != operationUid) return@fold
                    _state.update {
                        it.copy(
                            savedPostIds = it.savedPostIds.rollbackMembership(
                                postId,
                                optimistic = willSave,
                                original = wasSaved,
                            ),
                            busyPostIds = it.busyPostIds - postId,
                        )
                    }
                    showNotice(error.feedMessage(), FeedNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun retryInteractions() {
        viewer.uid?.let(::loadInteractions)
    }

    fun dismissNotice(id: Long) {
        _state.update { current ->
            if (current.notice?.id == id) current.copy(notice = null) else current
        }
    }

    private fun loadInteractions(uid: String) {
        interactionJob?.cancel()
        val generation = ++interactionGeneration
        _state.update { it.copy(interactionsReady = false, isLoadingInteractions = true) }
        interactionJob = viewModelScope.launch {
            repository.loadInteractions(uid).fold(
                onSuccess = { interactions ->
                    if (viewer.uid != uid || generation != interactionGeneration) return@fold
                    upvoteDocumentIds = interactions.upvoteDocumentIds
                    downvoteDocumentIds = interactions.downvoteDocumentIds
                    saveDocumentIds = interactions.saveDocumentIds
                    _state.update {
                        it.copy(
                            upvotedPostIds = interactions.upvotedPostIds,
                            downvotedPostIds = interactions.downvotedPostIds,
                            savedPostIds = interactions.savedPostIds,
                            interactionsReady = true,
                            isLoadingInteractions = false,
                        )
                    }
                },
                onFailure = { error ->
                    if (viewer.uid != uid || generation != interactionGeneration) return@fold
                    _state.update {
                        it.copy(interactionsReady = false, isLoadingInteractions = false)
                    }
                    showNotice(error.feedMessage(), FeedNoticeKind.Error)
                },
            )
        }
    }

    private fun resetInteractions(signedIn: Boolean) {
        interactionJob?.cancel()
        interactionGeneration++
        upvoteDocumentIds = emptyMap()
        downvoteDocumentIds = emptyMap()
        saveDocumentIds = emptyMap()
        _state.update {
            it.copy(
                upvotedPostIds = emptySet(),
                downvotedPostIds = emptySet(),
                savedPostIds = emptySet(),
                busyPostIds = emptySet(),
                interactionsReady = !signedIn,
                isLoadingInteractions = false,
            )
        }
    }

    private fun showNotice(message: String, kind: FeedNoticeKind) {
        val notice = FeedNotice(++noticeId, message, kind)
        _state.update { it.copy(notice = notice) }
    }
}

internal data class VotePreview(
    val post: Post,
    val vote: PostVote?,
    val upvoted: Boolean,
    val downvoted: Boolean,
)

internal fun previewVote(
    post: Post,
    wasUpvoted: Boolean,
    wasDownvoted: Boolean,
    requestedVote: PostVote,
): VotePreview {
    val nextVote = when (requestedVote) {
        PostVote.Up -> if (wasUpvoted) null else PostVote.Up
        PostVote.Down -> if (wasDownvoted) null else PostVote.Down
    }
    val upvoted = nextVote == PostVote.Up
    val downvoted = nextVote == PostVote.Down
    val upvoteDelta = upvoted.toInt() - wasUpvoted.toInt()
    val downvoteDelta = downvoted.toInt() - wasDownvoted.toInt()
    return VotePreview(
        post = post.copy(
            upvotesCount = (post.upvotesCount + upvoteDelta).coerceAtLeast(0),
            downvotesCount = (post.downvotesCount + downvoteDelta).coerceAtLeast(0),
        ),
        vote = nextVote,
        upvoted = upvoted,
        downvoted = downvoted,
    )
}

internal fun mergePosts(current: List<Post>, incoming: List<Post>): List<Post> =
    (current + incoming).distinctBy(Post::id)

internal fun mergeProducts(current: List<Product>, incoming: List<Product>): List<Product> =
    (current + incoming).distinctBy(Product::id)

internal fun mergeFeedOrder(
    current: List<FeedOrderEntry>,
    incoming: List<FeedOrderEntry>,
): List<FeedOrderEntry> = (current + incoming).distinctBy { "${it.type}:${it.id}" }

private fun Boolean.toInt(): Int = if (this) 1 else 0

private fun Set<String>.withMembership(id: String, present: Boolean): Set<String> =
    if (present) this + id else this - id

private fun Set<String>.rollbackMembership(
    id: String,
    optimistic: Boolean,
    original: Boolean,
): Set<String> = if ((id in this) == optimistic) withMembership(id, original) else this

private fun Map<String, String>.withEntry(id: String, documentId: String?): Map<String, String> =
    if (documentId == null) this - id else this + (id to documentId)

private fun List<Post>.replacePost(post: Post): List<Post> = map { current ->
    if (current.id == post.id) post else current
}

private fun List<Post>.rollbackPost(optimistic: Post, original: Post): List<Post> = map { current ->
    if (current.id == optimistic.id &&
        current.upvotesCount == optimistic.upvotesCount &&
        current.downvotesCount == optimistic.downvotesCount
    ) {
        current.copy(
            upvotesCount = original.upvotesCount,
            downvotesCount = original.downvotesCount,
        )
    } else {
        current
    }
}

private fun Throwable.feedMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load the community."
        raw.contains("UNAVAILABLE", ignoreCase = true) || raw.contains("network", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "This post is not available to your account."
        raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
            "Too many requests. Wait a moment and try again."
        raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.contains("NOT_FOUND", ignoreCase = true) || raw.contains("no longer exists", ignoreCase = true) ->
            "This post is no longer available."
        else -> "Something went wrong. Please try again."
    }
}

private const val GuestPreviewLimit = 5
private const val GuestProductPreviewLimit = 2
