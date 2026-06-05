package com.backgroundtube.playback

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.ServiceCompat
import com.backgroundtube.R
import com.backgroundtube.data.SettingsRepository
import com.backgroundtube.data.UserSettings
import com.backgroundtube.diagnostics.DiagnosticsStore
import com.backgroundtube.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MediaPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var settingsRepository: SettingsRepository

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var settings = UserSettings()
    private var isForegroundStarted = false
    private var hasAudioFocus = false
    private var resumeAfterFocusGain = false
    private var isPlaying = false
    private var currentTitle = ""

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    isPlaying = true
                    sendWebCommand(PlaybackCommand.PLAY)
                    updateForegroundState()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocusGain = isPlaying
                isPlaying = false
                sendWebCommand(PlaybackCommand.PAUSE)
                updateForegroundState()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusGain = false
                isPlaying = false
                sendWebCommand(PlaybackCommand.PAUSE)
                updateForegroundState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.ensureChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        settingsRepository = SettingsRepository(this)
        createWakeLock()
        createMediaSession()
        observeSettings()
        updateDiagnostics()
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
        serviceScope.cancel()
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        isForegroundStarted = false
        updateDiagnostics()
        super.onDestroy()
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsRepository.settings.collectLatest { newSettings ->
                settings = newSettings
                if (!settings.enableWakeLock || !settings.enableScreenOffPlayback) {
                    releaseWakeLock()
                }
                if (isForegroundStarted) {
                    updateForegroundState()
                } else {
                    updateDiagnostics()
                }
            }
        }
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
        if (!settings.enableBackgroundPlayback || !settings.enableForegroundService) {
            stopServiceShell()
            return
        }

        if (isPlaying && !requestAudioFocus()) {
            isPlaying = false
            sendWebCommand(PlaybackCommand.PAUSE)
        }

        if (!isPlaying) {
            abandonAudioFocus()
        }

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
        isForegroundStarted = true
        updateDiagnostics()
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
        mediaSession.isActive = true
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .setWillPauseWhenDucked(false)
            .build()

        audioFocusRequest = request
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioManager.abandonAudioFocusRequest(request)
        audioFocusRequest = null
        hasAudioFocus = false
    }

    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        val shouldHoldWakeLock = isPlaying &&
            settings.enableBackgroundPlayback &&
            settings.enableScreenOffPlayback &&
            settings.enableWakeLock
        val activeWakeLock = wakeLock ?: return

        if (shouldHoldWakeLock && !activeWakeLock.isHeld) {
            activeWakeLock.acquire()
        } else if (!shouldHoldWakeLock && activeWakeLock.isHeld) {
            activeWakeLock.release()
        }
        updateDiagnostics()
    }

    private fun releaseWakeLock() {
        val activeWakeLock = wakeLock ?: return
        if (activeWakeLock.isHeld) {
            activeWakeLock.release()
        }
        updateDiagnostics()
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
        resumeAfterFocusGain = false
        updateMediaSessionState()
        abandonAudioFocus()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        updateDiagnostics()
        stopSelf()
    }

    private fun stopServiceShell() {
        abandonAudioFocus()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        updateDiagnostics()
        stopSelf()
    }

    private fun updateDiagnostics() {
        DiagnosticsStore.updateForegroundService(if (isForegroundStarted) "Running" else "Stopped")
        DiagnosticsStore.updateWakeLock(if (wakeLock?.isHeld == true) "Held" else "Released")
        DiagnosticsStore.updateMediaSession(if (::mediaSession.isInitialized && mediaSession.isActive) "Active" else "Inactive")
        DiagnosticsStore.updatePlayback(if (isPlaying) "Playing" else "Paused")
    }
}
