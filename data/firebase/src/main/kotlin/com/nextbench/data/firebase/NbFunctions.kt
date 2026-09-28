package com.nextbench.data.firebase

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Callable boundary. The Functions instance is resolved lazily (`Provider`) so an
 * unconfigured build reaches each repository's `ensureConfigured()` guard instead of
 * crashing during injection.
 */
@Singleton
class NbFunctions @Inject constructor(
    private val functionsProvider: Provider<FirebaseFunctions>,
) {
    private val functions get() = functionsProvider.get()
    private suspend fun call(name: String, data: Any = emptyMap<String, Any?>()): Any? =
        functions.getHttpsCallable(name).call(data).await().getData()

    private suspend fun callMap(name: String, data: Any = emptyMap<String, Any?>()): Map<String, Any?> =
        call(name, data).asStringMap()

    suspend fun createNotification(params: Map<String, Any?>): Map<String, Any?> =
        callMap("createNotification", params)

    suspend fun getDiscoveryFeed(params: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        callMap("getDiscoveryFeed", params)

    suspend fun getRecommendedProducts(params: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> =
        callMap("getRecommendedProducts", params).mapList("products")

    suspend fun getSuggestedUsers(): List<Map<String, Any?>> =
        callMap("getSuggestedUsers").mapList("users")

    suspend fun searchDiscovery(params: Map<String, Any?>): Map<String, Any?> =
        callMap("searchDiscovery", params)

    suspend fun searchPublicUsers(params: Map<String, Any?>): List<Map<String, Any?>> =
        callMap("searchPublicUsers", params).mapList("users")

    suspend fun getPublicUsers(userIds: List<String>): List<Map<String, Any?>> =
        callMap("getPublicUsers", mapOf("userIds" to userIds.distinct().filter(String::isNotBlank).take(50)))
            .mapList("users")

    suspend fun registerAndroidPushToken(token: String): Boolean =
        callMap("registerAndroidPushToken", mapOf("token" to token))["success"] as? Boolean ?: false

    suspend fun removeAndroidPushToken(token: String): Boolean =
        callMap("removeAndroidPushToken", mapOf("token" to token))["success"] as? Boolean ?: false

    suspend fun getAndroidPublicProfileStats(userId: String): Map<String, Any?> =
        callMap("getAndroidPublicProfileStats", mapOf("userId" to userId))

    suspend fun getPublicProfile(userId: String): Map<String, Any?>? =
        callMap("getPublicProfile", mapOf("userId" to userId))["user"].asNullableStringMap()

    suspend fun getPublicProfileContent(userId: String): Map<String, Any?> =
        callMap("getPublicProfileContent", mapOf("userId" to userId))

    suspend fun getPostReplies(postId: String): List<Map<String, Any?>> =
        callMap("getPostReplies", mapOf("postId" to postId)).mapList("replies")

    suspend fun getProductReviews(productId: String): List<Map<String, Any?>> =
        callMap("getProductReviews", mapOf("productId" to productId)).mapList("reviews")

    suspend fun createProductReview(params: Map<String, Any?>): String =
        callMap("createProductReview", params)["id"]?.toString().orEmpty()

    suspend fun createInviteCode(): String =
        callMap("createInviteCode")["code"]?.toString().orEmpty()

    suspend fun submitInviteCode(referralCode: String): Map<String, Any?> =
        callMap("submitInviteCode", mapOf("referralCode" to referralCode))

    suspend fun lookupReferralCode(code: String): String? =
        callMap("lookupReferralCode", mapOf("code" to code))["userId"]?.toString()

    suspend fun isReferralCodeAvailable(code: String): Boolean =
        callMap("isReferralCodeAvailable", mapOf("code" to code))["available"] as? Boolean ?: false

    suspend fun sendAuthOtpEmail(email: String): Boolean =
        callMap("sendAuthOtpEmail", mapOf("email" to email))["success"] as? Boolean ?: false

    suspend fun verifyAuthOtpEmail(params: Map<String, Any?>): Map<String, Any?> =
        callMap("verifyAuthOtpEmail", params)

    suspend fun getLandingStats(): Map<String, Any?> = callMap("getLandingStats")

    suspend fun getLinkPreview(url: String): Map<String, Any?> =
        callMap("getLinkPreview", mapOf("url" to url))

    suspend fun deletePostCascade(postId: String): Boolean =
        callMap("deletePostCascade", mapOf("postId" to postId))["success"] as? Boolean ?: false

    /**
     * Requests server-authoritative account deletion.
     *
     * The cascade (profile, private documents, identity evidence, push tokens, club
     * membership, chat participation, retained-record anonymisation, and finally the Auth
     * account) runs in the callable with the Admin SDK. The client only confirms intent,
     * because Firestore rules deny client deletes and a partial client-side cascade could
     * look successful while leaving artifacts behind.
     */
    suspend fun requestAccountDeletion(confirmation: String): Map<String, Any?> =
        callMap("requestAccountDeletion", accountDeletionRequestPayload(confirmation))
}

/**
 * The only field the deletion callable accepts. `confirmation` is trimmed so a pasted trailing
 * newline does not fail the deliberate typed confirmation; the server still requires exactly
 * `DELETE`, and everything else about the deletion is server-owned.
 */
internal fun accountDeletionRequestPayload(confirmation: String): Map<String, Any?> =
    mapOf("confirmation" to confirmation.trim())

internal fun Any?.asStringMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }.orEmpty()

private fun Any?.asNullableStringMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }

internal fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
    (get(key) as? List<*>)?.mapNotNull { value ->
        (value as? Map<*, *>)?.entries?.associate { (entryKey, entryValue) ->
            entryKey.toString() to entryValue
        }
    }.orEmpty()
