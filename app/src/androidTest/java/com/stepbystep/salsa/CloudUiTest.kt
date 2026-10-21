package com.stepbystep.salsa

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudUiTest {
    private fun browser(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) {
                browser(view.getChildAt(i))?.let {
                    return it
                }
            }
        return null
    }

    private fun js(scenario: ActivityScenario<CloudUiActivity>, code: String): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { activity ->
            browser(activity.window.decorView)!!.evaluateJavascript(code) {
                result = it
                done.countDown()
            }
        }
        assertTrue("WebView callback timed out", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun await(scenario: ActivityScenario<CloudUiActivity>, predicate: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (js(scenario, predicate) == "true") return
            Thread.sleep(100)
        }
        fail("Cloud UI did not reach: $predicate")
    }

    @Test
    fun launcherUsesCloudLayoutAndBackProtectsTheEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        assertEquals(CloudUiActivity::class.java.name, launch.component!!.className)
        ActivityScenario.launch<CloudUiActivity>(launch).use { scenario ->
            await(scenario, "!!window.AndroidAppUI")
            assertEquals(
                "true",
                js(
                    scenario,
                    "document.querySelector('#overview') !== null && document.querySelector('nav [data-nav=cloud]') !== null",
                ),
            )
            js(scenario, "document.querySelector('#addFigure').click()")
            await(scenario, "document.querySelector('#editor').open")
            js(
                scenario,
                "document.querySelector('#figureTitle').value='Android Entwurf';document.querySelector('#figureTitle').dispatchEvent(new Event('input',{bubbles:true}))",
            )
            scenario.onActivity { it.onBackPressed() }
            await(scenario, "document.querySelector('#confirmDialog').open")
            assertEquals(
                "true",
                js(
                    scenario,
                    "document.querySelector('#confirmTitle').textContent.includes('Änderungen verwerfen')",
                ),
            )
            js(scenario, "document.querySelector('#confirmCancel').click()")
            await(
                scenario,
                "!document.querySelector('#confirmDialog').open && document.querySelector('#editor').open",
            )
        }
    }
}
