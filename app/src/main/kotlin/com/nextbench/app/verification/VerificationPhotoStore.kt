package com.nextbench.app.verification

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

private const val MaxSourceBytes = 20L * 1024L * 1024L
private const val MaxOutputBytes = 5L * 1024L * 1024L
private const val MaxImageEdge = 2_048

data class CapturedPhoto(
    val file: File,
    val uri: Uri,
)

class VerificationPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun createCapture(prefix: String): CapturedPhoto {
        val directory = File(context.cacheDir, "verification").apply { mkdirs() }
        val file = File.createTempFile("${prefix}_", ".jpg", directory)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return CapturedPhoto(file, uri)
    }

    fun prepare(uri: Uri, prefix: String): Result<File> = runCatching {
        val resolver = context.contentResolver
        validateSource(resolver, uri)
        val bounds = decodeBounds(resolver, uri)
        require(bounds.first > 0 && bounds.second > 0) { "Choose a valid image file." }

        val sampleSize = calculateSampleSize(bounds.first, bounds.second)
        val bitmap = resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("The selected image could not be decoded.")

        val oriented = applyExifOrientation(bitmap, resolver.openInputStream(uri))
        if (oriented !== bitmap) bitmap.recycle()
        val directory = File(context.cacheDir, "verification").apply { mkdirs() }
        val output = File.createTempFile("${prefix}_prepared_", ".jpg", directory)
        try {
            writeBoundedJpeg(oriented, output)
            output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            oriented.recycle()
        }
    }

    private fun validateSource(resolver: ContentResolver, uri: Uri) {
        val type = resolver.getType(uri).orEmpty()
        require(type.isBlank() || type.startsWith("image/")) { "Choose a valid image file." }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(size <= 0L || size <= MaxSourceBytes) { "Choose an image smaller than 20 MB." }
    }

    private fun decodeBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(stream, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > MaxImageEdge) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, stream: InputStream?): Bitmap {
        if (stream == null) return bitmap
        val orientation = stream.use(::jpegOrientation)
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(-90f)
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    private fun writeBoundedJpeg(source: Bitmap, output: File) {
        val longest = max(source.width, source.height)
        val scaled = if (longest > MaxImageEdge) {
            val ratio = MaxImageEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).roundToInt(),
                (source.height * ratio).roundToInt(),
                true,
            )
        } else {
            source
        }
        try {
            var quality = 90
            do {
                FileOutputStream(output, false).use { stream ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        "The image could not be prepared for upload."
                    }
                }
                quality -= 8
            } while (output.length() > MaxOutputBytes && quality >= 58)
            require(output.length() in 1..MaxOutputBytes) {
                "This image is too detailed to upload. Try a closer, clearer photo."
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}

internal fun jpegOrientation(stream: InputStream): Int {
    fun readUnsignedShort(): Int = (stream.read() shl 8) or stream.read()
    if (readUnsignedShort() != 0xFFD8) return 1

    while (true) {
        var markerStart = stream.read()
        while (markerStart != -1 && markerStart != 0xFF) markerStart = stream.read()
        var marker = stream.read()
        while (marker == 0xFF) marker = stream.read()
        if (marker == -1 || marker == 0xDA || marker == 0xD9) return 1
        val length = readUnsignedShort() - 2
        if (length < 0) return 1
        if (marker != 0xE1) {
            stream.skipFully(length.toLong())
            continue
        }

        val data = ByteArray(length)
        if (stream.readFully(data) != length || length < 14) return 1
        if (!data.copyOfRange(0, 6).contentEquals(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0))) return 1
        val littleEndian = when {
            data[6] == 0x49.toByte() && data[7] == 0x49.toByte() -> true
            data[6] == 0x4D.toByte() && data[7] == 0x4D.toByte() -> false
            else -> return 1
        }
        fun u16(offset: Int): Int {
            if (offset + 1 >= data.size) return -1
            val first = data[offset].toInt() and 0xFF
            val second = data[offset + 1].toInt() and 0xFF
            return if (littleEndian) first or (second shl 8) else (first shl 8) or second
        }
        fun u32(offset: Int): Int {
            if (offset + 3 >= data.size) return -1
            val a = data[offset].toInt() and 0xFF
            val b = data[offset + 1].toInt() and 0xFF
            val c = data[offset + 2].toInt() and 0xFF
            val d = data[offset + 3].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        if (u16(8) != 42) return 1
        val directory = 6 + u32(10)
        val count = u16(directory)
        if (directory < 6 || count < 0) return 1
        repeat(count) { index ->
            val entry = directory + 2 + index * 12
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0 && read() == -1) return
        remaining -= if (skipped > 0) skipped else 1
    }
}

private fun InputStream.readFully(buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) break
        offset += read
    }
    return offset
}
