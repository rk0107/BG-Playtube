package com.backgroundtube.data

import android.content.Context
import androidx.core.content.edit
import com.backgroundtube.util.AppConstants

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        AppConstants.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val lastUrl: String
        get() = prefs.getString(AppConstants.KEY_LAST_URL, AppConstants.DEFAULT_URL)
            ?: AppConstants.DEFAULT_URL

    val lastTitle: String
        get() = prefs.getString(AppConstants.KEY_LAST_TITLE, "") ?: ""

    val wasPlaying: Boolean
        get() = prefs.getBoolean(AppConstants.KEY_LAST_PLAYING, false)

    fun saveLastUrl(url: String?) {
        val cleanUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return
        prefs.edit {
            putString(AppConstants.KEY_LAST_URL, cleanUrl)
        }
    }

    fun saveTitle(title: String?) {
        prefs.edit {
            putString(AppConstants.KEY_LAST_TITLE, title.orEmpty())
        }
    }

    fun savePlaybackState(isPlaying: Boolean) {
        prefs.edit {
            putBoolean(AppConstants.KEY_LAST_PLAYING, isPlaying)
        }
    }
}
