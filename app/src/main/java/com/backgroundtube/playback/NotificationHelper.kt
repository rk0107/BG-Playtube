package com.backgroundtube.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import com.backgroundtube.MainActivity
import com.backgroundtube.R
import com.backgroundtube.util.AppConstants

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(
        isPlaying: Boolean,
        title: String,
        sessionToken: MediaSessionCompat.Token
    ): Notification {
        val displayTitle = title.ifBlank { context.getString(R.string.notification_title) }
        val contentText = if (isPlaying) {
            context.getString(R.string.notification_playing)
        } else {
            context.getString(R.string.notification_paused)
        }

        return NotificationCompat.Builder(context, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(contentText)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .addAction(
                R.drawable.ic_play,
                context.getString(R.string.action_play),
                serviceIntent(AppConstants.ACTION_PLAY, 1)
            )
            .addAction(
                R.drawable.ic_pause,
                context.getString(R.string.action_pause),
                serviceIntent(AppConstants.ACTION_PAUSE, 2)
            )
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.action_stop),
                serviceIntent(AppConstants.ACTION_STOP, 3)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, pendingIntentFlags())
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(context, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
