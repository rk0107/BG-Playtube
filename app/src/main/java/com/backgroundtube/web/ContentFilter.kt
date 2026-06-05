package com.backgroundtube.web

import android.net.Uri
import com.backgroundtube.data.UserSettings

class ContentFilter {
    @Volatile
    private var settings = UserSettings()

    fun updateSettings(settings: UserSettings) {
        this.settings = settings
    }

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val activeSettings = settings

        if (activeSettings.enableTrackingProtection && TRACKER_DOMAINS.matches(host)) {
            return true
        }

        val adDomains = AD_DOMAINS + activeSettings.customBlockedDomains.normalized()
        return activeSettings.enableGenericAdBlocking && adDomains.matches(host)
    }

    private fun Set<String>.matches(host: String): Boolean {
        return any { rule ->
            host == rule || host.endsWith(".$rule")
        }
    }

    private fun Set<String>.normalized(): Set<String> {
        return mapNotNull { raw ->
            raw.trim()
                .lowercase()
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .substringBefore("/")
                .takeIf { it.isNotBlank() }
        }.toSet()
    }

    private companion object {
        val TRACKER_DOMAINS = setOf(
            "doubleclick.net",
            "google-analytics.com",
            "googletagmanager.com",
            "googlesyndication.com",
            "scorecardresearch.com",
            "quantserve.com",
            "facebook.net",
            "facebook.com",
            "segment.io",
            "hotjar.com",
            "mixpanel.com",
            "app-measurement.com",
            "crashlytics.com"
        )

        val AD_DOMAINS = setOf(
            "adservice.google.com",
            "ad.doubleclick.net",
            "pagead2.googlesyndication.com",
            "adsystem.com",
            "adnxs.com",
            "adsafeprotected.com",
            "amazon-adsystem.com",
            "taboola.com",
            "outbrain.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "yieldmo.com",
            "moatads.com"
        )
    }
}
