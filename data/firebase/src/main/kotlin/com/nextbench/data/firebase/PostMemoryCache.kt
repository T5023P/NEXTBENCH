package com.nextbench.data.firebase

import com.nextbench.data.model.Post
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps recently displayed posts available when a guest opens a feed item. */
@Singleton
class PostMemoryCache @Inject constructor() {
    private val posts = object : LinkedHashMap<String, Post>(CacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Post>?): Boolean =
            size > CacheCapacity
    }

    @Synchronized
    fun get(postId: String): Post? = posts[postId]

    @Synchronized
    fun put(post: Post) {
        if (post.id.isNotBlank()) posts[post.id] = post
    }

    @Synchronized
    fun putAll(incoming: Iterable<Post>) = incoming.forEach(::put)

    private companion object {
        const val CacheCapacity = 100
    }
}
