package com.nextbench.app.feed

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class PreparedStoryMedia(
    val file: File,
    val previewUri: Uri,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
)

class StoryMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri): Result<PreparedStoryMedia> = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
            "Choose a photo or video."
        }
        val isVideo = mimeType.startsWith("video/")
        val maxBytes = if (isVideo) MaxVideoBytes else MaxImageBytes
        val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(sourceSize <= 0L || sourceSize <= maxBytes) {
            if (isVideo) "Choose a video smaller than 100 MB." else "Choose a photo smaller than 15 MB."
        }

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf(String::isNotBlank)
            ?: if (isVideo) "mp4" else "jpg"
        val directory = File(context.cacheDir, "story_media").apply { mkdirs() }
        val file = File.createTempFile("story_", ".$extension", directory)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected media could not be opened." }
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            require(file.length() > 0L) { "The selected media is empty." }
            require(file.length() <= maxBytes) {
                if (isVideo) "Choose a video smaller than 100 MB." else "Choose a photo smaller than 15 MB."
            }
            val metadata = if (isVideo) videoMetadata(file) else imageMetadata(file)
            require(metadata.first > 0 && metadata.second > 0) { "The selected media could not be read." }
            PreparedStoryMedia(
                file = file,
                previewUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                mimeType = mimeType,
                width = metadata.first,
                height = metadata.second,
                durationMs = metadata.third,
            )
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun imageMetadata(file: File): Triple<Int, Int, Long?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return Triple(options.outWidth, options.outHeight, null)
    }

    private fun videoMetadata(file: File): Triple<Int, Int, Long?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (rotation == 90 || rotation == 270) Triple(rawHeight, rawWidth, duration) else Triple(rawWidth, rawHeight, duration)
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MaxImageBytes = 15L * 1024L * 1024L
        private const val MaxVideoBytes = 100L * 1024L * 1024L
    }
}
