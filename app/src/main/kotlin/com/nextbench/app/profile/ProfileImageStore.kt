package com.nextbench.app.profile

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

private const val MaxProfileSourceBytes = 20L * 1024L * 1024L
private const val MaxProfileOutputBytes = 5L * 1024L * 1024L
private const val ProfileImageEdge = 1_080

class ProfileImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri): Result<File> = runCatching {
        val resolver = context.contentResolver
        validateSource(resolver, uri)
        val bounds = decodeBounds(resolver, uri)
        require(bounds.first > 0 && bounds.second > 0) { "Choose a valid image file." }

        val bitmap = resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = profileSampleSize(bounds.first, bounds.second)
                },
            )
        } ?: error("The selected image could not be decoded.")

        val orientation = resolver.openInputStream(uri).use { stream ->
            if (stream == null) ExifInterface.ORIENTATION_NORMAL else runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }
        val oriented = applyOrientation(bitmap, orientation)
        if (oriented !== bitmap) bitmap.recycle()
        val square = cropSquare(oriented)
        if (square !== oriented) oriented.recycle()
        val output = File.createTempFile(
            "profile_",
            ".jpg",
            File(context.cacheDir, "profile_media").apply { mkdirs() },
        )
        try {
            writeBoundedJpeg(square, output)
            output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            square.recycle()
        }
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        return if (matrix.isIdentity) source else Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
    }

    private fun validateSource(resolver: ContentResolver, uri: Uri) {
        val type = resolver.getType(uri).orEmpty()
        require(type.isBlank() || type.startsWith("image/")) { "Choose a valid image file." }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(size <= 0L || size <= MaxProfileSourceBytes) { "Choose an image smaller than 20 MB." }
    }

    private fun decodeBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(stream, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun cropSquare(source: Bitmap): Bitmap {
        val edge = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - edge) / 2,
            (source.height - edge) / 2,
            edge,
            edge,
        )
        return if (edge > ProfileImageEdge) {
            Bitmap.createScaledBitmap(cropped, ProfileImageEdge, ProfileImageEdge, true).also {
                if (it !== cropped) cropped.recycle()
            }
        } else {
            cropped
        }
    }

    private fun writeBoundedJpeg(source: Bitmap, output: File) {
        var quality = 90
        do {
            FileOutputStream(output, false).use { stream ->
                check(source.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    "The image could not be prepared for upload."
                }
            }
            quality -= 8
        } while (output.length() > MaxProfileOutputBytes && quality >= 58)
        require(output.length() in 1..MaxProfileOutputBytes) {
            "This image is too detailed to upload. Try a closer photo."
        }
    }
}

internal fun profileSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > ProfileImageEdge * 2) sample *= 2
    return sample
}
