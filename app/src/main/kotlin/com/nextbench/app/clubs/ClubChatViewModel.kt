package com.nextbench.app.clubs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ClubRepository
import com.nextbench.data.firebase.LinkPreview
import com.nextbench.data.firebase.LinkPreviewRepository
import com.nextbench.data.firebase.firstMessageUrl
import com.nextbench.app.chat.ChatAttachmentKind
import com.nextbench.app.chat.ChatMediaStore
import com.nextbench.app.chat.ChatVoicePlaybackState
import com.nextbench.app.chat.ChatVoicePlayer
import com.nextbench.app.chat.ChatVoiceRecorder
import com.nextbench.app.chat.PreparedChatAttachment
import com.nextbench.app.chat.voiceRecorderMessage
import com.nextbench.data.model.Club
import com.nextbench.data.model.Message
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ClubChatNoticeKind { Info, Success, Error }

@Immutable
data class ClubChatNotice(val id: Long, val message: String, val kind: ClubChatNoticeKind)

@Immutable
data class ClubChatUiState(
    val club: Club? = null,
    val messages: List<Message> = emptyList(),
    val composerText: String = "",
    val replyTo: Message? = null,
    val actionMessage: Message? = null,
    val attachment: PreparedChatAttachment? = null,
    val isPreparingAttachment: Boolean = false,
    val isSendingAttachment: Boolean = false,
    val isRecordingVoice: Boolean = false,
    val voiceRecordingDurationSeconds: Long = 0L,
    val voiceRecordingLevels: List<Float> = emptyList(),
    val isSendingVoice: Boolean = false,
    val voicePlayback: ChatVoicePlaybackState = ChatVoicePlaybackState(),
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isLeaving: Boolean = false,
    val error: String? = null,
    val notice: ClubChatNotice? = null,
) {
    fun isMember(viewerId: String?): Boolean = !viewerId.isNullOrBlank() && viewerId in club?.memberIds.orEmpty()
    fun canPost(viewerId: String?): Boolean {
        val current = club ?: return false
        val member = viewerId in current.memberIds
        val lead = viewerId == current.leadId || viewerId in current.coLeadIds
        return member && (!current.settings.onlyLeadsCanPost || lead)
    }

    fun canSend(viewerId: String?): Boolean = canPost(viewerId) && !isSending && !isSendingAttachment && !isSendingVoice && !isRecordingVoice && composerText.trim().isNotEmpty()
    fun canSendAttachment(viewerId: String?): Boolean = canSendClubAttachment(
        canPost = canPost(viewerId),
        hasAttachment = attachment != null,
        isSending = isSending,
        isSendingAttachment = isSendingAttachment,
        isSendingVoice = isSendingVoice,
        isRecordingVoice = isRecordingVoice,
    )
}

internal fun canSendClubAttachment(
    canPost: Boolean,
    hasAttachment: Boolean,
    isSending: Boolean,
    isSendingAttachment: Boolean,
    isSendingVoice: Boolean = false,
    isRecordingVoice: Boolean = false,
): Boolean = canPost && hasAttachment && !isSending && !isSendingAttachment && !isSendingVoice && !isRecordingVoice

@HiltViewModel
class ClubChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ClubRepository,
    private val mediaStore: ChatMediaStore,
    private val voiceRecorder: ChatVoiceRecorder,
    private val voicePlayer: ChatVoicePlayer,
    private val linkPreviewRepository: LinkPreviewRepository,
) : ViewModel() {
    private val clubId: String = requireNotNull(savedStateHandle["clubId"]) { "Club chat requires a clubId." }
    private val _state = MutableStateFlow(ClubChatUiState())
    val state: StateFlow<ClubChatUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var clubJob: Job? = null
    private var messagesJob: Job? = null
    private var noticeId = 0L
    private var typingActive = false
    private var typingIdleJob: Job? = null
    private var typingRefreshJob: Job? = null
    private var voiceRecordingJob: Job? = null
    private val requestedLinkPreviewUrls = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            voicePlayer.state.collect { playback -> _state.update { it.copy(voicePlayback = playback) } }
        }
    }

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        stopTyping()
        cancelVoiceRecording()
        voicePlayer.stop()
        state.value.attachment?.file?.delete()
        requestedLinkPreviewUrls.clear()
        viewer = user
        clubJob?.cancel()
        messagesJob?.cancel()
        _state.value = ClubChatUiState(isLoading = user != null)
        val uid = user?.uid ?: return

        clubJob = viewModelScope.launch {
            repository.observeClub(clubId, uid)
                .catch { error -> showLoadError(error) }
                .collect { club ->
                    _state.update { it.copy(club = club, isLoading = false, error = if (club == null) "This club is no longer available." else null) }
                    if (club?.let { uid in it.memberIds } == true && uid in club.unreadBy) repository.markRead(clubId, uid)
                }
        }
        messagesJob = viewModelScope.launch {
            repository.observeMessages(clubId, uid)
                .catch { error -> showLoadError(error) }
                .collect { messages ->
                    _state.update { it.copy(messages = messages, isLoading = false, error = null) }
                    loadLinkPreviews(messages)
                }
        }
    }

    fun setComposerText(value: String) {
        val normalized = value.take(2_000)
        _state.update { it.copy(composerText = normalized) }
        if (normalized.isBlank()) stopTyping() else {
            startTyping()
            typingIdleJob?.cancel()
            typingIdleJob = viewModelScope.launch { delay(TypingIdleMillis); stopTyping() }
        }
    }

    fun prepareAttachment(uri: android.net.Uri, kind: ChatAttachmentKind? = null) {
        if (state.value.isPreparingAttachment || state.value.isSendingAttachment || state.value.isSendingVoice || state.value.isRecordingVoice) return
        state.value.attachment?.file?.delete()
        _state.update { it.copy(isPreparingAttachment = true, attachment = null) }
        viewModelScope.launch {
            mediaStore.prepare(uri, kind).fold(
                onSuccess = { attachment -> _state.update { it.copy(attachment = attachment, isPreparingAttachment = false) } },
                onFailure = { error -> _state.update { it.copy(isPreparingAttachment = false) }; showNotice(error.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
    }

    fun clearAttachment() {
        if (state.value.isPreparingAttachment || state.value.isSendingAttachment) return
        state.value.attachment?.file?.delete()
        _state.update { it.copy(attachment = null) }
    }

    fun sendText(): Boolean {
        val sender = viewer ?: return false
        val snapshot = state.value
        if (!snapshot.canSend(sender.uid)) return false
        _state.update { it.copy(isSending = true) }
        stopTyping()
        viewModelScope.launch {
            repository.sendText(clubId, sender, snapshot.composerText, snapshot.replyTo).fold(
                onSuccess = { _state.update { it.copy(composerText = "", replyTo = null, isSending = false) } },
                onFailure = { error -> _state.update { it.copy(isSending = false) }; showNotice(error.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun sendAttachment(): Boolean {
        val sender = viewer ?: return false
        val snapshot = state.value
        val attachment = snapshot.attachment ?: return false
        if (!snapshot.canSendAttachment(sender.uid)) return false
        _state.update { it.copy(isSendingAttachment = true) }
        stopTyping()
        viewModelScope.launch {
            val result = when {
                attachment.mimeType.startsWith("image/") -> repository.sendImage(clubId, sender, attachment.file, attachment.width, attachment.height, snapshot.composerText, snapshot.replyTo)
                attachment.mimeType.startsWith("video/") -> repository.sendVideo(clubId, sender, attachment.file, attachment.width, attachment.height, attachment.durationMs, snapshot.composerText, snapshot.replyTo)
                else -> repository.sendFile(clubId, sender, attachment.file, attachment.displayName, attachment.mimeType, snapshot.composerText, snapshot.replyTo)
            }
            result.fold(
                onSuccess = {
                    attachment.file.delete()
                    _state.update { it.copy(attachment = null, composerText = "", replyTo = null, isSendingAttachment = false) }
                },
                onFailure = { error -> _state.update { it.copy(isSendingAttachment = false) }; showNotice(error.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun startVoiceRecording(): Boolean {
        val uid = viewer?.uid ?: return false
        val snapshot = state.value
        if (!snapshot.canPost(uid) || snapshot.attachment != null || snapshot.isSending || snapshot.isSendingAttachment || snapshot.isSendingVoice || snapshot.isRecordingVoice) return false
        stopTyping()
        voicePlayer.stop()
        voiceRecorder.start().fold(
            onSuccess = {
                _state.update { it.copy(isRecordingVoice = true, voiceRecordingDurationSeconds = 0L, voiceRecordingLevels = emptyList()) }
                voiceRecordingJob?.cancel()
                voiceRecordingJob = viewModelScope.launch {
                    while (state.value.isRecordingVoice) {
                        val elapsedMillis = voiceRecorder.elapsedMillis()
                        val seconds = (elapsedMillis / 1_000L).coerceAtMost(ChatVoiceRecorder.MaxVoiceDurationSeconds)
                        val level = (voiceRecorder.amplitude().toFloat() / 32_767f).coerceIn(0.04f, 1f)
                        _state.update { current -> current.copy(voiceRecordingDurationSeconds = seconds, voiceRecordingLevels = (current.voiceRecordingLevels + level).takeLast(VoiceWaveformSamples)) }
                        if (elapsedMillis >= ChatVoiceRecorder.MaxVoiceDurationSeconds * 1_000L) {
                            stopVoiceRecording()
                            break
                        }
                        delay(VoiceMeterIntervalMillis)
                    }
                }
            },
            onFailure = { showNotice(it.voiceRecorderMessage(), ClubChatNoticeKind.Error) },
        )
        return state.value.isRecordingVoice
    }

    fun stopVoiceRecording(): Boolean {
        if (!state.value.isRecordingVoice) return false
        voiceRecordingJob?.cancel()
        voiceRecordingJob = null
        val recording = voiceRecorder.stop().fold(
            onSuccess = { it },
            onFailure = {
                _state.update { current -> current.copy(isRecordingVoice = false, voiceRecordingDurationSeconds = 0L, voiceRecordingLevels = emptyList()) }
                showNotice(it.voiceRecorderMessage(), ClubChatNoticeKind.Error)
                return false
            },
        )
        if (recording.durationSeconds < 1L) {
            recording.file.delete()
            _state.update { it.copy(isRecordingVoice = false, voiceRecordingDurationSeconds = 0L, voiceRecordingLevels = emptyList()) }
            showNotice("Recording is too short. Record for at least 1 second.", ClubChatNoticeKind.Error)
            return false
        }
        val sender = viewer
        if (sender == null) {
            recording.file.delete()
            return false
        }
        val reply = state.value.replyTo
        _state.update { it.copy(isRecordingVoice = false, voiceRecordingDurationSeconds = recording.durationSeconds, voiceRecordingLevels = emptyList(), isSendingVoice = true) }
        viewModelScope.launch {
            try {
                repository.sendVoice(clubId, sender, recording.file, recording.durationSeconds, recording.mimeType, reply).fold(
                    onSuccess = { _state.update { it.copy(replyTo = null, isSendingVoice = false, voiceRecordingDurationSeconds = 0L) } },
                    onFailure = { error -> _state.update { it.copy(isSendingVoice = false) }; showNotice(error.clubMessage(), ClubChatNoticeKind.Error) },
                )
            } finally {
                recording.file.delete()
            }
        }
        return true
    }

    fun cancelVoiceRecording(): Boolean {
        val wasRecording = state.value.isRecordingVoice
        voiceRecordingJob?.cancel()
        voiceRecordingJob = null
        voiceRecorder.cancel()
        if (wasRecording) _state.update { it.copy(isRecordingVoice = false, voiceRecordingDurationSeconds = 0L, voiceRecordingLevels = emptyList()) }
        return wasRecording
    }

    fun onMicrophonePermissionDenied() = showNotice("Microphone permission is required to send voice messages.", ClubChatNoticeKind.Error)

    fun toggleVoicePlayback(message: Message) = voicePlayer.toggle(message)

    fun seekVoicePlayback(messageId: String, fraction: Float) = voicePlayer.seek(messageId, fraction)

    fun cycleVoicePlaybackSpeed(messageId: String) = voicePlayer.cycleSpeed(messageId)

    fun onScreenDisposed() {
        stopTyping()
        cancelVoiceRecording()
        voicePlayer.stop()
    }

    fun setReplyTo(message: Message?) = _state.update { it.copy(replyTo = message, actionMessage = null) }

    fun openMessageActions(message: Message) = _state.update { it.copy(actionMessage = message) }

    fun closeMessageActions() = _state.update { it.copy(actionMessage = null) }

    fun toggleReaction(emoji: String): Boolean {
        val uid = viewer?.uid ?: return false
        val message = state.value.actionMessage ?: return false
        viewModelScope.launch {
            repository.toggleReaction(clubId, message.id, uid, emoji).fold(
                onSuccess = { _state.update { it.copy(actionMessage = null) } },
                onFailure = { showNotice(it.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun deleteForMe(): Boolean = messageAction(ownerOnly = false) { message, uid -> repository.deleteForMe(clubId, message.id, uid) }

    fun deleteForEveryone(): Boolean = messageAction(ownerOnly = true) { message, uid -> repository.deleteForEveryone(clubId, message.id, uid) }

    private fun messageAction(ownerOnly: Boolean, operation: suspend (Message, String) -> Result<Unit>): Boolean {
        val uid = viewer?.uid ?: return false
        val message = state.value.actionMessage ?: return false
        if (ownerOnly && message.senderId != uid) return false
        viewModelScope.launch {
            operation(message, uid).fold(
                onSuccess = { _state.update { it.copy(actionMessage = null) } },
                onFailure = { showNotice(it.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun markMessageRead(messageId: String) {
        val uid = viewer?.uid ?: return
        viewModelScope.launch { repository.markMessageRead(clubId, messageId, uid) }
    }

    private fun startTyping() {
        val uid = viewer?.uid ?: return
        if (!state.value.canPost(uid)) return
        if (!typingActive) {
            typingActive = true
            viewModelScope.launch { repository.setTyping(clubId, uid, true) }
        }
        if (typingRefreshJob?.isActive != true) {
            typingRefreshJob = viewModelScope.launch {
                while (typingActive) {
                    delay(TypingRefreshMillis)
                    if (typingActive) repository.setTyping(clubId, uid, true)
                }
            }
        }
    }

    fun stopTyping() {
        typingIdleJob?.cancel()
        typingIdleJob = null
        typingRefreshJob?.cancel()
        typingRefreshJob = null
        if (!typingActive) return
        typingActive = false
        val uid = viewer?.uid ?: return
        viewModelScope.launch { repository.setTyping(clubId, uid, false) }
    }

    fun leaveClub(): Boolean {
        val uid = viewer?.uid ?: return false
        if (state.value.isLeaving || !state.value.isMember(uid)) return false
        _state.update { it.copy(isLeaving = true) }
        viewModelScope.launch {
            repository.leaveClub(uid, clubId).fold(
                onSuccess = { _state.update { it.copy(isLeaving = false) }; showNotice("You left the club.", ClubChatNoticeKind.Success) },
                onFailure = { error -> _state.update { it.copy(isLeaving = false) }; showNotice(error.clubMessage(), ClubChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun dismissNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    private fun loadLinkPreviews(messages: List<Message>) {
        messages.asSequence()
            .filter { !it.isDeletedForEveryone }
            .mapNotNull { firstMessageUrl(it.text) }
            .distinct()
            .filter(requestedLinkPreviewUrls::add)
            .forEach { url ->
                viewModelScope.launch {
                    linkPreviewRepository.resolve(url)?.let { preview ->
                        _state.update { current -> current.copy(linkPreviews = current.linkPreviews + (url to preview)) }
                    }
                }
            }
    }

    private fun showNotice(message: String, kind: ClubChatNoticeKind) = _state.update { it.copy(notice = ClubChatNotice(++noticeId, message, kind)) }
    private fun showLoadError(error: Throwable) = _state.update { it.copy(isLoading = false, error = error.clubMessage()) }

    override fun onCleared() {
        typingIdleJob?.cancel()
        typingRefreshJob?.cancel()
        voiceRecordingJob?.cancel()
        voiceRecorder.cancel()
        voicePlayer.release()
        state.value.attachment?.file?.delete()
        super.onCleared()
    }

    companion object {
        private const val TypingIdleMillis = 3_000L
        private const val TypingRefreshMillis = 2_000L
        private const val VoiceMeterIntervalMillis = 100L
        private const val VoiceWaveformSamples = 34
    }
}
