package com.backgroundtube.playback

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.ServiceCompat
import com.backgroundtube.R
import com.backgroundtube.util.AppConstants

class MediaPlaybackService : Service() {
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null

    private var isPlaying = false
    private var currentTitle = ""

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.ensureChannel()
        createWakeLock()
        createMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            AppConstants.ACTION_SERVICE_STATE -> {
                currentTitle = intent.getStringExtra(AppConstants.EXTRA_TITLE).orEmpty()
                isPlaying = intent.getBooleanExtra(AppConstants.EXTRA_IS_PLAYING, isPlaying)
                updateForegroundState()
            }

            AppConstants.ACTION_PLAY -> {
                isPlaying = true
                sendWebCommand(PlaybackCommand.PLAY)
                updateForegroundState()
            }

            AppConstants.ACTION_PAUSE -> {
                isPlaying = false
                sendWebCommand(PlaybackCommand.PAUSE)
                updateForegroundState()
            }

            AppConstants.ACTION_STOP -> {
                sendWebCommand(PlaybackCommand.STOP)
                stopPlayback()
                return START_NOT_STICKY
            }

            else -> updateForegroundState()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        mediaSession.release()
        super.onDestroy()
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "BackgroundTubeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    isPlaying = true
                    sendWebCommand(PlaybackCommand.PLAY)
                    updateForegroundState()
                }

                override fun onPause() {
                    isPlaying = false
                    sendWebCommand(PlaybackCommand.PAUSE)
                    updateForegroundState()
                }

                override fun onStop() {
                    sendWebCommand(PlaybackCommand.STOP)
                    stopPlayback()
                }
            })
            isActive = true
        }
        updateMediaSessionState()
    }

    private fun createWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:BackgroundTubePlayback"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun updateForegroundState() {
        updateMediaSessionState()
        updateWakeLock()

        val notification = notificationHelper.buildNotification(
            isPlaying = isPlaying,
            title = currentTitle,
            sessionToken = mediaSession.sessionToken
        )

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            AppConstants.NOTIFICATION_ID,
            notification,
            foregroundType
        )
    }

    private fun updateMediaSessionState() {
        val playbackState = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    currentTitle.ifBlank { getString(R.string.notification_title) }
                )
                .build()
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        val activeWakeLock = wakeLock ?: return
        if (isPlaying && !activeWakeLock.isHeld) {
            activeWakeLock.acquire()
        } else if (!isPlaying && activeWakeLock.isHeld) {
            activeWakeLock.release()
        }
    }

    private fun releaseWakeLock() {
        val activeWakeLock = wakeLock ?: return
        if (activeWakeLock.isHeld) {
            activeWakeLock.release()
        }
    }

    private fun sendWebCommand(command: PlaybackCommand) {
        val commandIntent = Intent(AppConstants.ACTION_WEB_COMMAND).apply {
            setPackage(packageName)
            putExtra(AppConstants.EXTRA_COMMAND, command.name)
        }
        sendBroadcast(commandIntent)
    }

    private fun stopPlayback() {
        isPlaying = false
        updateMediaSessionState()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
