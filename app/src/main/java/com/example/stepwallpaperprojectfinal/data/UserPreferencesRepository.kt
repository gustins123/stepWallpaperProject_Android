package com.example.stepwallpaperprojectfinal.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class UserPreferencesRepository(context: Context) {

    // Get the singleton DataStore instance
    private val dataStore: DataStore<Preferences> = context.appSettingsStore

    // --- Read Operations (using Flows) ---

    val imageUrlFlow: Flow<String?> = dataStore.data
        .catch { exception -> // Handle errors reading preferences
            if (exception is IOException) {
                emit(emptyPreferences()) // Emit empty on IO error
            } else {
                throw exception // Rethrow other errors
            }
        }.map { preferences ->
            preferences[PrefKeys.IMAGE_URL] // Returns null if key doesn't exist
        }

    val lastFetchTimestampFlow: Flow<Long?> = dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences ->
            preferences[PrefKeys.LAST_FETCH_TIMESTAMP] // Returns null if key doesn't exist
        }

    // Provide a default value (0) if the key doesn't exist
    val dailyStepsFlow: Flow<Long> = dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences ->
            preferences[PrefKeys.DAILY_STEP_COUNT] ?: 0L
        }

    val stepBaselineFlow: Flow<Float?> = dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences ->
            preferences[PrefKeys.STEP_BASELINE_AT_FETCH] // Returns null if key doesn't exist
        }

    // Flow for reading the latest raw count
    val latestRawStepsFlow: Flow<Float?> = dataStore.data
        .catch { exception -> // Handle errors reading preferences
            if (exception is IOException) {
                emit(emptyPreferences()) // Emit empty on IO error
            } else {
                throw exception // Rethrow other errors
            }
        }.map { preferences ->
            preferences[PrefKeys.LATEST_RAW_STEP_COUNT] // Returns null if key doesn't exist
        }

    // --- Write Operations (Suspending Functions) ---

    suspend fun saveImageUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url == null) {
                preferences.remove(PrefKeys.IMAGE_URL)
            } else {
                preferences[PrefKeys.IMAGE_URL] = url
            }
        }
    }

    suspend fun saveLastFetchTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PrefKeys.LAST_FETCH_TIMESTAMP] = timestamp
        }
    }

    suspend fun saveDailySteps(steps: Long) {
        dataStore.edit { preferences ->
            preferences[PrefKeys.DAILY_STEP_COUNT] = steps
        }
    }

    suspend fun saveStepBaseline(baseline: Float?) {
        dataStore.edit { preferences ->
            if (baseline == null) {
                preferences.remove(PrefKeys.STEP_BASELINE_AT_FETCH)
            } else {
                preferences[PrefKeys.STEP_BASELINE_AT_FETCH] = baseline
            }
        }
    }
    // Function for saving the latest raw count (will be called by StepCheckAndUpdateWorker later)
    suspend fun saveLatestRawSteps(steps: Float?) {
        dataStore.edit { preferences ->
            if (steps == null) {
                preferences.remove(PrefKeys.LATEST_RAW_STEP_COUNT)
            } else {
                preferences[PrefKeys.LATEST_RAW_STEP_COUNT] = steps
            }
        }
    }

    // --- Combined Operations (Example: Resetting for a new day) ---
    suspend fun startNewDay(newUrl: String, fetchTimestamp: Long, currentSensorBaseline: Float?) {
        dataStore.edit { preferences ->
            preferences[PrefKeys.IMAGE_URL] = newUrl
            preferences[PrefKeys.LAST_FETCH_TIMESTAMP] = fetchTimestamp
            preferences[PrefKeys.DAILY_STEP_COUNT] = 0L // Reset daily steps
            if (currentSensorBaseline == null) {
                preferences.remove(PrefKeys.STEP_BASELINE_AT_FETCH)
            } else {
                preferences[PrefKeys.STEP_BASELINE_AT_FETCH] = currentSensorBaseline
            }
        }
    }
}