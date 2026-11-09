package com.bladeblack88.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {
    // Exposes a small, read-only marker so the website can distinguish the APK WebView
    // from a regular Android browser and hide its own APK download link.
    class AppBridge(private val androidTv: Boolean) {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true
        @JavascriptInterface
        fun isAndroidTv(): Boolean = androidTv
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val homeUrl = "https://bladeblack88.github.io/Iptv/"
    private val keyHandler = Handler(Looper.getMainLooper())
    private var upLongPressTriggered = false
    private var upLongPressRunnable: Runnable? = null
    private var upKeyDownAt = 0L
    private var pendingChannelKey = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        rootLayout = FrameLayout(this)
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(AppBridge(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)), "AndroidApp")
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadsImagesAutomatically = true
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.allowFileAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            // Android WebView requires a native custom view for HTML5 video fullscreen.
            // Without this callback, requestFullscreen() can leave the video surface black.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                view.setBackgroundColor(android.graphics.Color.BLACK)
                rootLayout.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                view.requestFocus()
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
        // Keep navigation inside WebView so embedded players and stream pages load in-app.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }

        rootLayout.addView(webView)
        setContentView(rootLayout)
        if (savedInstanceState == null) {
            webView.loadUrl(homeUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun savePlayerPosition() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                var list = document.getElementById('channelList');
                window.__apkSavedListTop = list ? list.scrollTop : 0;
                window.__apkSavedFocusIndex = typeof focusedChannelIndex === 'number' ? focusedChannelIndex : 0;
                window.__apkSavedFocusUrl = typeof tvFocusedChannelUrl === 'string' ? tvFocusedChannelUrl : null;
                window.__apkSavedActiveIndex = typeof activeIndex === 'number' ? activeIndex : -1;
            })();
        """.trimIndent(), null)
    }

    private fun restorePlayerPosition() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                var list = document.getElementById('channelList');
                if (list && typeof window.__apkSavedListTop === 'number') list.scrollTop = window.__apkSavedListTop;
                if (typeof window.__apkSavedFocusUrl === 'string' && window.__apkSavedFocusUrl) {
                    tvFocusedChannelUrl = window.__apkSavedFocusUrl;
                    var items = channelItems();
                    var match = items.findIndex(function(el) {
                        if (el.classList.contains('channel-filter-entry')) return false;
                        var i = parseInt(el.getAttribute('data-index'), 10);
                        return channels[i] && channels[i].url === window.__apkSavedFocusUrl;
                    });
                    if (match >= 0) focusChannel(match);
                } else if (typeof window.__apkSavedFocusIndex === 'number') {
                    focusChannel(window.__apkSavedFocusIndex);
                }
            })();
        """.trimIndent(), null)
    }

    private fun switchChannel(direction: Int) {
        if (!::webView.isInitialized) return
        val js = """
            (function() {
                if (typeof channels === 'undefined' || !channels.length || typeof playChannel !== 'function') return;
                var items = typeof channelItems === 'function' ? channelItems() : [];
                var current = -1;
                if (typeof activeIndex === 'number' && activeIndex >= 0) {
                    current = items.findIndex(function(el) {
                        return !el.classList.contains('channel-filter-entry') &&
                            parseInt(el.getAttribute('data-index'), 10) === activeIndex;
                    });
                }
                if (current < 0) current = 0;
                var next = Math.max(0, Math.min(items.length - 1, current + ($direction)));
                while (next >= 0 && next < items.length && items[next].classList.contains('channel-filter-entry')) {
                    next += ($direction);
                }
                if (next < 0 || next >= items.length) return;
                var index = parseInt(items[next].getAttribute('data-index'), 10);
                if (!isNaN(index)) {
                    if (typeof focusChannel === 'function') focusChannel(next);
                    playChannel(index);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun jumpToListTop() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                var list = document.getElementById('channelList');
                if (list) list.scrollTop = 0;
                if (typeof focusChannel === 'function') focusChannel(0);
            })();
        """.trimIndent(), null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        upLongPressTriggered = false
                        upKeyDownAt = android.os.SystemClock.elapsedRealtime()
                        upLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
                        val task = Runnable {
                            upLongPressTriggered = true
                            jumpToListTop()
                        }
                        upLongPressRunnable = task
                        keyHandler.postDelayed(task, 550L)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    upLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
                    upLongPressRunnable = null
                    if (!upLongPressTriggered) switchChannel(-1)
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) return true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                switchChannel(1)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Android")
    private fun hideCustomView() {
                savePlayerPosition()
        val view = customView ?: return
        rootLayout.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
        webView.postDelayed({ restorePlayerPosition() }, 150L)
    }

    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
