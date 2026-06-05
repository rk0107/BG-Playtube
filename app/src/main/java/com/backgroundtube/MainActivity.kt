package com.backgroundtube

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.backgroundtube.data.SessionStore
import com.backgroundtube.data.SettingsRepository
import com.backgroundtube.data.UserSettings
import com.backgroundtube.databinding.ActivityMainBinding
import com.backgroundtube.diagnostics.DiagnosticState
import com.backgroundtube.diagnostics.DiagnosticsStore
import com.backgroundtube.network.NetworkMonitor
import com.backgroundtube.playback.MediaPlaybackService
import com.backgroundtube.playback.PlaybackCommand
import com.backgroundtube.util.AppConstants
import com.backgroundtube.web.ContentFilter
import com.backgroundtube.web.WebViewManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), WebViewManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var webViewManager: WebViewManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var contentFilter: ContentFilter

    private var currentSettings = UserSettings()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var receiverRegistered = false
    private var mediaServiceStarted = false
    private var isApplyingSettingsToUi = false
    private var isCurrentPlaybackPlaying = false
    private var currentTitle = ""
    private var lastExitPressAt = 0L

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The foreground service still runs if permission is denied, but Android may hide
        // the drawer notification on Android 13+.
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = PlaybackCommand.from(intent.getStringExtra(AppConstants.EXTRA_COMMAND))
                ?: return
            webViewManager.handlePlaybackCommand(command)
            if (command == PlaybackCommand.STOP) {
                isCurrentPlaybackPlaying = false
                mediaServiceStarted = false
                DiagnosticsStore.updatePlayback("Stopped")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        settingsRepository = SettingsRepository(this)
        contentFilter = ContentFilter()
        webViewManager = WebViewManager(binding.webView, sessionStore, contentFilter, this)
        networkMonitor = NetworkMonitor(this) { isConnected ->
            runOnUiThread { renderNetworkState(isConnected) }
        }

        setupSystemBars()
        setupTopBar()
        setupSettingsPanel()
        setupDiagnosticsPanel()
        setupBackHandling()
        observeSettings()
        observeDiagnostics()
        registerCommandReceiver()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null || !webViewManager.restoreState(savedInstanceState)) {
            webViewManager.loadInitialUrl()
        }
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
    }

    override fun onResume() {
        super.onResume()
        webViewManager.onResume()
        if (isCurrentPlaybackPlaying && shouldUseForegroundService()) {
            mediaServiceStarted = true
            sendPlaybackStateToService(isPlaying = true, title = currentTitle)
        }
    }

    override fun onPause() {
        val keepPlaybackAlive = shouldKeepPlaybackAlive()
        if (keepPlaybackAlive) {
            mediaServiceStarted = true
            sendPlaybackStateToService(isPlaying = true, title = currentTitle)
        }
        webViewManager.onPause(keepPlaybackAlive)
        super.onPause()
    }

    override fun onStop() {
        networkMonitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterCommandReceiver()
        if (isFinishing) {
            stopService(Intent(this, MediaPlaybackService::class.java))
            webViewManager.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webViewManager.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webViewManager.applyDarkMode()
        setupSystemBars()
    }

    override fun onPageProgress(progress: Int) {
        binding.loadingProgress.progress = progress
        binding.loadingProgress.isVisible = progress in 1..99
    }

    override fun onPageTitleChanged(title: String) {
        currentTitle = title
        binding.titleText.text = title.ifBlank { getString(R.string.top_bar_title) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, title: String) {
        currentTitle = title
        isCurrentPlaybackPlaying = isPlaying
        sessionStore.savePlaybackState(isPlaying)
        sessionStore.saveTitle(title)
        DiagnosticsStore.updatePlayback(if (isPlaying) "Playing" else "Paused")

        if (isPlaying && shouldUseForegroundService()) {
            mediaServiceStarted = true
            sendPlaybackStateToService(isPlaying = true, title = title)
        } else if (mediaServiceStarted) {
            sendPlaybackStateToService(isPlaying = false, title = title)
        }
    }

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback

        return runCatching {
            fileChooserLauncher.launch(params.createIntent())
            true
        }.getOrElse {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            false
        }
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }

        fullscreenView = view
        fullscreenCallback = callback
        binding.fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenContainer.isVisible = true
        binding.webContainer.isVisible = false
        binding.topAppBar.isVisible = false
        binding.loadingProgress.isVisible = false
        enterImmersiveMode()
    }

    override fun onHideCustomView() {
        val view = fullscreenView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        binding.webContainer.isVisible = true
        binding.topAppBar.isVisible = true
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
        exitImmersiveMode()
    }

    override fun onPageLoadError(description: String) {
        binding.offlineBanner.isVisible = true
        Toast.makeText(this, R.string.page_load_error, Toast.LENGTH_SHORT).show()
    }

    override fun onExternalUrlRequested(uri: Uri) {
        openUriExternally(uri)
    }

    private fun setupTopBar() {
        binding.backButton.setOnClickListener {
            navigateBackOrExit()
        }
        binding.settingsButton.setOnClickListener {
            showSettingsPanel(true)
        }
        binding.refreshButton.setOnClickListener {
            webViewManager.reload()
        }
        binding.openBrowserButton.setOnClickListener {
            openUriExternally(Uri.parse(webViewManager.currentUrl()))
        }
        binding.titleText.setOnLongClickListener {
            showDiagnosticsPanel(true)
            true
        }
    }

    private fun setupSettingsPanel() {
        binding.settingsCloseButton.setOnClickListener {
            showSettingsPanel(false)
        }

        binding.switchBackgroundPlayback.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setBackgroundPlayback(checked) }
        }
        binding.switchScreenOffPlayback.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setScreenOffPlayback(checked) }
        }
        binding.switchForegroundService.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setForegroundService(checked) }
        }
        binding.switchWakeLock.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setWakeLock(checked) }
        }
        binding.switchTrackingProtection.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setTrackingProtection(checked) }
        }
        binding.switchAdBlocking.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setGenericAdBlocking(checked) }
        }
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            updateSetting { settingsRepository.setDarkMode(checked) }
        }
    }

    private fun setupDiagnosticsPanel() {
        binding.debugCloseButton.setOnClickListener {
            showDiagnosticsPanel(false)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        fullscreenView != null -> webViewManager.hideCustomView()
                        binding.debugPanel.isVisible -> showDiagnosticsPanel(false)
                        binding.settingsPanel.isVisible -> showSettingsPanel(false)
                        else -> navigateBackOrExit()
                    }
                }
            }
        )
    }

    private fun setupSystemBars() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorSurface)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.colorSurface)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isNight
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightNavigationBars = !isNight
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.settings.collect { settings ->
                    applySettings(settings)
                }
            }
        }
    }

    private fun observeDiagnostics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticsStore.state.collect { state ->
                    renderDiagnostics(state)
                }
            }
        }
    }

    private fun applySettings(settings: UserSettings) {
        currentSettings = settings
        webViewManager.applyUserSettings(settings)
        renderSettings(settings)
        applyDarkModeSetting(settings.darkMode)

        if (!shouldUseForegroundService() && mediaServiceStarted) {
            stopService(Intent(this, MediaPlaybackService::class.java))
            mediaServiceStarted = false
        } else if (isCurrentPlaybackPlaying && shouldUseForegroundService()) {
            mediaServiceStarted = true
            sendPlaybackStateToService(isPlaying = true, title = currentTitle)
        }
    }

    private fun renderSettings(settings: UserSettings) {
        isApplyingSettingsToUi = true
        binding.switchBackgroundPlayback.isChecked = settings.enableBackgroundPlayback
        binding.switchScreenOffPlayback.isChecked = settings.enableScreenOffPlayback
        binding.switchForegroundService.isChecked = settings.enableForegroundService
        binding.switchWakeLock.isChecked = settings.enableWakeLock
        binding.switchTrackingProtection.isChecked = settings.enableTrackingProtection
        binding.switchAdBlocking.isChecked = settings.enableGenericAdBlocking
        binding.switchDarkMode.isChecked = settings.darkMode
        isApplyingSettingsToUi = false
    }

    private fun renderDiagnostics(state: DiagnosticState) {
        binding.debugForegroundServiceText.text = getString(
            R.string.diagnostics_foreground_service,
            state.foregroundServiceState
        )
        binding.debugWakeLockText.text = getString(
            R.string.diagnostics_wake_lock,
            state.wakeLockState
        )
        binding.debugMediaSessionText.text = getString(
            R.string.diagnostics_media_session,
            state.mediaSessionState
        )
        binding.debugPlaybackText.text = getString(
            R.string.diagnostics_playback,
            state.playbackState
        )
        binding.debugNetworkText.text = getString(
            R.string.diagnostics_network,
            state.networkStatus
        )
    }

    private fun applyDarkModeSetting(enabled: Boolean) {
        val targetMode = if (enabled) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }
    }

    private fun updateSetting(block: suspend () -> Unit) {
        if (isApplyingSettingsToUi) return
        lifecycleScope.launch {
            block()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun registerCommandReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            commandReceiver,
            IntentFilter(AppConstants.ACTION_WEB_COMMAND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterCommandReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(commandReceiver) }
        receiverRegistered = false
    }

    private fun navigateBackOrExit() {
        if (webViewManager.canGoBack()) {
            webViewManager.goBack()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastExitPressAt < EXIT_CONFIRMATION_WINDOW_MS) {
            finish()
        } else {
            lastExitPressAt = now
            Toast.makeText(this, R.string.exit_prompt, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderNetworkState(isConnected: Boolean) {
        binding.offlineBanner.isVisible = !isConnected
        DiagnosticsStore.updateNetwork(if (isConnected) "Online" else "Offline")
    }

    private fun shouldUseForegroundService(): Boolean {
        return currentSettings.enableBackgroundPlayback && currentSettings.enableForegroundService
    }

    private fun shouldKeepPlaybackAlive(): Boolean {
        return isCurrentPlaybackPlaying &&
            currentSettings.enableBackgroundPlayback &&
            currentSettings.enableScreenOffPlayback &&
            currentSettings.enableForegroundService
    }

    private fun sendPlaybackStateToService(isPlaying: Boolean, title: String) {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            action = AppConstants.ACTION_SERVICE_STATE
            putExtra(AppConstants.EXTRA_IS_PLAYING, isPlaying)
            putExtra(AppConstants.EXTRA_TITLE, title)
        }

        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun openUriExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.content_open_browser)))
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettingsPanel(show: Boolean) {
        binding.settingsPanel.isVisible = show
        if (show) {
            binding.debugPanel.isVisible = false
        }
    }

    private fun showDiagnosticsPanel(show: Boolean) {
        binding.debugPanel.isVisible = show
        if (show) {
            binding.settingsPanel.isVisible = false
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        setupSystemBars()
    }

    private companion object {
        const val EXIT_CONFIRMATION_WINDOW_MS = 1800L
    }
}
