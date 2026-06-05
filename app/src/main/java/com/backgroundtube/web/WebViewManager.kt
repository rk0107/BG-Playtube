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
import com.backgroundtube.data.UserSettings
import com.backgroundtube.playback.PlaybackCommand
import com.backgroundtube.util.AppConstants
import java.io.ByteArrayInputStream

class WebViewManager(
    private val webView: WebView,
    private val sessionStore: SessionStore,
    private val contentFilter: ContentFilter,
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
    private var currentSettings = UserSettings()
    private var lastReportedPlaying: Boolean? = null
    private var lastReportedTitle = ""
    private var lastPlaybackReportAt = 0L

    init {
        configureWebView()
    }

    fun applyUserSettings(settings: UserSettings) {
        currentSettings = settings
        contentFilter.updateSettings(settings)
        applyDarkMode()
        if (settings.enableBackgroundPlayback && settings.enableScreenOffPlayback) {
            installBackgroundKeepAliveScript()
        } else {
            setBackgroundPlaybackMode(false)
        }
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
        setBackgroundPlaybackMode(false)
        requestPlaybackSnapshot()
    }

    fun onPause(keepPlaybackAlive: Boolean) {
        sessionStore.saveLastUrl(currentUrl())
        if (keepPlaybackAlive) {
            installBackgroundKeepAliveScript()
            setBackgroundPlaybackMode(true)
            resumePrimaryMedia()
        } else {
            setBackgroundPlaybackMode(false)
            webView.onPause()
        }
    }

    fun prepareForBackgroundPlayback() {
        installBackgroundKeepAliveScript()
        setBackgroundPlaybackMode(true)
        resumePrimaryMedia()
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
                    window.__backgroundTubeUserPaused = false;
                    window.__backgroundTubeShouldResume = true;
                    var video = document.querySelector('video, audio');
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
                    window.__backgroundTubeUserPaused = true;
                    window.__backgroundTubeShouldResume = false;
                    var video = document.querySelector('video, audio');
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
                    window.__backgroundTubeUserPaused = true;
                    window.__backgroundTubeShouldResume = false;
                    var video = document.querySelector('video, audio');
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
                window.__backgroundTubeShouldResume = false;
                window.__backgroundTubeUserPaused = false;

                window.__backgroundTubeReportPlayback = function() {
                    try {
                        var media = document.querySelector('video, audio');
                        var playing = !!media && !media.paused && !media.ended && media.readyState >= 2;
                        var title = document.title || '';
                        BackgroundTubeBridge.postPlaybackState(playing, title);
                    } catch (ignored) {}
                };

                window.__backgroundTubeResumeIfNeeded = function() {
                    try {
                        var media = document.querySelector('video, audio');
                        if (!media) return;
                        if (
                            window.__backgroundTubeKeepAliveEnabled &&
                            window.__backgroundTubeBackgroundMode &&
                            window.__backgroundTubeShouldResume &&
                            !window.__backgroundTubeUserPaused &&
                            media.paused &&
                            !media.ended
                        ) {
                            var playPromise = media.play();
                            if (playPromise && playPromise.catch) {
                                playPromise.catch(function() {});
                            }
                        }
                    } catch (ignored) {}
                };

                window.__backgroundTubeAttachMediaObservers = function() {
                    var mediaNodes = document.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaNodes.length; i++) {
                        var media = mediaNodes[i];
                        if (media.__backgroundTubeAttached) {
                            continue;
                        }
                        media.__backgroundTubeAttached = true;
                        media.addEventListener('play', function() {
                            window.__backgroundTubeShouldResume = true;
                            window.__backgroundTubeUserPaused = false;
                            window.__backgroundTubeReportPlayback();
                        });
                        media.addEventListener('pause', function() {
                            window.__backgroundTubeReportPlayback();
                            setTimeout(window.__backgroundTubeResumeIfNeeded, 250);
                        });
                        media.addEventListener('ended', function() {
                            window.__backgroundTubeShouldResume = false;
                            window.__backgroundTubeReportPlayback();
                        });
                        media.addEventListener('loadedmetadata', window.__backgroundTubeReportPlayback);
                        media.addEventListener('timeupdate', window.__backgroundTubeReportPlayback);
                    }
                    window.__backgroundTubeReportPlayback();
                };

                var root = document.documentElement || document.body;
                if (root) {
                    new MutationObserver(function() {
                        window.__backgroundTubeAttachMediaObservers();
                    }).observe(root, { childList: true, subtree: true });
                }

                setInterval(window.__backgroundTubeAttachMediaObservers, 2000);
                setInterval(window.__backgroundTubeResumeIfNeeded, 1500);
                window.__backgroundTubeAttachMediaObservers();
            })();
            """.trimIndent()
        )
    }

    private fun installBackgroundKeepAliveScript() {
        evaluateJavascript(
            """
            (function() {
                if (window.__backgroundTubeKeepAliveInstalled) {
                    return;
                }
                window.__backgroundTubeKeepAliveInstalled = true;
                window.__backgroundTubeKeepAliveEnabled = false;
                window.__backgroundTubeBackgroundMode = false;

                var originalHiddenGetter = function() { return false; };
                var originalVisibilityGetter = function() { return 'visible'; };

                try {
                    var hiddenDescriptor =
                        Object.getOwnPropertyDescriptor(Document.prototype, 'hidden') ||
                        Object.getOwnPropertyDescriptor(document, 'hidden');
                    if (hiddenDescriptor && hiddenDescriptor.get) {
                        originalHiddenGetter = hiddenDescriptor.get.bind(document);
                    }
                } catch (ignored) {}

                try {
                    var visibilityDescriptor =
                        Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState') ||
                        Object.getOwnPropertyDescriptor(document, 'visibilityState');
                    if (visibilityDescriptor && visibilityDescriptor.get) {
                        originalVisibilityGetter = visibilityDescriptor.get.bind(document);
                    }
                } catch (ignored) {}

                try {
                    Object.defineProperty(document, 'hidden', {
                        configurable: true,
                        get: function() {
                            return window.__backgroundTubeKeepAliveEnabled ? false : originalHiddenGetter();
                        }
                    });
                } catch (ignored) {}

                try {
                    Object.defineProperty(document, 'visibilityState', {
                        configurable: true,
                        get: function() {
                            return window.__backgroundTubeKeepAliveEnabled ? 'visible' : originalVisibilityGetter();
                        }
                    });
                } catch (ignored) {}

                try {
                    var originalHasFocus = document.hasFocus ? document.hasFocus.bind(document) : function() { return true; };
                    document.hasFocus = function() {
                        return window.__backgroundTubeKeepAliveEnabled ? true : originalHasFocus();
                    };
                } catch (ignored) {}
            })();
            """.trimIndent()
        )
    }

    private fun setBackgroundPlaybackMode(enabled: Boolean) {
        val jsEnabled = enabled.toString()
        evaluateJavascript(
            """
            (function() {
                window.__backgroundTubeKeepAliveEnabled = $jsEnabled;
                window.__backgroundTubeBackgroundMode = $jsEnabled;
                if ($jsEnabled && window.__backgroundTubeResumeIfNeeded) {
                    window.__backgroundTubeResumeIfNeeded();
                }
            })();
            """.trimIndent()
        )
    }

    private fun resumePrimaryMedia() {
        evaluateJavascript(
            """
            (function() {
                window.__backgroundTubeUserPaused = false;
                window.__backgroundTubeShouldResume = true;
                if (window.__backgroundTubeResumeIfNeeded) {
                    window.__backgroundTubeResumeIfNeeded();
                }
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
        return if (uri != null && isHttpUri(uri)) {
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

        return false
    }

    private fun isHttpUri(uri: Uri): Boolean {
        return uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
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

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
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

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return if (contentFilter.shouldBlock(request.url)) {
                blockedResponse()
            } else {
                super.shouldInterceptRequest(view, request)
            }
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
            if (currentSettings.enableBackgroundPlayback && currentSettings.enableScreenOffPlayback) {
                installBackgroundKeepAliveScript()
            }
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
    }
}
