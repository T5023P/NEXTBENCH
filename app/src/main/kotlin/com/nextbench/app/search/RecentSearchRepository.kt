package com.nextbench.app.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val RecentSearchStoreName = "nextbench_search"
private const val RecentSearchLimit = 8
private const val RecentSearchSeparator = "\n"
private val RecentSearchKey = stringPreferencesKey("recent_searches")
private val Context.recentSearchDataStore by preferencesDataStore(name = RecentSearchStoreName)

@Singleton
class RecentSearchRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.recentSearchDataStore

    val searches: Flow<List<String>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> decodeRecentSearches(preferences[RecentSearchKey].orEmpty()) }

    suspend fun add(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return
        dataStore.edit { preferences ->
            val next = buildRecentSearches(normalized, decodeRecentSearches(preferences[RecentSearchKey].orEmpty()))
            preferences[RecentSearchKey] = next.joinToString(RecentSearchSeparator)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(RecentSearchKey) }
    }
}

internal fun buildRecentSearches(query: String, existing: List<String>): List<String> {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return existing.distinctBy(String::lowercase).take(RecentSearchLimit)
    return (listOf(normalized) + existing.filterNot { it.equals(normalized, ignoreCase = true) })
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .take(RecentSearchLimit)
}

private fun decodeRecentSearches(raw: String): List<String> =
    raw.split(RecentSearchSeparator).map(String::trim).filter(String::isNotBlank).take(RecentSearchLimit)
