package com.stepbystep.salsa

import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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
    @Test
    fun physicalPlayerRotationAndFullscreenMatchSharedLayoutAndResetOnExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }
        val encoded = JSONObject.quote(Base64.encodeToString(fixture, Base64.NO_WRAP))
        ActivityScenario.launch(CloudUiActivity::class.java).use { scenario ->
            await(scenario, "!!window.AndroidAppUI")
            js(scenario, """
                (async()=>{
                  const storage=await import('./storage.js');
                  const bytes=Uint8Array.from(atob($encoded),c=>c.charCodeAt(0));
                  const id='physical-player-'+crypto.randomUUID();window.physicalPlayerId=id;
                  await storage.saveFigure({id,title:'Physical Player Test',description:'Player description',tags:[],status:'Neu'},[{id:crypto.randomUUID(),name:'Video',blob:new Blob([bytes],{type:'video/mp4'}),confirmed:true,duration:1,width:160,height:240}]);
                  window.dispatchEvent(new Event('focus'));window.physicalPlayerSeeded=true;
                })().catch(e=>window.physicalPlayerError=String(e))
            """.trimIndent())
            await(scenario, "!!window.physicalPlayerSeeded && !!document.querySelector('[data-figure-id=\"'+window.physicalPlayerId+'\"] .card-main')")
            js(scenario, "document.querySelector('[data-figure-id=\"'+window.physicalPlayerId+'\"] .card-main').click()")
            await(scenario, "!document.querySelector('#playerScreen').hidden && document.querySelector('#learningVideo').videoWidth>0")
            js(scenario, "document.querySelector('#rotatePlayer').click()")
            await(scenario, "innerWidth>innerHeight && document.querySelector('#playerScreen').classList.contains('is-landscape')")
            assertEquals("true",js(scenario,"getComputedStyle(document.querySelector('#playerScreen .bottom-nav')).display==='none' && getComputedStyle(document.querySelector('#playerDescription')).display==='none'"))
            js(scenario, "window.AndroidAppUI.back()")
            await(scenario, "document.querySelector('#playerScreen').hidden && innerHeight>innerWidth")
            js(scenario, "document.querySelector('[data-figure-id=\"'+window.physicalPlayerId+'\"] .card-main').click()")
            await(scenario, "!document.querySelector('#playerScreen').hidden")
            assertEquals("\"false\"",js(scenario,"document.querySelector('#rotatePlayer').getAttribute('aria-pressed')"))
            js(scenario, "document.querySelector('#fullscreen').click()")
            await(scenario, "document.querySelector('#playerScreen').classList.contains('is-native-fullscreen')")
            assertEquals("true",js(scenario,"getComputedStyle(document.querySelector('#playerScreen .bottom-nav')).display==='none'"))
            js(scenario, "window.AndroidAppUI.back()")
            await(scenario, "document.querySelector('#playerScreen').hidden && !document.querySelector('#playerScreen').classList.contains('is-native-fullscreen') && innerHeight>innerWidth")
            js(scenario, "(async()=>{const s=await import('./storage.js');const f=(await s.listFigures()).find(f=>f.id===window.physicalPlayerId);if(f)await s.deleteFigure(f);window.physicalPlayerCleaned=true;})()")
            await(scenario, "!!window.physicalPlayerCleaned")
        }
    }

}
