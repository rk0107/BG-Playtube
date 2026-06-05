package com.backgroundtube.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.userSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_settings"
)

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.userSettingsDataStore

    val settings: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            UserSettings(
                enableBackgroundPlayback = preferences[Keys.BACKGROUND_PLAYBACK] ?: true,
                enableScreenOffPlayback = preferences[Keys.SCREEN_OFF_PLAYBACK] ?: true,
                enableForegroundService = preferences[Keys.FOREGROUND_SERVICE] ?: true,
                enableWakeLock = preferences[Keys.WAKE_LOCK] ?: true,
                enableTrackingProtection = preferences[Keys.TRACKING_PROTECTION] ?: true,
                enableGenericAdBlocking = preferences[Keys.GENERIC_AD_BLOCKING] ?: true,
                darkMode = preferences[Keys.DARK_MODE] ?: false,
                customBlockedDomains = preferences[Keys.CUSTOM_BLOCKED_DOMAINS] ?: emptySet()
            )
        }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        updateBoolean(Keys.BACKGROUND_PLAYBACK, enabled)
    }

    suspend fun setScreenOffPlayback(enabled: Boolean) {
        updateBoolean(Keys.SCREEN_OFF_PLAYBACK, enabled)
    }

    suspend fun setForegroundService(enabled: Boolean) {
        updateBoolean(Keys.FOREGROUND_SERVICE, enabled)
    }

    suspend fun setWakeLock(enabled: Boolean) {
        updateBoolean(Keys.WAKE_LOCK, enabled)
    }

    suspend fun setTrackingProtection(enabled: Boolean) {
        updateBoolean(Keys.TRACKING_PROTECTION, enabled)
    }

    suspend fun setGenericAdBlocking(enabled: Boolean) {
        updateBoolean(Keys.GENERIC_AD_BLOCKING, enabled)
    }

    suspend fun setDarkMode(enabled: Boolean) {
        updateBoolean(Keys.DARK_MODE, enabled)
    }

    suspend fun setCustomBlockedDomains(domains: Set<String>) {
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_BLOCKED_DOMAINS] = domains
        }
    }

    private suspend fun updateBoolean(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[key] = enabled
        }
    }

    private object Keys {
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val SCREEN_OFF_PLAYBACK = booleanPreferencesKey("screen_off_playback")
        val FOREGROUND_SERVICE = booleanPreferencesKey("foreground_service")
        val WAKE_LOCK = booleanPreferencesKey("wake_lock")
        val TRACKING_PROTECTION = booleanPreferencesKey("tracking_protection")
        val GENERIC_AD_BLOCKING = booleanPreferencesKey("generic_ad_blocking")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val CUSTOM_BLOCKED_DOMAINS = stringSetPreferencesKey("custom_blocked_domains")
    }
}
