package com.backgroundtube.util

object AppConstants {
    const val DEFAULT_URL = "https://m.youtube.com"

    const val PREFS_NAME = "background_tube_prefs"
    const val KEY_LAST_URL = "last_url"
    const val KEY_LAST_TITLE = "last_title"
    const val KEY_LAST_PLAYING = "last_playing"

    const val NOTIFICATION_CHANNEL_ID = "background_tube_media"
    const val NOTIFICATION_ID = 4201

    const val ACTION_SERVICE_STATE = "com.backgroundtube.action.SERVICE_STATE"
    const val ACTION_PLAY = "com.backgroundtube.action.PLAY"
    const val ACTION_PAUSE = "com.backgroundtube.action.PAUSE"
    const val ACTION_STOP = "com.backgroundtube.action.STOP"
    const val ACTION_WEB_COMMAND = "com.backgroundtube.action.WEB_COMMAND"

    const val EXTRA_COMMAND = "extra_command"
    const val EXTRA_IS_PLAYING = "extra_is_playing"
    const val EXTRA_TITLE = "extra_title"
}
