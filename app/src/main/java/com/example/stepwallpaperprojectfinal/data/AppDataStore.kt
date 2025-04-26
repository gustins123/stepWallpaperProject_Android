package com.example.stepwallpaperprojectfinal.data

import android.content.Context
import androidx.datastore.core.DataStore // Import core DataStore
import androidx.datastore.preferences.core.Preferences // Import Preferences
import androidx.datastore.preferences.preferencesDataStore // Import the delegate

// Define the DataStore instance as an extension property on Context
// "app_settings" will be the name of the preferences file created on disk
val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")