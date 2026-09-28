package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.nextbench.data.model.UserData
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class InviteContent(
    val referralCode: String?,
    val referralCount: Int,
    val referredBy: String?,
)

@Singleton
class InviteRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()

    fun observeInvite(uid: String): Flow<InviteContent> = configuredFlow(uid) {
        refs.user(uid).snapshotFlow().map { snapshot -> snapshot.toInviteContent() }
    }

    suspend fun createCode(uid: String): Result<String> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        functions.createInviteCode().trim().also { require(it.isNotBlank()) { "Invite code could not be generated." } }
    }

    suspend fun redeemCode(uid: String, code: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val normalized = code.trim().uppercase()
        require(normalized.isNotBlank()) { "Enter an invite code first." }
        val response = functions.submitInviteCode(normalized)
        require(response["success"] as? Boolean == true) { "Invite code could not be applied." }
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) {
            "Your session expired. Sign in and try again."
        }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw InviteConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }
}

internal fun DocumentSnapshot.toInviteContent(): InviteContent =
    data?.toInviteContent() ?: InviteContent(referralCode = null, referralCount = 0, referredBy = null)

internal fun Map<String, Any?>.toInviteContent(): InviteContent = InviteContent(
    referralCode = get("referralCode")?.toString()?.trim()?.takeIf(String::isNotBlank),
    referralCount = (get("referralCount") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
    referredBy = get("referredBy")?.toString()?.trim()?.takeIf(String::isNotBlank),
)

private class InviteConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
