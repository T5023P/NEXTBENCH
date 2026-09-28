package com.nextbench.data.firebase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class CloudinaryResult(
    val url: String,
    val publicId: String,
    val width: Int,
    val height: Int,
    val format: String,
    val pages: Int? = null,
)

enum class CloudinaryResourceType(val path: String) {
    Auto("auto"),
    Image("image"),
    Video("video"),
    Raw("raw"),
}

/**
 * Unsigned Cloudinary upload — no server secret required. The upload preset is read from
 * BuildConfig (injected from local.properties at build time).
 */
@Singleton
class CloudinaryUploader @Inject constructor() {

    private val cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME
    private val uploadPreset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
    suspend fun upload(
        file: File,
        folder: String,
        resourceType: CloudinaryResourceType = CloudinaryResourceType.Auto,
    ): CloudinaryResult =
        withContext(Dispatchers.IO) {
            require(cloudName.isNotBlank() && uploadPreset.isNotBlank()) {
                "Cloudinary is not configured. Set CLOUDINARY_CLOUD_NAME and CLOUDINARY_UPLOAD_PRESET."
            }
            require(file.isFile && file.length() > 0L) { "Upload file is missing or empty." }
            require(folder.startsWith("nextbench/")) { "Uploads must use a NextBench folder." }

            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            val uploadUrl = "https://api.cloudinary.com/v1_1/$cloudName/${resourceType.path}/upload"
            val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            try {
                DataOutputStream(connection.outputStream).use { output ->
                    fun writeField(name: String, value: String) {
                        output.writeBytes("--$boundary\r\n")
                        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        output.writeBytes("$value\r\n")
                    }

                    writeField("upload_preset", uploadPreset)
                    writeField("folder", folder)
                    val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
                    output.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                    file.inputStream().use { it.copyTo(output) }
                    output.writeBytes("\r\n--$boundary--\r\n")
                }

                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = responseBody.takeIf(String::isNotBlank)?.let(::JSONObject)

                check(responseCode in 200..299) {
                    json?.optJSONObject("error")?.optString("message")
                        ?.takeIf(String::isNotBlank)
                        ?: "Cloudinary upload failed with status $responseCode."
                }

                requireNotNull(json) { "Cloudinary returned an empty response." }
                CloudinaryResult(
                    url = json.getString("secure_url"),
                    publicId = json.getString("public_id"),
                    width = json.optInt("width"),
                    height = json.optInt("height"),
                    format = json.optString("format"),
                    pages = json.optInt("pages").takeIf { it > 0 },
                )
            } finally {
                connection.disconnect()
            }
        }
}
