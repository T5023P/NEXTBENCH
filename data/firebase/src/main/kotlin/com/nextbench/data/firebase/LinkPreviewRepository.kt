package com.nextbench.data.firebase

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val siteName: String? = null,
)

@Singleton
class LinkPreviewRepository @Inject constructor(
    private val functionsProvider: Provider<NbFunctions>,
) {
    private val cache = ConcurrentHashMap<String, LinkPreview>()
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(url: String): LinkPreview? {
        cache[url]?.let { return it }
        if (url in unavailable) return null

        val requestMutex = requestMutexes.getOrPut(url) { Mutex() }
        return requestMutex.withLock {
            cache[url]?.let { return@withLock it }
            if (url in unavailable) return@withLock null

            val preview = runCatching { functionsProvider.get().getLinkPreview(url).toLinkPreview(url) }.getOrNull()
            if (preview == null) unavailable += url else cache[url] = preview
            preview
        }
    }
}

fun firstMessageUrl(text: String?): String? {
    val raw = text
        ?.let { MessageUrlRegex.find(it)?.value }
        ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        ?.takeIf(String::isNotBlank)
        ?: return null
    return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
        raw
    } else {
        "https://$raw"
    }
}

internal fun Map<String, Any?>.toLinkPreview(requestedUrl: String): LinkPreview? {
    if (get("error") != null) return null
    val title = stringValue("title")
    val image = stringValue("image")
    if (title == null && image == null) return null
    return LinkPreview(
        url = stringValue("url") ?: requestedUrl,
        title = title,
        description = stringValue("description"),
        image = image,
        siteName = stringValue("siteName"),
    )
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    get(key)?.toString()?.trim()?.takeIf(String::isNotBlank)

private val MessageUrlRegex = Regex("(https?://[^\\s]+|www\\.[^\\s]+)", RegexOption.IGNORE_CASE)
