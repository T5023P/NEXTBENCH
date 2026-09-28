package com.nextbench.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nextbench.app.chat.ChatReplyReceiver
import com.nextbench.data.firebase.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NbMessagingService : FirebaseMessagingService() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var repository: NotificationRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    override fun onNewToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        serviceScope.launch {
            repository.registerMessagingToken(uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"].orEmpty()
        val title = message.notification?.title ?: message.data["title"] ?: "NextBench update"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: "You have a new update."
        val link = message.data["link"].orEmpty()

        if (type == "new_message") {
            showChatNotification(
                title = title,
                body = body,
                link = link,
                roomId = message.data["roomId"].orEmpty(),
                senderName = message.data["senderName"].orEmpty().ifBlank { title },
            )
        } else {
            showGenericNotification(title = title, body = body, link = link, message = message)
        }
    }

    // ── Chat notification (WhatsApp-style heads-up + inline reply) ────────────

    // Lint's MissingPermission check cannot follow the guard into canPostNotification().
    // The runtime check (POST_NOTIFICATIONS on API 33+, areNotificationsEnabled() on all)
    // runs at the top of this method before notify() is ever reached.
    @SuppressLint("MissingPermission")
    private fun showChatNotification(
        title: String,
        body: String,
        link: String,
        roomId: String,
        senderName: String,
    ) {
        if (!canPostNotification()) return

        // Per-room notification ID so messages in the same chat stack together.
        val notifId = if (roomId.isNotBlank()) roomId.hashCode() else body.hashCode()

        // Tap action — open the chat room
        val deepLink = link.toAppDeepLink()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (deepLink != null) data = android.net.Uri.parse(deepLink)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Inline reply action
        val remoteInput = RemoteInput.Builder(ChatReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyBroadcast = Intent(this, ChatReplyReceiver::class.java).apply {
            action = ChatReplyReceiver.ACTION_REPLY
            putExtra(ChatReplyReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(ChatReplyReceiver.EXTRA_NOTIF_ID, notifId)
        }
        // FLAG_MUTABLE is required on Android 12+ for RemoteInput results to be written into the intent.
        val replyPendingIntent = PendingIntent.getBroadcast(
            this, notifId, replyBroadcast,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // MessagingStyle gives the WhatsApp-style conversation bubble
        val sender = Person.Builder().setName(senderName.ifBlank { "Someone" }).build()
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage(body, System.currentTimeMillis(), sender)

        val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        runCatching { NotificationManagerCompat.from(this).notify(notifId, notification) }
    }

    // ── Generic notification (post activity, marketplace, etc.) ──────────────

    // See showChatNotification: canPostNotification() performs the runtime permission check.
    @SuppressLint("MissingPermission")
    private fun showGenericNotification(
        title: String,
        body: String,
        link: String,
        message: RemoteMessage,
    ) {
        if (!canPostNotification()) return
        val deepLink = link.toAppDeepLink()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (deepLink != null) data = android.net.Uri.parse(deepLink)
        }
        val notifId = message.data["notificationId"]?.hashCode()
            ?: message.messageId?.hashCode()
            ?: body.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(notifId, notification) }
    }

    private fun canPostNotification(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        internal const val ChannelId = "nextbench_updates"
        const val CHAT_CHANNEL_ID = "nextbench_messages"
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

internal fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)

    // IMPORTANCE_HIGH → enables heads-up (peek) notifications for messages
    manager.createNotificationChannel(
        NotificationChannel(
            NbMessagingService.CHAT_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Direct messages and chat"
            enableVibration(true)
        },
    )

    // General updates channel (posts, marketplace, account)
    manager.createNotificationChannel(
        NotificationChannel(
            NbMessagingService.ChannelId,
            "NextBench updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Marketplace updates, post activity, and account notifications"
        },
    )
}

internal fun String.toAppDeepLink(): String? {
    val value = trim()
    if (value.isBlank()) return null
    if (value.startsWith("nextbench://")) return value
    val normalized = value
        .removePrefix("https://nextbench.in/")
        .removePrefix("http://nextbench.in/")
        .removePrefix("/")
    val destination = if (normalized == "dashboard") "community" else normalized
    return destination.takeIf(String::isNotBlank)?.let { "nextbench://$it" }
}
