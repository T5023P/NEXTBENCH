package com.nextbench.data.firebase

import com.nextbench.data.model.Club
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

data class SearchResults(
    val people: List<UserData>,
    val posts: List<Post>,
    val listings: List<Product>,
    val clubs: List<Club>,
)

@Singleton
class SearchRepository @Inject constructor(
    private val functionsProvider: Provider<NbFunctions>,
) {
    private val functions get() = functionsProvider.get()

    suspend fun search(
        query: String,
        school: String = "",
        city: String = "",
    ): Result<SearchResults> = runCatching {
        ensureConfigured()
        functions.searchDiscovery(
            buildMap {
                put("query", query.trim())
                put("suggestions", query.isBlank())
                school.trim().takeIf(String::isNotBlank)?.let { put("school", it) }
                city.trim().takeIf(String::isNotBlank)?.let { put("city", it) }
            },
        ).toSearchResults()
    }

    private fun ensureConfigured() {
        if (!BuildConfig.FIREBASE_CONFIGURED) throw SearchConfigurationException()
    }
}

internal fun Map<String, Any?>.toSearchResults(): SearchResults = SearchResults(
    people = mapList("users").mapNotNull(Map<String, Any?>::toPublicUser),
    posts = mapList("posts").mapNotNull(Map<String, Any?>::toPost),
    listings = mapList("products").mapNotNull(Map<String, Any?>::toProduct),
    clubs = mapList("clubs").mapNotNull { data ->
        val id = data["id"]?.toString().orEmpty()
        id.takeIf(String::isNotBlank)?.let { data.toClub(it) }
    },
)

private class SearchConfigurationException : IllegalStateException(
    "Firebase is not configured for this build. Add app/google-services.json and rebuild.",
)
