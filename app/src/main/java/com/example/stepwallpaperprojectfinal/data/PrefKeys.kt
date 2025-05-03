package com.example.stepwallpaperprojectfinal.data

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PrefKeys {
    // Key for storing the URL of the current day's wallpaper image
    val IMAGE_URL = stringPreferencesKey("image_url")

    // Key for storing the timestamp (milliseconds) of the last successful image fetch
    val LAST_FETCH_TIMESTAMP = longPreferencesKey("last_fetch_timestamp")

    // Key for storing the calculated steps taken so far *today*
    val DAILY_STEP_COUNT = longPreferencesKey("daily_step_count")

    // Key for storing the raw step counter value at the beginning of the current day (or last fetch)
    // Using Float as TYPE_STEP_COUNTER returns Float, avoids potential casting issues later
    val STEP_BASELINE_AT_FETCH = floatPreferencesKey("step_baseline_at_fetch")

    // latest known raw step count
    val LATEST_RAW_STEP_COUNT = floatPreferencesKey("latest_raw_step_count")
}