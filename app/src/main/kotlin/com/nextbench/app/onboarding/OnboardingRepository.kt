package com.nextbench.app.onboarding

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private const val OnboardingStoreName = "nextbench_onboarding"
private val OnboardingCompleteKey = booleanPreferencesKey("onboarding_complete")
private val Context.onboardingDataStore by preferencesDataStore(name = OnboardingStoreName)

@Singleton
class OnboardingRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.onboardingDataStore

    val completionState: Flow<Boolean?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map<Preferences, Boolean?> { preferences -> preferences[OnboardingCompleteKey] ?: false }
        .onStart { emit(null) }

    suspend fun complete() {
        dataStore.edit { preferences -> preferences[OnboardingCompleteKey] = true }
    }
}
