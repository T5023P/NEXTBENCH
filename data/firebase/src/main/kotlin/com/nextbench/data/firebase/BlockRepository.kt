package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Owns the shared `blocks` relationship, mirroring the website's `src/lib/blocks.ts`.
 *
 * A block is a single document at [blockDocumentId] and nothing else; the `follows` rows are
 * cleaned up as part of the same operation because the website does so. `follow_edges` is a
 * server-materialized cache (`firestore.rules` sets `allow write: if false`) and is intentionally
 * never touched here.
 */
@Singleton
class BlockRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()

    /** Live block state for the viewer → target direction, so the UI reconciles without a reload. */
    fun observeBlockedByViewer(viewerId: String, targetId: String): Flow<Boolean> =
        configuredFlow(viewerId) {
            refs.blocks.document(blockDocumentId(viewerId, targetId)).snapshotFlow().map { it.exists() }
        }

    /**
     * Blocks [blockedId] on behalf of [blockerId], then removes the follow relationship in both
     * directions. The block document is written first on purpose: the `follows` delete rule only
     * permits the blocked member's row to be removed once `blocks/{blockerId}_{blockedId}` exists.
     */
    suspend fun blockUser(blockerId: String, blockedId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(blockerId)
        require(blockedId.isNotBlank() && blockedId != blockerId) { "This member cannot be blocked." }

        refs.blocks
            .document(blockDocumentId(blockerId, blockedId))
            .set(blockPayload(blockerId, blockedId))
            .await()
        removeFollows(blockerId, blockedId)
        Unit
    }

    /**
     * Removes the block. Follows are deliberately not restored, matching the website: unblocking
     * only deletes the block document.
     */
    suspend fun unblockUser(blockerId: String, blockedId: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(blockerId)
        require(blockedId.isNotBlank()) { "This member cannot be unblocked." }

        refs.blocks.document(blockDocumentId(blockerId, blockedId)).delete().await()
        Unit
    }

    private suspend fun removeFollows(blockerId: String, blockedId: String) {
        val references = followRemovalPairs(blockerId, blockedId).flatMap { (followerId, followingId) ->
            refs.follows
                .whereEqualTo("followerId", followerId)
                .whereEqualTo("followingId", followingId)
                .get()
                .await()
                .documents
                .map { it.reference }
        }
        if (references.isEmpty()) return
        val batch = refs.follows.firestore.batch()
        references.forEach { batch.delete(it) }
        batch.commit().await()
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw BlockConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }
}

/**
 * `blocks/{blockerId}_{blockedId}` — the identity the website writes and the security rules assert
 * (`blockId == request.auth.uid + '_' + incoming().blockedId`).
 */
internal fun blockDocumentId(blockerId: String, blockedId: String): String = "${blockerId}_${blockedId}"

/**
 * The canonical block document. The rules only accept exactly these three keys, and `createdAt`
 * must be the server timestamp.
 */
internal fun blockPayload(blockerId: String, blockedId: String): Map<String, Any?> = mapOf(
    "blockerId" to blockerId,
    "blockedId" to blockedId,
    "createdAt" to FieldValue.serverTimestamp(),
)

/**
 * The `(followerId, followingId)` follow rows a block removes, in the website's order: the
 * blocker's own follow first, then the reverse follow the block document just authorised.
 */
internal fun followRemovalPairs(blockerId: String, blockedId: String): List<Pair<String, String>> =
    listOf(blockerId to blockedId, blockedId to blockerId)

private class BlockConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
