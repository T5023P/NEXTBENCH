package com.nextbench.app.chat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.nextbench.data.firebase.ChatRepository
import com.nextbench.data.model.UserData
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Handles the inline "Reply" action from a chat heads-up notification.
 *
 * Reads the reply text written into the [RemoteInput], loads the current
 * user's profile from Firestore, calls [ChatRepository.sendText], and then
 * dismisses the notification so the next incoming message shows a fresh one.
 */
@AndroidEntryPoint
class ChatReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var auth: FirebaseAuth

    override fun onReceive(context: Context, intent: Intent) {
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty) ?: return

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            ?.takeIf(String::isNotEmpty) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                // Load the sender's name and avatar for the message payload.
                val snap = FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
                val sender = UserData(
                    uid = uid,
                    name = snap.getString("name") ?: "Student",
                    profilePicture = snap.getString("profilePicture"),
                )
                chatRepository.sendText(roomId = roomId, sender = sender, text = replyText)
                // Dismiss so the next message triggers a fresh notification.
                context.getSystemService(NotificationManager::class.java)?.cancel(notifId)
            } catch (_: Exception) {
                // Swallow silently — the user can still open the app to retry.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.nextbench.app.ACTION_CHAT_REPLY"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}
