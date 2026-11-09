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
                var longPressMs = 650;

                function items() {
                    return Array.prototype.slice.call(document.querySelectorAll('#channelList .channel'));
                }
                function currentPosition(all) {
                    var active = document.activeElement;
                    var pos = all.indexOf(active);
                    if (pos >= 0) return pos;
                    var saved = window.__apkSavedPlayerPosition;
                    if (saved && saved.focusUrl && typeof channels !== 'undefined') {
                        return all.findIndex(function(el) {
                            var n = parseInt(el.getAttribute('data-index'), 10);
                            return !el.classList.contains('channel-filter-entry') &&
                                channels[n] && channels[n].url === saved.focusUrl;
                        });
                    }
                    return 0;
                }
                function save() {
                    var all = items();
                    var active = document.activeElement;
                    var list = document.getElementById('channelList');
                    var n = active && active.classList && active.classList.contains('channel') ?
                        parseInt(active.getAttribute('data-index'), 10) : NaN;
                    var state = {
                        top: list ? list.scrollTop : 0,
                        focusIndex: all.indexOf(active),
                        focusUrl: !isNaN(n) && typeof channels !== 'undefined' && channels[n] ? channels[n].url : null,
                        activeIndex: typeof activeIndex === 'number' ? activeIndex : -1
                    };
                    window.__apkSavedPlayerPosition = state;
                    try { localStorage.setItem('__apkPlayerPosition', JSON.stringify(state)); } catch (e) {}
                }
                function focusAt(pos) {
                    var all = items();
                    if (!all.length) return;
                    pos = Math.max(0, Math.min(all.length - 1, pos));
                    all.forEach(function(el) { el.classList.remove('tv-focused'); });
                    var target = all[pos];
                    target.classList.add('tv-focused');
                    target.tabIndex = 0;
                    target.focus();
                    target.scrollIntoView({block:'nearest'});
                    var n = parseInt(target.getAttribute('data-index'), 10);
                    window.tvFocusedChannelUrl = !isNaN(n) && typeof channels !== 'undefined' && channels[n] ? channels[n].url : null;
                    save();
                }
                function move(delta) {
                    var all = items();
                    if (!all.length) return;
                    var pos = currentPosition(all);
                    if (pos < 0) pos = 0;
                    focusAt(pos + delta);
                }
                function activate() {
                    var all = items();
                    var pos = currentPosition(all);
                    if (pos < 0 || !all[pos]) return;
                    var target = all[pos];
                    if (target.classList.contains('channel-filter-entry')) {
                        target.onclick && target.onclick.call(target);
                        return;
                    }
                    var n = parseInt(target.getAttribute('data-index'), 10);
                    if (!isNaN(n) && typeof playChannel === 'function') {
                        window.tvFocusedChannelUrl = channels[n] ? channels[n].url : null;
                        playChannel(n);
                        save();
                    }
                }
                document.addEventListener('keydown', function(e) {
                    var key = e.key || '';
                    var code = e.keyCode;
                    if (key === 'ArrowUp' || code === 19) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!upTimer) {
                            upHeld = false;
                            upTimer = setTimeout(function() {
                                upHeld = true;
                                var list = document.getElementById('channelList');
                                if (list) list.scrollTop = 0;
                                focusAt(0);
                            }, longPressMs);
                        }
                        return;
                    }
                    if (key === 'ArrowDown' || code === 20) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!e.repeat) move(1);
                        return;
                    }
                    if (key === 'Enter' || key === ' ' || code === 23 || code === 66) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!e.repeat) activate();
                    }
                }, true);
                document.addEventListener('keyup', function(e) {
                    if (e.key === 'ArrowUp' || e.keyCode === 19) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (upTimer) clearTimeout(upTimer);
                        upTimer = null;
                        if (!upHeld) move(-1);
                        upHeld = false;
                    }
                }, true);

                var channelList = document.getElementById('channelList');
                if (channelList) channelList.addEventListener('scroll', save, {passive:true});
                save();
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
