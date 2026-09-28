package com.nextbench.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.messaging.FirebaseMessaging
import com.nextbench.data.model.Notification
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@Singleton
class NotificationRepository @Inject constructor(
    private val authProvider: Provider<FirebaseAuth>,
    private val refsProvider: Provider<FirestoreRefs>,
    private val functionsProvider: Provider<NbFunctions>,
    private val messagingProvider: Provider<FirebaseMessaging>,
) {
    private val auth get() = authProvider.get()
    private val refs get() = refsProvider.get()
    private val functions get() = functionsProvider.get()
    private val messaging get() = messagingProvider.get()

    fun observeNotifications(uid: String): Flow<List<Notification>> = configuredFlow(uid) {
        refs.notifications
            .whereEqualTo("userId", uid)
            .snapshotFlow()
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull(DocumentSnapshot::toNextBenchNotification)
                    .sortedWith(
                        compareByDescending<Notification> { !it.read }
                            .thenByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE },
                    )
            }
    }

    suspend fun markRead(notificationId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val reference = refs.notifications.document(notificationId)
        val snapshot = reference.get().await()
        require(snapshot.getString("userId") == uid) { "This notification is no longer available." }
        reference.update("read", true).await()
    }

    suspend fun markAllRead(notificationIds: List<String>, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        if (notificationIds.isEmpty()) return@runCatching Unit
        val batch = refs.notifications.firestore.batch()
        notificationIds.distinct().forEach { id ->
            val reference = refs.notifications.document(id)
            batch.update(reference, mapOf("read" to true))
        }
        batch.commit().await()
    }

    suspend fun delete(notificationId: String, uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val reference = refs.notifications.document(notificationId)
        val snapshot = reference.get().await()
        require(snapshot.getString("userId") == uid) { "This notification is no longer available." }
        reference.delete().await()
    }

    suspend fun syncMessagingToken(uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val token = messaging.token.await().takeIf(String::isNotBlank) ?: return@runCatching
        check(functions.registerAndroidPushToken(token)) { "Push token registration was rejected." }
    }

    suspend fun registerMessagingToken(uid: String, token: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        require(token.isNotBlank()) { "Notification token is missing." }
        check(functions.registerAndroidPushToken(token)) { "Push token registration was rejected." }
    }

    suspend fun removeMessagingToken(uid: String): Result<Unit> = runCatching {
        ensureConfigured()
        requireAuthenticated(uid)
        val token = messaging.token.await().takeIf(String::isNotBlank) ?: return@runCatching
        check(functions.removeAndroidPushToken(token)) { "Push token removal was rejected." }
    }

    private fun requireAuthenticated(uid: String) {
        require(uid.isNotBlank() && auth.currentUser?.uid == uid) { "Your session expired. Sign in and try again." }
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw NotificationConfigurationException()
    }

    private fun <T> configuredFlow(uid: String, stream: () -> Flow<T>): Flow<T> = flow {
        ensureConfigured()
        requireAuthenticated(uid)
        emitAll(stream())
    }
}

internal fun DocumentSnapshot.toNextBenchNotification(): Notification? {
    if (!exists()) return null
    return data?.toNextBenchNotification(id)
}

internal fun Map<String, Any?>.toNextBenchNotification(id: String): Notification {
    return Notification(
        id = id,
        userId = this["userId"]?.toString().orEmpty(),
        type = this["type"]?.toString().orEmpty(),
        title = this["title"]?.toString().orEmpty(),
        message = this["message"]?.toString().orEmpty(),
        link = this["link"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
        postId = this["postId"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
        read = this["read"] as? Boolean ?: false,
        createdAt = this["createdAt"] as? com.google.firebase.Timestamp,
    )
}

private class NotificationConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
