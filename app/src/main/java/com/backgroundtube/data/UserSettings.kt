package com.backgroundtube.data

data class UserSettings(
    val enableBackgroundPlayback: Boolean = true,
    val enableScreenOffPlayback: Boolean = true,
    val enableForegroundService: Boolean = true,
    val enableWakeLock: Boolean = true,
    val enableTrackingProtection: Boolean = true,
    val enableGenericAdBlocking: Boolean = true,
    val darkMode: Boolean = false,
    val customBlockedDomains: Set<String> = emptySet()
)
