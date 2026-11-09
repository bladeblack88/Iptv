package com.bladeblack88.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
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
    companion object {
        const val EXTRA_TEST_MODE = "com.bladeblack88.iptv.TEST_MODE"
    }

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
        webView.id = View.generateViewId()
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
        if (intent.getBooleanExtra(EXTRA_TEST_MODE, false)) {
            // Instrumentation supplies a deterministic local page after the activity starts.
            // Avoid racing that fixture against the normal hosted-page navigation.
        } else if (savedInstanceState == null) {
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
                function apply() {
                    if (state.focusUrl && typeof channels !== 'undefined') {
                        var all = Array.prototype.slice.call(document.querySelectorAll('#channelList .channel'));
                        var match = all.findIndex(function(el) {
                            var i = parseInt(el.getAttribute('data-index'), 10);
                            return !el.classList.contains('channel-filter-entry') && channels[i] && channels[i].url === state.focusUrl;
                        });
                        if (match >= 0 && typeof focusChannel === 'function') focusChannel(match);
                        window.tvFocusedChannelUrl = state.focusUrl;
                    } else if (typeof state.focusIndex === 'number' && typeof focusChannel === 'function') {
                        focusChannel(state.focusIndex);
                    }
                    if (list) list.scrollTop = Math.max(0, Number(state.top) || 0);
                    window.__apkSavedPlayerPosition = state;
                }
                apply();
                requestAnimationFrame(function() { requestAnimationFrame(apply); });
            })();
        """.trimIndent(), null)
    }

    private fun installRemoteControls() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("""
            (function() {
                if (window.__apkRemoteControlsInstalled) return;
                window.__apkRemoteControlsInstalled = true;

                function items() {
                    return Array.prototype.slice.call(document.querySelectorAll('#channelList .channel'));
                }
                function currentPosition(all) {
                    var active = document.activeElement;
                    var pos = all.indexOf(active);
                    if (pos >= 0) return pos;
                    // WebView may keep focus on the body while the visual TV focus
                    // is represented only by the .tv-focused class.
                    var visual = all.findIndex(function(el) { return el.classList.contains('tv-focused'); });
                    if (visual >= 0) return visual;
                    var url = window.tvFocusedChannelUrl || (window.__apkSavedPlayerPosition || {}).focusUrl;
                    if (url && typeof channels !== 'undefined') {
                        return all.findIndex(function(el) {
                            var n = parseInt(el.getAttribute('data-index'), 10);
                            return !el.classList.contains('channel-filter-entry') &&
                                channels[n] && channels[n].url === url;
                        });
                    }
                    return 0;
                }
                function save() {
                    var all = items(), active = document.activeElement;
                    var list = document.getElementById('channelList');
                    var n = active && active.classList && active.classList.contains('channel') ?
                        parseInt(active.getAttribute('data-index'), 10) : NaN;
                    var url = !isNaN(n) && typeof channels !== 'undefined' && channels[n] ? channels[n].url :
                        (window.tvFocusedChannelUrl || (window.__apkSavedPlayerPosition || {}).focusUrl || null);
                    var state = {top:list ? list.scrollTop : 0, focusIndex:all.indexOf(active),
                        focusUrl:url, activeIndex:typeof activeIndex === 'number' ? activeIndex : -1};
                    window.__apkSavedPlayerPosition = state;
                    try { localStorage.setItem('__apkPlayerPosition', JSON.stringify(state)); } catch(e) {}
                }
                function focusAt(pos, wantedUrl) {
                    var all = items();
                    if (!all.length) return;
                    if (wantedUrl) {
                        var found = all.findIndex(function(el) {
                            var n = parseInt(el.getAttribute('data-index'), 10);
                            return !el.classList.contains('channel-filter-entry') &&
                                typeof channels !== 'undefined' && channels[n] && channels[n].url === wantedUrl;
                        });
                        if (found >= 0) pos = found;
                    }
                    pos = Math.max(0, Math.min(all.length - 1, pos));
                    all.forEach(function(el) { el.classList.remove('tv-focused'); });
                    var target = all[pos];
                    target.classList.add('tv-focused'); target.tabIndex = 0; target.focus();
                    target.scrollIntoView({block:'nearest'});
                    var n = parseInt(target.getAttribute('data-index'), 10);
                    window.tvFocusedChannelUrl = !isNaN(n) && typeof channels !== 'undefined' && channels[n] ? channels[n].url : null;
                    save();
                }
                function move(delta) {
                    var all = items();
                    if (all.length) focusAt(Math.max(0, currentPosition(all)) + delta);
                }
                var lastOkChannelUrl = null;
                function isTvFullscreen() {
                    var video = document.getElementById('video');
                    var app = document.getElementById('app');
                    return !!(document.fullscreenElement ||
                        (video && video.webkitDisplayingFullscreen) ||
                        (app && app.classList.contains('tv-video-fullscreen')) ||
                        document.body.classList.contains('tv-video-fullscreen'));
                }
                function activate() {
                    var all = items(), pos = currentPosition(all), target = all[pos];
                    if (!target) return;
                    if (target.classList.contains('channel-filter-entry')) {
                        target.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                        lastOkChannelUrl = null;
                        return;
                    }
                    var n = parseInt(target.getAttribute('data-index'), 10);
                    if (isNaN(n) || !channels[n]) return;
                    var url = channels[n].url;
                    window.tvFocusedChannelUrl = url;
                    // First OK selects/starts the channel in the page player.
                    // A second OK on that same selected channel opens native TV fullscreen.
                    if (lastOkChannelUrl === url && typeof activeIndex === 'number' &&
                        activeIndex === n && typeof enterTvFullscreen === 'function') {
                        enterTvFullscreen();
                        lastOkChannelUrl = null;
                        save();
                        return;
                    }
                    if (typeof playChannel === 'function') {
                        playChannel(n);
                        lastOkChannelUrl = url;
                        save();
                    }
                }
                function toggleFavorite() {
                    var all = items(), pos = currentPosition(all), target = all[pos];
                    if (!target || target.classList.contains('channel-filter-entry')) return;
                    var n = parseInt(target.getAttribute('data-index'), 10);
                    if (isNaN(n) || typeof toggleFavoriteByIndex !== 'function') return;
                    var url = channels[n] && channels[n].url;
                    var top = document.getElementById('channelList')?.scrollTop || 0;
                    window.tvFocusedChannelUrl = url;
                    toggleFavoriteByIndex(n);
                    var updated = items();
                    var found = updated.findIndex(function(el) {
                        var i = parseInt(el.getAttribute('data-index'), 10);
                        return !el.classList.contains('channel-filter-entry') && channels[i] && channels[i].url === url;
                    });
                    if (found >= 0) focusAt(found, url);
                    var list = document.getElementById('channelList');
                    if (list) list.scrollTop = top;
                    save();
                }
                window.addEventListener('keydown', function(e) {
                    var key = e.key || '', code = e.keyCode;
                    // In fullscreen, leave OK/Back handling to the site's fullscreen
                    // exit logic and do not move the hidden channel-list focus.
                    if (isTvFullscreen()) return;
                    if (key === 'ArrowUp' || code === 19) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!e.repeat) move(-1);
                        return;
                    }
                    if (key === 'ArrowDown' || code === 20) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!e.repeat) move(1);
                        return;
                    }
                    if (key === 'ArrowRight' || code === 22) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (e.repeat) return;
                        var active = document.activeElement;
                        var inList = active && active.classList && active.classList.contains('channel') &&
                            document.getElementById('channelList') &&
                            document.getElementById('channelList').contains(active);
                        var all = items();
                        if (!inList) {
                            // Moving right from the video/player enters the channel list.
                            // This navigation must never change a channel's favorite state.
                            var firstChannel = all.findIndex(function(el) {
                                return !el.classList.contains('channel-filter-entry');
                            });
                            focusAt(firstChannel >= 0 ? firstChannel : 0);
                        } else if (active.classList.contains('channel-filter-entry')) {
                            var nextChannel = all.findIndex(function(el) {
                                return !el.classList.contains('channel-filter-entry');
                            });
                            if (nextChannel >= 0) focusAt(nextChannel);
                        } else {
                            toggleFavorite();
                        }
                        return;
                    }
                    if (key === 'Enter' || key === ' ' || code === 23 || code === 66) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        if (!e.repeat) activate();
                    }
                }, true);
                var list = document.getElementById('channelList');
                if (list) list.addEventListener('scroll', save, {passive:true});
                save();
            })();
        """.trimIndent(), null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Android TV sends remote keys to the Activity first. Route the keys used
        // by the web UI directly into its capture-phase handler instead of relying
        // on whichever DOM element happens to own WebView focus.
        if (customView != null || !::webView.isInitialized) {
            return super.dispatchKeyEvent(event)
        }

        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            else -> return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                val code = event.keyCode
                webView.evaluateJavascript(
                    "window.dispatchEvent(new KeyboardEvent('keydown', {key:'$key', keyCode:$code, which:$code, bubbles:true, cancelable:true}));",
                    null
                )
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) return true
        return true
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
