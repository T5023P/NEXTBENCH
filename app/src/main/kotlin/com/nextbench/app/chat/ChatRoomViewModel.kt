package com.nextbench.app.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.ChatBlockState
import com.nextbench.data.firebase.ChatRepository
import com.nextbench.data.firebase.ChatRoomDetail
import com.nextbench.data.firebase.ForwardTarget
import com.nextbench.data.firebase.LinkPreview
import com.nextbench.data.firebase.LinkPreviewRepository
import com.nextbench.data.firebase.firstMessageUrl
import com.nextbench.data.model.Message
import com.nextbench.data.model.MessageStatus
import com.nextbench.data.model.MessageType
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.google.firebase.Timestamp
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

enum class ChatNoticeKind { Info, Success, Error }

@Immutable
data class ChatNotice(
    val id: Long,
    val message: String,
    val kind: ChatNoticeKind,
)

@Immutable
data class ChatRoomUiState(
    val room: ChatRoomDetail? = null,
    val messages: List<Message> = emptyList(),
    val blockState: ChatBlockState = ChatBlockState(),
    val composerText: String = "",
    val replyTo: Message? = null,
    val attachment: PreparedChatAttachment? = null,
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isPreparingAttachment: Boolean = false,
    val isSendingAttachment: Boolean = false,
    val isRecordingVoice: Boolean = false,
    val voiceRecordingDurationSeconds: Long = 0L,
    val voiceRecordingLevels: List<Float> = emptyList(),
    val isSendingVoice: Boolean = false,
    val voiceUploadProgress: Int = 0,
    val voicePlayback: ChatVoicePlaybackState = ChatVoicePlaybackState(),
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val actionMessage: Message? = null,
    val selectedMessageIds: Set<String> = emptySet(),
    val forwardSourceIds: Set<String> = emptySet(),
    val forwardTargets: List<ForwardTarget> = emptyList(),
    val selectedForwardTargetKeys: Set<String> = emptySet(),
    val forwardQuery: String = "",
    val isLoadingForwardTargets: Boolean = false,
    val isForwarding: Boolean = false,
    val isBulkActionRunning: Boolean = false,
    val isActingOnRequest: Boolean = false,
    val error: String? = null,
    val notice: ChatNotice? = null,
) {
    val pendingRequest: Boolean get() = room?.room?.status == "pending"
    val pendingRequester: String? get() = room?.room?.requestedBy
    val blocked: Boolean get() = blockState.isBlocked
    val selectionMode: Boolean get() = selectedMessageIds.isNotEmpty()
    val selectedMessages: List<Message> get() = messages.filter { it.id in selectedMessageIds }
    val visibleForwardTargets: List<ForwardTarget>
        get() {
            val normalized = forwardQuery.trim()
            return if (normalized.isBlank()) forwardTargets else forwardTargets.filter { it.name.contains(normalized, ignoreCase = true) }
        }

    fun canRespondToRequest(viewerId: String?): Boolean =
        pendingRequest && !viewerId.isNullOrBlank() && pendingRequester != viewerId

    fun canSend(viewerId: String?): Boolean =
        !viewerId.isNullOrBlank() && room != null && !pendingRequest && !blocked &&
            !isSending && !isSendingAttachment && !isSendingVoice && !isRecordingVoice
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository,
    private val mediaStore: ChatMediaStore,
    private val voiceRecorder: ChatVoiceRecorder,
    private val voicePlayer: ChatVoicePlayer,
    private val linkPreviewRepository: LinkPreviewRepository,
) : ViewModel() {
    private val roomId: String = requireNotNull(savedStateHandle["roomId"]) {
        "Conversation requires a roomId navigation argument."
    }
    private val _state = MutableStateFlow(ChatRoomUiState())
    val state: StateFlow<ChatRoomUiState> = _state.asStateFlow()

    private var viewer: UserData? = null
    private var roomJob: Job? = null
    private var messagesJob: Job? = null
    private var blockJob: Job? = null
    private var typingIdleJob: Job? = null
    private var typingRefreshJob: Job? = null
    private var voiceRecordingJob: Job? = null
    private var typingActive = false
    private var noticeId = 0L
    private var remoteMessages: List<Message> = emptyList()
    private val optimisticMessages = linkedMapOf<String, Message>()
    private val requestedLinkPreviewUrls = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            voicePlayer.state.collect { playback ->
                _state.update { it.copy(voicePlayback = playback) }
            }
        }
    }

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && (viewer == null) == (user == null)) return
        roomJob?.cancel()
        messagesJob?.cancel()
        blockJob?.cancel()
        cancelVoiceRecording()
        voicePlayer.stop()
        stopTyping()
        remoteMessages = emptyList()
        optimisticMessages.clear()
        requestedLinkPreviewUrls.clear()
        viewer = user
        _state.value = ChatRoomUiState(isLoading = user != null)
        val uid = user?.uid ?: return

        roomJob = viewModelScope.launch {
            repository.observeRoom(roomId, uid)
                .catch { error -> showLoadError(error) }
                .collect { room ->
                    _state.update { it.copy(room = room, isLoading = false, error = null) }
                    if (uid in room?.room?.unreadBy.orEmpty()) repository.markRead(roomId, uid)
                }
        }
        messagesJob = viewModelScope.launch {
            repository.observeMessages(roomId, uid)
                .catch { error -> showLoadError(error) }
                .collect { messages ->
                    remoteMessages = messages
                    val deliveredIds = messages.mapNotNull(Message::clientMessageId).toSet()
                    optimisticMessages.keys.removeAll(deliveredIds)
                    publishMessages(isLoading = false, error = null)
                    loadLinkPreviews(messages)
                }
        }
        blockJob = viewModelScope.launch {
            _state
                .map { detail -> detail.room?.room?.participants?.firstOrNull { it != uid } }
                .distinctUntilChanged()
                .flatMapLatest { otherId ->
                    if (otherId.isNullOrBlank()) flowOf(ChatBlockState())
                    else repository.observeBlockState(uid, otherId)
                }
                .catch { error -> showLoadError(error) }
                .collect { blockState ->
                    _state.update { it.copy(blockState = blockState) }
                }
        }
    }

    fun setComposerText(value: String) {
        val normalized = value.take(ChatRepository.MessageCharacterLimit)
        _state.update { it.copy(composerText = normalized) }
        if (normalized.isBlank()) {
            stopTyping()
        } else {
            startTyping()
            typingIdleJob?.cancel()
            typingIdleJob = viewModelScope.launch {
                delay(TypingIdleMillis)
                stopTyping()
            }
        }
    }

    fun prepareAttachment(uri: android.net.Uri, kind: ChatAttachmentKind? = null) {
        if (state.value.isPreparingAttachment || state.value.isSendingAttachment) return
        state.value.attachment?.file?.delete()
        _state.update { it.copy(isPreparingAttachment = true, attachment = null) }
        viewModelScope.launch {
            mediaStore.prepare(uri, kind).fold(
                onSuccess = { attachment -> _state.update { it.copy(attachment = attachment, isPreparingAttachment = false) } },
                onFailure = { error ->
                    _state.update { it.copy(isPreparingAttachment = false) }
                    showNotice(error.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
    }

    fun clearAttachment() {
        state.value.attachment?.file?.delete()
        _state.update { it.copy(attachment = null) }
    }

    fun setReplyTo(message: Message?) = _state.update { it.copy(replyTo = message, actionMessage = null) }

    fun openMessageActions(message: Message) = _state.update { it.copy(actionMessage = message) }

    fun closeMessageActions() = _state.update { it.copy(actionMessage = null) }

    fun selectActionMessage(): Boolean {
        val message = state.value.actionMessage ?: return false
        return selectMessage(message)
    }

    fun selectMessage(message: Message): Boolean {
        if (message.isDeletedForEveryone || message.id.isBlank()) return false
        val selected = state.value.selectedMessageIds
        if (message.id !in selected && selected.size >= ChatRepository.MaxForwardMessages) {
            showNotice("Select up to ${ChatRepository.MaxForwardMessages} messages at once.", ChatNoticeKind.Info)
            return false
        }
        _state.update { current ->
            current.copy(
                selectedMessageIds = if (message.id in current.selectedMessageIds) {
                    current.selectedMessageIds - message.id
                } else {
                    current.selectedMessageIds + message.id
                },
                actionMessage = null,
            )
        }
        return true
    }

    fun clearMessageSelection() {
        _state.update { it.copy(selectedMessageIds = emptySet()) }
    }

    fun openForwardingFromAction(): Boolean {
        val message = state.value.actionMessage ?: return false
        return openForwarding(setOf(message.id))
    }

    fun openForwardingFromSelection(): Boolean = openForwarding(state.value.selectedMessageIds)

    private fun openForwarding(sourceIds: Set<String>): Boolean {
        val validIds = state.value.messages
            .filter { it.id in sourceIds && !it.isDeletedForEveryone }
            .map(Message::id)
            .toSet()
        if (validIds.isEmpty()) return false
        val uid = viewer?.uid ?: return false
        _state.update {
            it.copy(
                actionMessage = null,
                forwardSourceIds = validIds,
                forwardTargets = emptyList(),
                selectedForwardTargetKeys = emptySet(),
                forwardQuery = "",
                isLoadingForwardTargets = true,
            )
        }
        viewModelScope.launch {
            repository.loadForwardTargets(uid).fold(
                onSuccess = { targets -> _state.update { it.copy(forwardTargets = targets, isLoadingForwardTargets = false) } },
                onFailure = { error ->
                    _state.update { it.copy(isLoadingForwardTargets = false, forwardSourceIds = emptySet()) }
                    showNotice(error.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun setForwardQuery(value: String) = _state.update { it.copy(forwardQuery = value.take(80)) }

    fun toggleForwardTarget(target: ForwardTarget) {
        val key = target.forwardKey()
        val selected = state.value.selectedForwardTargetKeys
        if (key !in selected && selected.size >= ChatRepository.MaxForwardTargets) {
            showNotice("Choose up to ${ChatRepository.MaxForwardTargets} conversations.", ChatNoticeKind.Info)
            return
        }
        _state.update { current ->
            current.copy(
                selectedForwardTargetKeys = if (key in current.selectedForwardTargetKeys) {
                    current.selectedForwardTargetKeys - key
                } else {
                    current.selectedForwardTargetKeys + key
                },
            )
        }
    }

    fun closeForwarding() {
        if (state.value.isForwarding) return
        _state.update {
            it.copy(
                forwardSourceIds = emptySet(),
                forwardTargets = emptyList(),
                selectedForwardTargetKeys = emptySet(),
                forwardQuery = "",
                isLoadingForwardTargets = false,
            )
        }
    }

    fun forwardSelectedMessages(): Boolean {
        val sender = viewer ?: return false
        val snapshot = state.value
        if (snapshot.isForwarding || snapshot.forwardSourceIds.isEmpty() || snapshot.selectedForwardTargetKeys.isEmpty()) return false
        val sources = snapshot.messages.filter { it.id in snapshot.forwardSourceIds }
        val targets = snapshot.forwardTargets.filter { it.forwardKey() in snapshot.selectedForwardTargetKeys }
        if (sources.isEmpty() || targets.isEmpty()) return false
        _state.update { it.copy(isForwarding = true) }
        viewModelScope.launch {
            repository.forwardMessages(sender, sources, targets).fold(
                onSuccess = { result ->
                    _state.update {
                        it.copy(
                            isForwarding = false,
                            forwardSourceIds = emptySet(),
                            forwardTargets = emptyList(),
                            selectedForwardTargetKeys = emptySet(),
                            forwardQuery = "",
                            selectedMessageIds = emptySet(),
                        )
                    }
                    val message = if (result.failedTargets > 0) {
                        "Forwarded to ${result.deliveredTargets}; ${result.failedTargets} failed"
                    } else {
                        "Forwarded to ${result.deliveredTargets} conversation${if (result.deliveredTargets == 1) "" else "s"}"
                    }
                    showNotice(message, if (result.deliveredTargets > 0) ChatNoticeKind.Success else ChatNoticeKind.Error)
                },
                onFailure = { error ->
                    _state.update { it.copy(isForwarding = false) }
                    showNotice(error.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun deleteSelectionForMe(): Boolean = bulkDelete(everyone = false)

    fun deleteSelectionForEveryone(): Boolean = bulkDelete(everyone = true)

    private fun bulkDelete(everyone: Boolean): Boolean {
        val uid = viewer?.uid ?: return false
        val snapshot = state.value
        val messages = snapshot.selectedMessages
        if (messages.isEmpty() || snapshot.isBulkActionRunning) return false
        if (everyone && messages.any { it.senderId != uid }) return false
        _state.update { it.copy(isBulkActionRunning = true) }
        viewModelScope.launch {
            val result = if (everyone) {
                repository.deleteForEveryoneBulk(roomId, messages.map(Message::id), uid)
            } else {
                repository.deleteForMeBulk(roomId, messages.map(Message::id), uid)
            }
            result.fold(
                onSuccess = { count ->
                    _state.update { it.copy(isBulkActionRunning = false, selectedMessageIds = emptySet()) }
                    showNotice("Deleted $count message${if (count == 1) "" else "s"}", ChatNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isBulkActionRunning = false) }
                    showNotice(error.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun markMessageRead(messageId: String) {
        val uid = viewer?.uid ?: return
        viewModelScope.launch {
            repository.markMessageRead(roomId, messageId, uid)
        }
    }

    fun toggleReaction(emoji: String): Boolean {
        val message = state.value.actionMessage ?: return false
        val uid = viewer?.uid ?: return false
        viewModelScope.launch {
            repository.toggleReaction(roomId, message.id, uid, emoji).fold(
                onSuccess = { },
                onFailure = { showNotice(it.chatMessage(), ChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun deleteForMe(): Boolean = messageAction(ownerOnly = false) { repository.deleteForMe(roomId, it.id, requireViewerId()) }

    fun deleteForEveryone(): Boolean = messageAction(ownerOnly = true) { repository.deleteForEveryone(roomId, it.id, requireViewerId()) }

    private fun messageAction(ownerOnly: Boolean, operation: suspend (Message) -> Result<Unit>): Boolean {
        val message = state.value.actionMessage ?: return false
        val uid = viewer?.uid ?: return false
        if (ownerOnly && message.senderId != uid) return false
        viewModelScope.launch {
            operation(message).fold(
                onSuccess = { _state.update { it.copy(actionMessage = null) } },
                onFailure = { showNotice(it.chatMessage(), ChatNoticeKind.Error) },
            )
        }
        return true
    }

    fun sendText(): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        val room = snapshot.room?.room ?: return false
        if (snapshot.isSending || snapshot.isSendingAttachment || snapshot.isSendingVoice || snapshot.isRecordingVoice || snapshot.blocked || room.status == "pending") return false
        val text = snapshot.composerText.trim()
        if (text.isEmpty()) return false
        val clientMessageId = "android_${UUID.randomUUID()}"
        val optimistic = optimisticTextMessage(clientMessageId, user, text, snapshot.replyTo, MessageStatus.Pending)
        optimisticMessages[clientMessageId] = optimistic
        _state.update { it.copy(composerText = "", replyTo = null) }
        publishMessages()
        stopTyping()
        deliverOptimisticText(clientMessageId, user, text, snapshot.replyTo)
        return true
    }

    fun retryText(message: Message): Boolean {
        val clientMessageId = message.clientMessageId ?: return false
        val outgoing = optimisticMessages[clientMessageId] ?: return false
        if (MessageStatus.from(outgoing.status) != MessageStatus.Failed) return false
        val user = viewer ?: return false
        optimisticMessages[clientMessageId] = outgoing.copy(status = MessageStatus.Pending.raw)
        publishMessages()
        deliverOptimisticText(clientMessageId, user, outgoing.text.orEmpty(), optimisticReplyMessage(outgoing))
        return true
    }

    fun removeFailedText(message: Message): Boolean {
        if (MessageStatus.from(message.status) != MessageStatus.Failed) return false
        val clientMessageId = message.clientMessageId ?: return false
        if (optimisticMessages.remove(clientMessageId) == null) return false
        publishMessages()
        return true
    }

    private fun deliverOptimisticText(clientMessageId: String, user: UserData, text: String, replyTo: Message?) {
        viewModelScope.launch {
            repository.sendText(roomId, user, text, replyTo, clientMessageId).fold(
                onSuccess = { delivered ->
                    if (remoteMessages.none { it.clientMessageId == clientMessageId }) {
                        optimisticMessages[clientMessageId] = delivered.copy(status = MessageStatus.Sent.raw)
                        publishMessages()
                    }
                },
                onFailure = { error ->
                    if (remoteMessages.none { it.clientMessageId == clientMessageId }) {
                        optimisticMessages[clientMessageId] = optimisticMessages.getValue(clientMessageId).copy(status = MessageStatus.Failed.raw)
                        publishMessages()
                        showNotice(error.chatMessage(), ChatNoticeKind.Error)
                    }
                },
            )
        }
    }

    private fun publishMessages(isLoading: Boolean = state.value.isLoading, error: String? = state.value.error) {
        _state.update { it.copy(messages = mergeOptimisticMessages(remoteMessages, optimisticMessages.values), isLoading = isLoading, error = error) }
    }

    fun sendAttachment(): Boolean {
        val user = viewer ?: return false
        val attachment = state.value.attachment ?: return false
        val snapshot = state.value
        if (snapshot.isSendingAttachment || snapshot.isSendingVoice || snapshot.isRecordingVoice || snapshot.blocked || snapshot.pendingRequest) return false
        _state.update { it.copy(isSendingAttachment = true) }
        viewModelScope.launch {
            val result = when {
                attachment.mimeType.startsWith("image/") -> repository.sendImage(roomId, user, attachment.file, attachment.width, attachment.height, snapshot.composerText, snapshot.replyTo)
                attachment.mimeType.startsWith("video/") -> repository.sendVideo(roomId, user, attachment.file, attachment.width, attachment.height, attachment.durationMs, snapshot.composerText, snapshot.replyTo)
                else -> repository.sendFile(roomId, user, attachment.file, attachment.mimeType, snapshot.composerText, replyTo = snapshot.replyTo, displayName = attachment.displayName)
            }
            result.fold(
                onSuccess = {
                    attachment.file.delete()
                    _state.update { it.copy(attachment = null, composerText = "", replyTo = null, isSendingAttachment = false) }
                },
                onFailure = {
                    _state.update { it.copy(isSendingAttachment = false) }
                    showNotice(it.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
        return true
    }

    fun startVoiceRecording(): Boolean {
        val snapshot = state.value
        if (viewer == null || !snapshot.canSend(viewer?.uid) || snapshot.attachment != null) return false
        stopTyping()
        voicePlayer.stop()
        voiceRecorder.start().fold(
            onSuccess = {
                _state.update {
                    it.copy(
                        isRecordingVoice = true,
                        voiceRecordingDurationSeconds = 0L,
                        voiceRecordingLevels = emptyList(),
                        voiceUploadProgress = 0,
                    )
                }
                voiceRecordingJob?.cancel()
                voiceRecordingJob = viewModelScope.launch {
                    while (state.value.isRecordingVoice) {
                        val elapsedMillis = voiceRecorder.elapsedMillis()
                        val seconds = (elapsedMillis / 1_000L).coerceAtMost(ChatVoiceRecorder.MaxVoiceDurationSeconds)
                        val level = (voiceRecorder.amplitude().toFloat() / 32_767f).coerceIn(0.04f, 1f)
                        _state.update { current ->
                            current.copy(
                                voiceRecordingDurationSeconds = seconds,
                                voiceRecordingLevels = (current.voiceRecordingLevels + level).takeLast(VoiceWaveformSamples),
                            )
                        }
                        if (elapsedMillis >= ChatVoiceRecorder.MaxVoiceDurationSeconds * 1_000L) {
                            stopVoiceRecording()
                            break
                        }
                        delay(VoiceMeterIntervalMillis)
                    }
                }
            },
            onFailure = { showNotice(it.voiceRecorderMessage(), ChatNoticeKind.Error) },
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
                showNotice(it.voiceRecorderMessage(), ChatNoticeKind.Error)
                return false
            },
        )
        if (recording.durationSeconds < 1L) {
            recording.file.delete()
            _state.update {
                it.copy(
                    isRecordingVoice = false,
                    voiceRecordingDurationSeconds = 0L,
                    voiceRecordingLevels = emptyList(),
                )
            }
            showNotice("Recording is too short. Record for at least 1 second.", ChatNoticeKind.Error)
            return false
        }
        _state.update {
            it.copy(
                isRecordingVoice = false,
                voiceRecordingDurationSeconds = recording.durationSeconds,
                voiceRecordingLevels = emptyList(),
                isSendingVoice = true,
                voiceUploadProgress = 0,
            )
        }
        val user = viewer
        if (user == null) {
            recording.file.delete()
            _state.update { it.copy(isSendingVoice = false) }
            return false
        }
        val reply = state.value.replyTo
        viewModelScope.launch {
            try {
                repository.sendVoice(
                    roomId = roomId,
                    sender = user,
                    file = recording.file,
                    durationSeconds = recording.durationSeconds,
                    mimeType = recording.mimeType,
                    replyTo = reply,
                    onProgress = { progress -> _state.update { it.copy(voiceUploadProgress = progress) } },
                ).fold(
                    onSuccess = {
                        _state.update { current ->
                            current.copy(
                                replyTo = null,
                                isSendingVoice = false,
                                voiceRecordingDurationSeconds = 0L,
                                voiceUploadProgress = 0,
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update { it.copy(isSendingVoice = false, voiceUploadProgress = 0) }
                        showNotice(error.chatMessage(), ChatNoticeKind.Error)
                    },
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
        if (wasRecording) {
            _state.update {
                it.copy(
                    isRecordingVoice = false,
                    voiceRecordingDurationSeconds = 0L,
                    voiceRecordingLevels = emptyList(),
                )
            }
        }
        return wasRecording
    }

    fun onMicrophonePermissionDenied() {
        showNotice("Microphone permission is required to send voice messages.", ChatNoticeKind.Error)
    }

    fun toggleVoicePlayback(message: Message) = voicePlayer.toggle(message)

    fun seekVoicePlayback(messageId: String, fraction: Float) = voicePlayer.seek(messageId, fraction)

    fun cycleVoicePlaybackSpeed(messageId: String) = voicePlayer.cycleSpeed(messageId)

    fun onScreenDisposed() {
        stopTyping()
        cancelVoiceRecording()
        voicePlayer.stop()
    }

    fun acceptRequest(): Boolean = requestAction { repository.acceptRequest(roomId, requireViewerId()) }

    fun declineRequest(): Boolean = requestAction { repository.declineRequest(roomId, requireViewerId()) }

    fun dismissNotice(id: Long) {
        _state.update { current -> if (current.notice?.id == id) current.copy(notice = null) else current }
    }

    fun notifyReplyTargetUnavailable() {
        showNotice("The original message is no longer available.", ChatNoticeKind.Info)
    }

    private fun loadLinkPreviews(messages: List<Message>) {
        messages.asSequence()
            .filter { !it.isDeletedForEveryone && MessageStatus.from(it.status) == MessageStatus.Sent }
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

    private fun requestAction(operation: suspend () -> Result<Unit>): Boolean {
        val user = viewer ?: return false
        val snapshot = state.value
        if (!snapshot.pendingRequest || snapshot.isActingOnRequest || snapshot.blocked) return false
        if (snapshot.pendingRequester == user.uid) return false
        _state.update { it.copy(isActingOnRequest = true) }
        viewModelScope.launch {
            operation().fold(
                onSuccess = {
                    _state.update { it.copy(isActingOnRequest = false) }
                    showNotice("Conversation updated", ChatNoticeKind.Success)
                },
                onFailure = { error ->
                    _state.update { it.copy(isActingOnRequest = false) }
                    showNotice(error.chatMessage(), ChatNoticeKind.Error)
                },
            )
        }
        return true
    }

    private fun requireViewerId(): String = requireNotNull(viewer?.uid)

    private fun startTyping() {
        val uid = viewer?.uid ?: return
        if (state.value.blocked || state.value.pendingRequest) return
        if (!typingActive) {
            typingActive = true
            viewModelScope.launch { repository.setTyping(roomId, uid, true) }
        }
        if (typingRefreshJob?.isActive != true) {
            typingRefreshJob = viewModelScope.launch {
                while (typingActive) {
                    delay(TypingRefreshMillis)
                    if (typingActive) repository.setTyping(roomId, uid, true)
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
        viewModelScope.launch { repository.setTyping(roomId, uid, false) }
    }

    private fun showNotice(message: String, kind: ChatNoticeKind) {
        _state.update { it.copy(notice = ChatNotice(++noticeId, message, kind)) }
    }

    private fun showLoadError(error: Throwable) {
        _state.update { it.copy(isLoading = false, error = error.chatMessage()) }
    }

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

internal fun ForwardTarget.forwardKey(): String = "${type.name}:$id"

internal fun optimisticTextMessage(
    clientMessageId: String,
    sender: UserData,
    text: String,
    replyTo: Message?,
    status: MessageStatus,
): Message = Message(
    id = clientMessageId,
    senderId = sender.uid,
    senderName = sender.name.ifBlank { "Student" },
    senderAvatar = sender.profilePicture,
    text = text,
    type = MessageType.Text.raw,
    createdAt = Timestamp.now(),
    replyToMessageId = replyTo?.id,
    replyToText = replyTo?.optimisticReplyPreview(),
    replyToSenderName = replyTo?.senderName,
    replyToType = replyTo?.let { MessageType.from(it.type).raw },
    clientMessageId = clientMessageId,
    status = status.raw,
)

internal fun mergeOptimisticMessages(remote: List<Message>, optimistic: Collection<Message>): List<Message> {
    val deliveredClientIds = remote.mapNotNull(Message::clientMessageId).toSet()
    return (remote + optimistic.filterNot { it.clientMessageId in deliveredClientIds })
        .distinctBy { it.id }
        .sortedWith(compareBy<Message> { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }.thenBy(Message::id))
}

private fun Message.optimisticReplyPreview(): String = text?.takeIf(String::isNotBlank)
    ?: when (MessageType.from(type)) {
        MessageType.Image -> "Photo"
        MessageType.Video -> "Video"
        MessageType.File -> file?.name?.takeIf(String::isNotBlank) ?: "File"
        MessageType.Voice -> "Voice message"
        MessageType.Text -> "Message"
    }

private fun optimisticReplyMessage(message: Message): Message? = message.replyToMessageId?.let { replyId ->
    Message(
        id = replyId,
        senderName = message.replyToSenderName,
        text = message.replyToText,
        type = message.replyToType ?: MessageType.Text.raw,
    )
}

internal fun Throwable.voiceRecorderMessage(): String = when (this) {
    is SecurityException -> "Microphone permission is required to send voice messages."
    else -> message?.takeIf(String::isNotBlank) ?: "Could not record audio. Please try again."
}

internal fun Throwable.chatMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) ->
            "Firebase is not configured for this build. Add google-services.json to load messages."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) ->
            "No internet connection. Check your network and try again."
        raw.contains("session expired", ignoreCase = true) || raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
            "Your session expired. Sign in and try again."
        raw.contains("blocked", ignoreCase = true) || raw.contains("cannot message", ignoreCase = true) ->
            "You cannot message this user."
        raw.contains("pending", ignoreCase = true) || raw.contains("Accept", ignoreCase = true) -> raw
        raw.contains("no longer available", ignoreCase = true) || raw.contains("NOT_FOUND", ignoreCase = true) ->
            "This conversation is no longer available."
        raw.isNotBlank() -> raw
        else -> "Something went wrong. Please try again."
    }
}
