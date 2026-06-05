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
import com.backgroundtube.data.SessionStore
import com.backgroundtube.databinding.ActivityMainBinding
import com.backgroundtube.network.NetworkMonitor
import com.backgroundtube.playback.MediaPlaybackService
import com.backgroundtube.playback.PlaybackCommand
import com.backgroundtube.util.AppConstants
import com.backgroundtube.web.WebViewManager

class MainActivity : AppCompatActivity(), WebViewManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var webViewManager: WebViewManager
    private lateinit var networkMonitor: NetworkMonitor

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var receiverRegistered = false
    private var mediaServiceStarted = false
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
                mediaServiceStarted = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        webViewManager = WebViewManager(binding.webView, sessionStore, this)
        networkMonitor = NetworkMonitor(this) { isConnected ->
            runOnUiThread { renderNetworkState(isConnected) }
        }

        setupSystemBars()
        setupTopBar()
        setupBackHandling()
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
    }

    override fun onPause() {
        webViewManager.onPause()
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
        binding.titleText.text = title.ifBlank { getString(R.string.top_bar_title) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, title: String) {
        sessionStore.savePlaybackState(isPlaying)
        sessionStore.saveTitle(title)

        if (isPlaying) {
            mediaServiceStarted = true
        }

        if (mediaServiceStarted) {
            sendPlaybackStateToService(isPlaying, title)
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
        binding.refreshButton.setOnClickListener {
            webViewManager.reload()
        }
        binding.openBrowserButton.setOnClickListener {
            openUriExternally(Uri.parse(webViewManager.currentUrl()))
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullscreenView != null) {
                        webViewManager.hideCustomView()
                        return
                    }
                    navigateBackOrExit()
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
