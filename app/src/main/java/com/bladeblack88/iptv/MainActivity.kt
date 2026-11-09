package com.bladeblack88.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
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
                savePlayerPosition()
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
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installRemoteControls()
                restorePlayerPosition()
            }
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
                var state = {
                    top: list ? list.scrollTop : 0,
                    focusIndex: typeof focusedChannelIndex === 'number' ? focusedChannelIndex : 0,
                    focusUrl: typeof tvFocusedChannelUrl === 'string' ? tvFocusedChannelUrl : null,
                    activeIndex: typeof activeIndex === 'number' ? activeIndex : -1
                };
                window.__apkSavedPlayerPosition = state;
                try { localStorage.setItem('__apkPlayerPosition', JSON.stringify(state)); } catch (e) {}
            })();
        """.trimIndent(), null)
    }

    private fun restorePlayerPosition() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                var state = window.__apkSavedPlayerPosition;
                try { state = state || JSON.parse(localStorage.getItem('__apkPlayerPosition') || 'null'); } catch (e) {}
                if (!state) return;
                var list = document.getElementById('channelList');
                if (list) list.scrollTop = Number(state.top) || 0;
                if (state.focusUrl && typeof channels !== 'undefined') {
                    tvFocusedChannelUrl = state.focusUrl;
                    var items = typeof channelItems === 'function' ? channelItems() : [];
                    var match = items.findIndex(function(el) {
                        if (el.classList.contains('channel-filter-entry')) return false;
                        var i = parseInt(el.getAttribute('data-index'), 10);
                        return channels[i] && channels[i].url === state.focusUrl;
                    });
                    if (match >= 0 && typeof focusChannel === 'function') focusChannel(match);
                } else if (typeof state.focusIndex === 'number' && typeof focusChannel === 'function') {
                    focusChannel(state.focusIndex);
                }
                window.__apkSavedPlayerPosition = state;
            })();
        """.trimIndent(), null)
    }

    private fun installRemoteControls() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                if (window.__apkRemoteControlsInstalled) return;
                window.__apkRemoteControlsInstalled = true;
                var upTimer = null;
                var upHeld = false;
                var lastKeyAt = 0;
                function getItems() {
                    return typeof channelItems === 'function' ? channelItems() : [];
                }
                function savePosition() {
                    var list = document.getElementById('channelList');
                    var state = {
                        top: list ? list.scrollTop : 0,
                        focusIndex: typeof focusedChannelIndex === 'number' ? focusedChannelIndex : 0,
                        focusUrl: typeof tvFocusedChannelUrl === 'string' ? tvFocusedChannelUrl : null,
                        activeIndex: typeof activeIndex === 'number' ? activeIndex : -1
                    };
                    window.__apkSavedPlayerPosition = state;
                    try { localStorage.setItem('__apkPlayerPosition', JSON.stringify(state)); } catch (e) {}
                }
                function move(delta) {
                    var list = getItems();
                    if (!list.length || typeof focusChannel !== 'function') return;
                    var current = list.indexOf(document.activeElement);
                    if (current < 0 && typeof focusedChannelIndex === 'number') current = focusedChannelIndex;
                    var next = Math.max(0, Math.min(list.length - 1, current + delta));
                    focusChannel(next);
                    var el = list[next];
                    if (el && !el.classList.contains('channel-filter-entry')) {
                        var index = parseInt(el.getAttribute('data-index'), 10);
                        if (!isNaN(index) && typeof playChannel === 'function') playChannel(index);
                    }
                    savePosition();
                }
                document.addEventListener('keydown', function(e) {
                    var key = e.key || '';
                    var code = e.keyCode;
                    if (key === 'ArrowUp' || code === 19) {
                        e.preventDefault(); e.stopImmediatePropagation();
                        if (!upTimer) {
                            upHeld = false;
                            upTimer = setTimeout(function() {
                                upHeld = true;
                                var list = document.getElementById('channelList');
                                if (list) list.scrollTop = 0;
                                if (typeof focusChannel === 'function') focusChannel(0);
                                savePosition();
                            }, 650);
                        }
                        return;
                    }
                    if (key === 'ArrowDown' || code === 20) {
                        e.preventDefault(); e.stopImmediatePropagation();
                        if (!e.repeat) move(1);
                        return;
                    }
                    if (key === 'Enter' || key === ' ' || code === 23 || code === 66) {
                        var active = document.activeElement;
                        if (active && active.classList && active.classList.contains('channel')) {
                            e.preventDefault(); e.stopImmediatePropagation(); active.click();
                        }
                    }
                }, true);
                document.addEventListener('keyup', function(e) {
                    if (e.key === 'ArrowUp' || e.keyCode === 19) {
                        e.preventDefault(); e.stopImmediatePropagation();
                        if (upTimer) clearTimeout(upTimer);
                        upTimer = null;
                        if (!upHeld) move(-1);
                        upHeld = false;
                    }
                }, true);
                var channelList = document.getElementById('channelList');
                if (channelList) channelList.addEventListener('scroll', savePosition, {passive:true});
                if (typeof focusChannel === 'function') {
                    var originalFocusChannel = focusChannel;
                    window.focusChannel = function(index) {
                        var result = originalFocusChannel.apply(this, arguments);
                        savePosition();
                        return result;
                    };
                }
                savePosition();
            })();
        """.trimIndent(), null)
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
