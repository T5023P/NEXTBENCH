package com.nextbench.app.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nextbench.data.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PreparedVoiceRecording(
    val file: File,
    val durationSeconds: Long,
    val mimeType: String = "audio/mp4",
)

class ChatVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMillis: Long = 0L

    fun start(): Result<Unit> = runCatching {
        cancel()
        val directory = File(context.cacheDir, "chat_voice").apply { mkdirs() }
        val file = File.createTempFile("voice_", ".m4a", directory)
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (error: Exception) {
            mediaRecorder.release()
            file.delete()
            throw error
        }
        recorder = mediaRecorder
        output = file
        startedAtMillis = SystemClock.elapsedRealtime()
    }

    fun elapsedMillis(): Long = if (recorder == null) 0L else (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)

    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun stop(): Result<PreparedVoiceRecording> = runCatching {
        val activeRecorder = requireNotNull(recorder) { "No voice recording is active." }
        val file = requireNotNull(output) { "Recording file is missing." }
        val durationSeconds = (elapsedMillis() / 1_000L).coerceAtMost(MaxVoiceDurationSeconds)
        recorder = null
        output = null
        startedAtMillis = 0L
        try {
            activeRecorder.stop()
        } catch (error: RuntimeException) {
            file.delete()
            throw IllegalStateException("Recording failed. Please try again.", error)
        } finally {
            activeRecorder.release()
        }
        require(file.isFile && file.length() > 0L) { "Recording failed. Please try again." }
        PreparedVoiceRecording(file = file, durationSeconds = durationSeconds)
    }

    fun cancel() {
        val activeRecorder = recorder
        val file = output
        recorder = null
        output = null
        startedAtMillis = 0L
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        file?.delete()
    }

    companion object {
        const val MaxVoiceDurationSeconds = 300L
    }
}

@Immutable
data class ChatVoicePlaybackState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

class ChatVoicePlayer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val player = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ChatVoicePlaybackState())
    val state: StateFlow<ChatVoicePlaybackState> = _state.asStateFlow()

    private val progressUpdate = object : Runnable {
        override fun run() {
            updatePosition()
            if (player.isPlaying) handler.postDelayed(this, ProgressIntervalMillis)
        }
    }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true, error = null) }
                        Player.STATE_READY -> _state.update {
                            it.copy(
                                isLoading = false,
                                durationMillis = player.duration.takeIf { duration -> duration > 0L } ?: it.durationMillis,
                            )
                        }
                        Player.STATE_ENDED -> {
                            player.seekTo(0L)
                            _state.update { it.copy(isPlaying = false, isLoading = false, positionMillis = 0L) }
                        }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying, isLoading = false) }
                    handler.removeCallbacks(progressUpdate)
                    if (isPlaying) handler.post(progressUpdate) else updatePosition()
                }

                override fun onPlayerError(error: PlaybackException) {
                    handler.removeCallbacks(progressUpdate)
                    _state.update {
                        it.copy(isPlaying = false, isLoading = false, error = "Unable to play voice message.")
                    }
                }
            },
        )
    }

    fun toggle(message: Message) {
        val url = message.audioUrl?.takeIf(String::isNotBlank) ?: return
        if (_state.value.messageId == message.id && player.isPlaying) {
            player.pause()
            return
        }
        if (_state.value.messageId != message.id || _state.value.error != null) {
            val fallbackDuration = message.duration?.times(1_000L) ?: 0L
            _state.value = ChatVoicePlaybackState(
                messageId = message.id,
                isLoading = true,
                durationMillis = fallbackDuration,
            )
            player.setMediaItem(MediaItem.fromUri(url))
            player.playbackParameters = PlaybackParameters(1f)
            player.prepare()
        }
        player.play()
    }

    fun seek(messageId: String, fraction: Float) {
        val snapshot = _state.value
        if (snapshot.messageId != messageId || snapshot.durationMillis <= 0L) return
        player.seekTo((snapshot.durationMillis * fraction.coerceIn(0f, 1f)).toLong())
        updatePosition()
    }

    fun cycleSpeed(messageId: String) {
        if (_state.value.messageId != messageId) return
        val current = _state.value.speed
        val next = when (current) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.playbackParameters = PlaybackParameters(next)
        _state.update { it.copy(speed = next) }
    }

    fun stop() {
        handler.removeCallbacks(progressUpdate)
        player.stop()
        player.clearMediaItems()
        _state.value = ChatVoicePlaybackState()
    }

    fun release() {
        handler.removeCallbacks(progressUpdate)
        player.release()
        _state.value = ChatVoicePlaybackState()
    }

    private fun updatePosition() {
        _state.update {
            it.copy(
                positionMillis = player.currentPosition.coerceAtLeast(0L),
                durationMillis = player.duration.takeIf { duration -> duration > 0L } ?: it.durationMillis,
            )
        }
    }

    private companion object {
        const val ProgressIntervalMillis = 50L
    }
}
