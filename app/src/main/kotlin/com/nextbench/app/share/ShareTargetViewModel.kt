package com.nextbench.app.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.app.chat.ChatMediaStore
import com.nextbench.app.chat.PreparedChatAttachment
import com.nextbench.data.firebase.ChatRepository
import com.nextbench.data.firebase.ClubRepository
import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.ForwardTargetType
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareTargetUiState(
    val text: String = "",
    val attachments: List<PreparedChatAttachment> = emptyList(),
    val targets: List<ForwardTarget> = emptyList(),
    val selectedTarget: ForwardTarget? = null,
    val query: String = "",
    val isPreparing: Boolean = false,
    val isLoadingTargets: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
) {
    val visibleTargets: List<ForwardTarget>
        get() = query.trim().takeIf(String::isNotBlank)?.let { search ->
            targets.filter { it.name.contains(search, ignoreCase = true) }
        } ?: targets

    val canSend: Boolean
        get() = selectedTarget != null && (text.isNotBlank() || attachments.isNotEmpty()) && !isPreparing && !isSending
}

@HiltViewModel
class ShareTargetViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val clubRepository: ClubRepository,
    private val mediaStore: ChatMediaStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareTargetUiState())
    val state: StateFlow<ShareTargetUiState> = _state.asStateFlow()
    private var loadedForUserId: String? = null
    private var activeIntent: Intent? = null
    private var lastPreparedIntent: Intent? = null
    private var queuedShare: Pair<Intent, (Intent) -> Unit>? = null

    fun sync(user: UserData?, intent: Intent?, onIntentConsumed: (Intent) -> Unit) {
        val currentUser = user ?: return
        if (loadedForUserId != currentUser.uid) {
            loadedForUserId = currentUser.uid
            loadTargets(currentUser.uid)
        }
        if (intent == null || intent === activeIntent || intent === lastPreparedIntent || intent === queuedShare?.first) return
        if (state.value.isPreparing) {
            queuedShare = intent to onIntentConsumed
            return
        }
        prepareIntent(intent, onIntentConsumed)
    }

    fun onMissingIntent() {
        if (lastPreparedIntent != null || state.value.text.isNotBlank() || state.value.attachments.isNotEmpty()) return
        _state.update { it.copy(error = "Share something from another app to choose a destination here.") }
    }

    fun setText(value: String) = _state.update { it.copy(text = value.take(ChatRepository.MessageCharacterLimit), error = null) }
    fun setQuery(value: String) = _state.update { it.copy(query = value.take(80)) }
    fun selectTarget(target: ForwardTarget) = _state.update { it.copy(selectedTarget = target, error = null) }

    fun removeAttachment(index: Int) {
        if (state.value.isSending) return
        val attachment = state.value.attachments.getOrNull(index) ?: return
        attachment.file.delete()
        _state.update { it.copy(attachments = it.attachments.filterIndexed { itemIndex, _ -> itemIndex != index }) }
    }

    fun send(user: UserData, onSent: (ForwardTarget) -> Unit): Boolean {
        val snapshot = state.value
        val target = snapshot.selectedTarget ?: return false
        if (!snapshot.canSend) return false
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            var remainingText = snapshot.text.trim()
            var remainingAttachments = snapshot.attachments
            val failure = if (remainingAttachments.isEmpty()) {
                sendText(target, user, remainingText).exceptionOrNull()
            } else {
                var error: Throwable? = null
                while (remainingAttachments.isNotEmpty() && error == null) {
                    val attachment = remainingAttachments.first()
                    val result = sendAttachment(target, user, attachment, remainingText.takeIf(String::isNotBlank))
                    result.fold(
                        onSuccess = {
                            attachment.file.delete()
                            remainingAttachments = remainingAttachments.drop(1)
                            remainingText = ""
                            _state.update { current -> current.copy(text = "", attachments = remainingAttachments) }
                        },
                        onFailure = { error = it },
                    )
                }
                error
            }

            if (failure == null) {
                _state.update { it.copy(isSending = false) }
                onSent(target)
            } else {
                _state.update { it.copy(isSending = false, text = remainingText, attachments = remainingAttachments, error = failure.shareMessage()) }
            }
        }
        return true
    }

    private fun loadTargets(uid: String) {
        _state.update { it.copy(isLoadingTargets = true, error = null) }
        viewModelScope.launch {
            chatRepository.loadForwardTargets(uid).fold(
                onSuccess = { targets -> _state.update { it.copy(targets = targets, isLoadingTargets = false) } },
                onFailure = { error -> _state.update { it.copy(isLoadingTargets = false, error = error.shareMessage()) } },
            )
        }
    }

    private fun prepareIntent(intent: Intent, onIntentConsumed: (Intent) -> Unit) {
        activeIntent = intent
        state.value.attachments.forEach { it.file.delete() }
        val sharedText = sharedIntentText(intent).take(ChatRepository.MessageCharacterLimit)
        val uris = sharedIntentUris(intent).take(MaxShareAttachments)
        _state.update { it.copy(text = sharedText, attachments = emptyList(), isPreparing = uris.isNotEmpty(), error = null) }
        if (uris.isEmpty()) {
            finishPreparingIntent(intent, onIntentConsumed)
            return
        }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) { uris.map(mediaStore::prepare) }
            val successful = prepared.mapNotNull(Result<PreparedChatAttachment>::getOrNull)
            val failure = prepared.firstOrNull(Result<PreparedChatAttachment>::isFailure)?.exceptionOrNull()
            _state.update {
                it.copy(
                    attachments = successful,
                    isPreparing = false,
                    error = failure?.shareMessage(),
                )
            }
            finishPreparingIntent(intent, onIntentConsumed)
        }
    }

    private fun finishPreparingIntent(intent: Intent, onIntentConsumed: (Intent) -> Unit) {
        activeIntent = null
        lastPreparedIntent = intent
        onIntentConsumed(intent)
        val queued = queuedShare ?: return
        queuedShare = null
        prepareIntent(queued.first, queued.second)
    }

    private suspend fun sendText(target: ForwardTarget, user: UserData, text: String) = when (target.type) {
        ForwardTargetType.Direct -> chatRepository.sendText(target.id, user, text)
        ForwardTargetType.Club -> clubRepository.sendText(target.id, user, text)
    }

    private suspend fun sendAttachment(target: ForwardTarget, user: UserData, attachment: PreparedChatAttachment, caption: String?) = when (target.type) {
        ForwardTargetType.Direct -> when {
            attachment.mimeType.startsWith("image/") -> chatRepository.sendImage(target.id, user, attachment.file, attachment.width, attachment.height, caption)
            attachment.mimeType.startsWith("video/") -> chatRepository.sendVideo(target.id, user, attachment.file, attachment.width, attachment.height, attachment.durationMs, caption)
            else -> chatRepository.sendFile(target.id, user, attachment.file, attachment.mimeType, caption = caption, displayName = attachment.displayName)
        }
        ForwardTargetType.Club -> when {
            attachment.mimeType.startsWith("image/") -> clubRepository.sendImage(target.id, user, attachment.file, attachment.width, attachment.height, caption, null)
            attachment.mimeType.startsWith("video/") -> clubRepository.sendVideo(target.id, user, attachment.file, attachment.width, attachment.height, attachment.durationMs, caption, null)
            else -> clubRepository.sendFile(target.id, user, attachment.file, attachment.displayName, attachment.mimeType, caption, null)
        }
    }

    override fun onCleared() {
        state.value.attachments.forEach { it.file.delete() }
        super.onCleared()
    }

    companion object {
        private const val MaxShareAttachments = 10
    }
}

internal fun sharedIntentText(intent: Intent): String = combinedSharedText(
    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
    body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.data?.toString(),
)

internal fun combinedSharedText(subject: String?, body: CharSequence?): String = listOfNotNull(
    subject?.trim()?.takeIf(String::isNotBlank),
    body?.toString()?.trim()?.takeIf(String::isNotBlank),
).distinct().joinToString("\n\n")

internal fun sharedIntentUris(intent: Intent): List<Uri> {
    val clipUris = buildList {
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }
    val singleUri = listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    val multipleUris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    return distinctShareItems(clipUris, singleUri, multipleUris)
}

internal fun <T> distinctShareItems(vararg sources: List<T>): List<T> = sources.flatMap(List<T>::toList).distinct()

private fun Throwable.shareMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.contains("permission", ignoreCase = true) || raw.contains("member", ignoreCase = true) -> "You can no longer send to that conversation."
        raw.isNotBlank() -> raw
        else -> "The share could not be sent. Please try again."
    }
}
