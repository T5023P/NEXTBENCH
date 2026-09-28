package com.nextbench.app.auth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class PreparedOrganizationDocument(
    val file: File,
    val previewUri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

class OrganizationDocumentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri): Result<PreparedOrganizationDocument> = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        require(mimeType.startsWith("image/") || mimeType == PdfMimeType) {
            "Choose a JPG, PNG, WebP, or PDF document."
        }
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            }
        }
        val displayName = metadata?.first?.takeIf(String::isNotBlank) ?: "organization-document"
        val sourceSize = metadata?.second ?: -1L
        require(sourceSize <= 0L || sourceSize <= MaxDocumentBytes) {
            "Choose a document smaller than 10 MB."
        }

        val extension = displayName.substringAfterLast('.', defaultExtension(mimeType))
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { defaultExtension(mimeType) }
        val directory = File(context.cacheDir, "organization_documents").apply { mkdirs() }
        val target = File.createTempFile("organization_", ".$extension", directory)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected document could not be opened." }
                FileOutputStream(target).use(input::copyTo)
            }
            require(target.length() > 0L) { "The selected document is empty." }
            require(target.length() <= MaxDocumentBytes) { "Choose a document smaller than 10 MB." }
            PreparedOrganizationDocument(
                file = target,
                previewUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                ),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun defaultExtension(mimeType: String): String = when (mimeType) {
        PdfMimeType -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val PdfMimeType = "application/pdf"
        const val MaxDocumentBytes = 10L * 1024L * 1024L
    }
}
