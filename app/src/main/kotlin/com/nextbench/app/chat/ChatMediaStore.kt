package com.nextbench.app.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class PreparedChatAttachment(
    val file: File,
    val previewUri: Uri,
    val mimeType: String,
    val displayName: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
)

class ChatMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri, expectedKind: ChatAttachmentKind? = null): Result<PreparedChatAttachment> = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val kind = when {
            mime.startsWith("image/") -> ChatAttachmentKind.Image
            mime.startsWith("video/") -> ChatAttachmentKind.Video
            else -> ChatAttachmentKind.File
        }
        if (expectedKind != null) require(expectedKind == kind) { "Choose a ${expectedKind.label.lowercase()}." }
        val maxBytes = if (kind == ChatAttachmentKind.File) MaxFileBytes else MaxMediaBytes
        val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(sourceSize <= 0L || sourceSize <= maxBytes) {
            if (kind == ChatAttachmentKind.File) "Choose a file smaller than 25 MB." else "Choose media smaller than 100 MB."
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: "attachment"
        val extension = name.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "bin" }
        val directory = File(context.cacheDir, "chat_media").apply { mkdirs() }
        val target = File.createTempFile("chat_", ".$extension", directory)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file could not be opened." }
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            require(target.length() > 0L) { "The selected file is empty." }
            require(target.length() <= maxBytes) {
                if (kind == ChatAttachmentKind.File) "Choose a file smaller than 25 MB." else "Choose media smaller than 100 MB."
            }
            val dimensions = when (kind) {
                ChatAttachmentKind.Image -> imageDimensions(target)
                ChatAttachmentKind.Video -> videoDimensions(target)
                ChatAttachmentKind.File -> Triple(0, 0, null)
            }
            PreparedChatAttachment(
                file = target,
                previewUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target),
                mimeType = mime,
                displayName = name,
                width = dimensions.first,
                height = dimensions.second,
                durationMs = dimensions.third,
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun imageDimensions(file: File): Triple<Int, Int, Long?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return Triple(options.outWidth, options.outHeight, null)
    }

    private fun videoDimensions(file: File): Triple<Int, Int, Long?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            Triple(width, height, duration)
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MaxMediaBytes = 100L * 1024L * 1024L
        private const val MaxFileBytes = 25L * 1024L * 1024L
    }
}

enum class ChatAttachmentKind(val label: String) { Image("Photo"), Video("Video"), File("Document") }
