package com.bladeblack88.iptv

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteControlInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: MainActivity? = null
    private var webView: WebView? = null

    // Launch asynchronously: startActivitySync waits for the main queue to become idle,
    // which is unreliable for a WebView loading a live IPTV page.
    @Before
    fun launchApp() {
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_TEST_MODE, true)
        )

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && activity == null) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
            }
            if (activity == null) Thread.sleep(100)
        }
        assertTrue("MainActivity did not reach RESUMED", activity != null)

        instrumentation.runOnMainSync {
            webView = findWebView(activity!!.window.decorView)
            assertTrue("WebView was not created", webView != null)
            // Use a deterministic local page so tests exercise the APK's real key handler
            // without depending on GitHub Pages, playlist downloads, or stream availability.
            webView!!.loadDataWithBaseURL(
                "https://bladeblack88.github.io/Iptv/",
                testPage,
                "text/html",
                "UTF-8",
                null
            )
        }
        waitForPage()
    }

    @After
    fun closeApp() {
        instrumentation.runOnMainSync {
            activity?.finish()
            activity = null
            webView = null
        }
    }

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

    private fun evaluate(script: String): String {
        val view = webView ?: throw AssertionError("WebView unavailable")
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                result.set(it)
                latch.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", latch.await(15, TimeUnit.SECONDS))
        return result.get() ?: "null"
    }

    private fun waitForPage() {
        val deadline = System.currentTimeMillis() + 15_000
        var ready = false
        while (System.currentTimeMillis() < deadline && !ready) {
            ready = evaluate(
                "!!(window.AndroidApp && AndroidApp.isNativeApp() && window.__apkRemoteControlsInstalled === true && document.querySelector('#channelList .channel'))"
            ) == "true"
            if (!ready) Thread.sleep(100)
        }
        assertTrue("Local test page or Android bridge did not become ready", ready)
    }

    private fun press(keyCode: Int) {
        // Keep the DOM channel focused between consecutive OK presses.
        // Re-requesting native WebView focus here steals focus from the DOM
        // after the first press and makes the second OK miss the fullscreen path.
        instrumentation.sendKeyDownUpSync(keyCode)
    }

    @Test
    fun appLoadsWebViewAndNativeBridge() {
        assertEquals("true", evaluate("AndroidApp.isNativeApp()"))
        assertEquals("true", evaluate("AndroidApp.isAndroidTv() === true || AndroidApp.isAndroidTv() === false"))
        assertEquals("2", evaluate("document.querySelectorAll('#channelList .channel').length"))
    }

    @Test
    fun dpadDownMovesFocusToNextChannel() {
        assertEquals("true", evaluate("""
            (function() {
              var rows = document.querySelectorAll('#channelList .channel');
              rows[0].classList.add('tv-focused');
              rows[0].focus();
              return document.activeElement === rows[0];
            })()
        """.trimIndent()))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("1", evaluate("""
            Array.from(document.querySelectorAll('#channelList .channel'))
              .findIndex(function(el) { return el.classList.contains('tv-focused'); })
        """.trimIndent()))
    }

    @Test
    fun movingRightFromPlayerToListDoesNotToggleFavorite() {
        instrumentation.runOnMainSync { webView?.requestFocus() }
        assertEquals("true", evaluate("""
            (function() {
              document.body.tabIndex = 0;
              document.body.focus();
              return document.activeElement === document.body;
            })()
        """.trimIndent()))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("0", evaluate("favoriteToggleCount"))
        assertEquals("0", evaluate("""
            Array.from(document.querySelectorAll('#channelList .channel'))
              .findIndex(function(el) { return el.classList.contains('tv-focused'); })
        """.trimIndent()))
    }

    @Test
    fun pressingOkTwiceOnSameChannelRequestsFullscreen() {
        evaluate("document.querySelector('#channelList .channel').focus()")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("0", evaluate("activeIndex"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("true", evaluate("document.body.classList.contains('tv-video-fullscreen')"))
    }

    @Test
    fun okDoesNotAccidentallyToggleFavorite() {
        evaluate("document.querySelector('#channelList .channel').focus()")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("0", evaluate("favoriteToggleCount"))
    }

    private val testPage = """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { background:#111; color:white; }
          .channel { display:block; padding:24px; margin:8px; background:#333; }
          .tv-focused { outline:4px solid yellow; }
        </style></head><body>
        <div id="channelList">
          <div class="channel" data-index="0" tabindex="0">Test channel one</div>
          <div class="channel" data-index="1" tabindex="0">Test channel two</div>
        </div>
        <script>
          var channels = [{url:'https://example.test/one'}, {url:'https://example.test/two'}];
          var activeIndex = -1;
          var favoriteToggleCount = 0;
          function playChannel(index) { activeIndex = index; }
          function enterTvFullscreen() { document.body.classList.add('tv-video-fullscreen'); }
          function toggleFavoriteByIndex() { favoriteToggleCount++; }
        </script></body></html>
    """.trimIndent()
}
