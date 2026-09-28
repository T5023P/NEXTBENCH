package com.nextbench.app.create

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val MaxSourceBytes = 20L * 1024L * 1024L

data class PreparedPostImage(
    val file: File,
    val uri: Uri,
)

class PostMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri): Result<PreparedPostImage> = runCatching {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        require(type.isBlank() || type.startsWith("image/")) { "Choose an image file." }
        val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(sourceSize <= 0L || sourceSize <= MaxSourceBytes) { "Choose an image smaller than 20 MB." }

        val directory = File(context.cacheDir, "post_media").apply { mkdirs() }
        val file = File.createTempFile("post_", ".upload", directory)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected image could not be opened." }
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            require(file.length() > 0L) { "The selected image is empty." }
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            PreparedPostImage(file, fileUri)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
