package com.backgroundtube.playback

enum class PlaybackCommand {
    PLAY,
    PAUSE,
    STOP;

    companion object {
        fun from(value: String?): PlaybackCommand? {
            return entries.firstOrNull { it.name == value }
        }
    }
}
