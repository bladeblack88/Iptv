package com.bladeblack88.iptv

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteControlInstrumentedTest {
    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java)

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun evaluate(webView: WebView, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) {
                result.set(it)
                latch.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get() ?: "null"
    }

    private fun waitForPage(webView: WebView) {
        val deadline = System.currentTimeMillis() + 60_000
        var ready = false
        while (System.currentTimeMillis() < deadline && !ready) {
            val state = evaluate(
                webView,
                "!!(window.AndroidApp && AndroidApp.isNativeApp() && document.querySelector('#channelList .channel'))"
            )
            ready = state == "true"
            if (!ready) Thread.sleep(500)
        }
        assertTrue("IPTV page or Android bridge did not become ready", ready)
    }

    @Test
    fun appLoadsIptvPageAndNativeBridge() {
        val webView = findWebView(activityRule.activity.window.decorView)
            ?: throw AssertionError("WebView was not created")
        waitForPage(webView)
        assertEquals("true", evaluate(webView, "AndroidApp.isNativeApp()"))
        assertEquals("true", evaluate(webView, "document.querySelectorAll('#channelList .channel').length > 0"))
    }

    @Test
    fun dpadDownMovesFocusToNextChannel() {
        val webView = findWebView(activityRule.activity.window.decorView)
            ?: throw AssertionError("WebView was not created")
        waitForPage(webView)

        val prepared = evaluate(webView, """
            (function() {
              var rows = Array.from(document.querySelectorAll('#channelList .channel'))
                .filter(function(el) { return !el.classList.contains('channel-filter-entry'); });
              if (rows.length < 2) return false;
              rows.forEach(function(el) { el.classList.remove('tv-focused'); });
              rows[0].classList.add('tv-focused');
              rows[0].focus();
              return true;
            })()
        """.trimIndent())
        assertEquals("true", prepared)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val focusedIndex = evaluate(webView, """
            (function() {
              var rows = Array.from(document.querySelectorAll('#channelList .channel'));
              return rows.findIndex(function(el) { return el.classList.contains('tv-focused'); });
            })()
        """.trimIndent()).trim('"').toIntOrNull() ?: -1
        assertTrue("DPAD_DOWN did not move focus to another list item (index=$focusedIndex)", focusedIndex > 0)
    }

    @Test
    fun pressingOkTwiceOnSelectedChannelRequestsFullscreen() {
        val webView = findWebView(activityRule.activity.window.decorView)
            ?: throw AssertionError("WebView was not created")
        waitForPage(webView)

        val prepared = evaluate(webView, """
            (function() {
              var row = Array.from(document.querySelectorAll('#channelList .channel'))
                .find(function(el) { return !el.classList.contains('channel-filter-entry'); });
              if (!row) return false;
              document.querySelectorAll('#channelList .channel').forEach(function(el) {
                el.classList.remove('tv-focused');
              });
              row.classList.add('tv-focused');
              row.focus();
              return true;
            })()
        """.trimIndent())
        assertEquals("true", prepared)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

        val selected = System.currentTimeMillis() + 20_000
        var playing = false
        while (System.currentTimeMillis() < selected && !playing) {
            playing = evaluate(webView,
                "typeof activeIndex === 'number' && activeIndex >= 0").trim() == "true"
            if (!playing) Thread.sleep(300)
        }
        assertTrue("First OK did not select/start a channel", playing)

        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val fullscreenDeadline = System.currentTimeMillis() + 15_000
        var fullscreen = false
        while (System.currentTimeMillis() < fullscreenDeadline && !fullscreen) {
            val nativeFullscreen = AtomicReference(false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val parent = webView.parent as? ViewGroup
                nativeFullscreen.set(parent != null && parent.childCount > 1)
            }
            fullscreen = nativeFullscreen.get() ||
                evaluate(webView, "!!(document.fullscreenElement || (document.getElementById('video') && document.getElementById('video').webkitDisplayingFullscreen))").trim() == "true"
            if (!fullscreen) Thread.sleep(300)
        }
        assertTrue("Second OK did not enter fullscreen", fullscreen)
    }

}
