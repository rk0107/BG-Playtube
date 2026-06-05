package com.backgroundtube.web

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.backgroundtube.data.SessionStore
import com.backgroundtube.playback.PlaybackCommand
import com.backgroundtube.util.AppConstants

class WebViewManager(
    private val webView: WebView,
    private val sessionStore: SessionStore,
    private val listener: Listener
) {
    interface Listener {
        fun onPageProgress(progress: Int)
        fun onPageTitleChanged(title: String)
        fun onPlaybackStateChanged(isPlaying: Boolean, title: String)
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
        fun onPageLoadError(description: String)
        fun onExternalUrlRequested(uri: Uri)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chromeClient = BackgroundTubeChromeClient()
    private var lastReportedPlaying: Boolean? = null
    private var lastReportedTitle = ""
    private var lastPlaybackReportAt = 0L

    init {
        configureWebView()
    }

    fun loadInitialUrl() {
        webView.loadUrl(normalizeUrl(sessionStore.lastUrl))
    }

    fun restoreState(bundle: Bundle): Boolean {
        return webView.restoreState(bundle) != null
    }

    fun saveState(bundle: Bundle) {
        sessionStore.saveLastUrl(currentUrl())
        webView.saveState(bundle)
    }

    fun currentUrl(): String {
        return webView.url ?: sessionStore.lastUrl
    }

    fun canGoBack(): Boolean = webView.canGoBack()

    fun goBack() {
        webView.goBack()
    }

    fun reload() {
        webView.reload()
    }

    fun onResume() {
        webView.onResume()
        requestPlaybackSnapshot()
    }

    fun onPause() {
        sessionStore.saveLastUrl(currentUrl())
    }

    fun destroy() {
        sessionStore.saveLastUrl(currentUrl())
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        webView.destroy()
    }

    fun applyDarkMode() {
        val nightMode = webView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            val mode = if (isNight) {
                WebSettingsCompat.FORCE_DARK_ON
            } else {
                WebSettingsCompat.FORCE_DARK_OFF
            }
            WebSettingsCompat.setForceDark(webView.settings, mode)
        }
    }

    fun requestPlaybackSnapshot() {
        evaluateJavascript("window.__backgroundTubeReportPlayback && window.__backgroundTubeReportPlayback();")
    }

    fun handlePlaybackCommand(command: PlaybackCommand) {
        when (command) {
            PlaybackCommand.PLAY -> evaluateJavascript(
                """
                (function() {
                    var video = document.querySelector('video');
                    if (video) {
                        var playPromise = video.play();
                        if (playPromise && playPromise.catch) {
                            playPromise.catch(function() {});
                        }
                    }
                    window.__backgroundTubeReportPlayback && window.__backgroundTubeReportPlayback();
                })();
                """.trimIndent()
            )

            PlaybackCommand.PAUSE -> evaluateJavascript(
                """
                (function() {
                    var video = document.querySelector('video');
                    if (video) {
                        video.pause();
                    }
                    window.__backgroundTubeReportPlayback && window.__backgroundTubeReportPlayback();
                })();
                """.trimIndent()
            )

            PlaybackCommand.STOP -> evaluateJavascript(
                """
                (function() {
                    var video = document.querySelector('video');
                    if (video) {
                        video.pause();
                        try { video.currentTime = 0; } catch (ignored) {}
                    }
                    window.__backgroundTubeReportPlayback && window.__backgroundTubeReportPlayback();
                })();
                """.trimIndent()
            )
        }
    }

    fun hideCustomView() {
        chromeClient.onHideCustomView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowContentAccess = true
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.isSaveEnabled = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(PlaybackBridge(), JS_BRIDGE_NAME)
        webView.webViewClient = BackgroundTubeWebViewClient()
        webView.webChromeClient = chromeClient
        applyDarkMode()
    }

    private fun injectPlaybackObserver() {
        evaluateJavascript(
            """
            (function() {
                if (window.__backgroundTubeObserverInstalled) {
                    window.__backgroundTubeReportPlayback && window.__backgroundTubeReportPlayback();
                    return;
                }

                window.__backgroundTubeObserverInstalled = true;

                window.__backgroundTubeReportPlayback = function() {
                    try {
                        var video = document.querySelector('video');
                        var playing = !!video && !video.paused && !video.ended && video.readyState >= 2;
                        var title = document.title || '';
                        BackgroundTubeBridge.postPlaybackState(playing, title);
                    } catch (ignored) {}
                };

                window.__backgroundTubeAttachVideoObservers = function() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var video = videos[i];
                        if (video.__backgroundTubeAttached) {
                            continue;
                        }
                        video.__backgroundTubeAttached = true;
                        video.addEventListener('play', window.__backgroundTubeReportPlayback);
                        video.addEventListener('pause', window.__backgroundTubeReportPlayback);
                        video.addEventListener('ended', window.__backgroundTubeReportPlayback);
                        video.addEventListener('loadedmetadata', window.__backgroundTubeReportPlayback);
                        video.addEventListener('timeupdate', window.__backgroundTubeReportPlayback);
                    }
                    window.__backgroundTubeReportPlayback();
                };

                var root = document.documentElement || document.body;
                if (root) {
                    new MutationObserver(function() {
                        window.__backgroundTubeAttachVideoObservers();
                    }).observe(root, { childList: true, subtree: true });
                }

                setInterval(window.__backgroundTubeAttachVideoObservers, 2000);
                window.__backgroundTubeAttachVideoObservers();
            })();
            """.trimIndent()
        )
    }

    private fun evaluateJavascript(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script, null)
        } else {
            mainHandler.post { webView.evaluateJavascript(script, null) }
        }
    }

    private fun normalizeUrl(rawUrl: String?): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        return if (uri != null && isHttpUri(uri) && isAllowedInWebView(uri)) {
            uri.toString()
        } else {
            AppConstants.DEFAULT_URL
        }
    }

    private fun shouldOverrideNavigation(request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false

        val uri = request.url ?: return false
        if (!isHttpUri(uri)) {
            listener.onExternalUrlRequested(uri)
            return true
        }

        if (isAllowedInWebView(uri)) {
            return false
        }

        listener.onExternalUrlRequested(uri)
        return true
    }

    private fun isHttpUri(uri: Uri): Boolean {
        return uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
    }

    private fun isAllowedInWebView(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    private fun reportPlaybackState(isPlaying: Boolean, title: String?) {
        val cleanTitle = title.orEmpty().replace(" - YouTube", "").trim()
        val now = SystemClock.elapsedRealtime()
        val isDuplicate = lastReportedPlaying == isPlaying &&
            lastReportedTitle == cleanTitle &&
            now - lastPlaybackReportAt < PLAYBACK_REPORT_THROTTLE_MS

        if (isDuplicate) return

        lastReportedPlaying = isPlaying
        lastReportedTitle = cleanTitle
        lastPlaybackReportAt = now
        listener.onPlaybackStateChanged(isPlaying, cleanTitle)
    }

    private inner class PlaybackBridge {
        @JavascriptInterface
        fun postPlaybackState(isPlaying: Boolean, title: String?) {
            mainHandler.post {
                reportPlaybackState(isPlaying, title)
            }
        }
    }

    private inner class BackgroundTubeWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            return shouldOverrideNavigation(request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            sessionStore.saveLastUrl(url)
            listener.onPageProgress(0)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            sessionStore.saveLastUrl(url)
            listener.onPageProgress(100)
            injectPlaybackObserver()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                listener.onPageLoadError(error.description?.toString().orEmpty())
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                listener.onPageLoadError(errorResponse.reasonPhrase.orEmpty())
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            handler.cancel()
            listener.onPageLoadError(error.toString())
        }
    }

    private inner class BackgroundTubeChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            listener.onPageProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val cleanTitle = title.orEmpty().replace(" - YouTube", "").trim()
            sessionStore.saveTitle(cleanTitle)
            listener.onPageTitleChanged(cleanTitle)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            return listener.onShowFileChooser(filePathCallback, fileChooserParams)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            listener.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            listener.onHideCustomView()
        }
    }

    private companion object {
        const val JS_BRIDGE_NAME = "BackgroundTubeBridge"
        const val PLAYBACK_REPORT_THROTTLE_MS = 2500L

        val ALLOWED_HOST_SUFFIXES = listOf(
            "youtube.com",
            "youtu.be",
            "google.com",
            "google.co.in",
            "googleusercontent.com",
            "gstatic.com",
            "ytimg.com",
            "ggpht.com",
            "doubleclick.net"
        )
    }
}
