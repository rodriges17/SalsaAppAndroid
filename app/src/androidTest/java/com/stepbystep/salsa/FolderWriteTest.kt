package com.stepbystep.salsa

import android.content.ComponentName
import android.content.Intent
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
class FolderWriteTest {
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun js(scenario: ActivityScenario<CloudUiActivity>, code: String): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { activity -> web(activity.window.decorView)!!.evaluateJavascript(code) { result = it; done.countDown() } }
        assertTrue("WebView response timed out", done.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun await(scenario: ActivityScenario<CloudUiActivity>, predicate: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (js(scenario, predicate) == "true") return
            Thread.sleep(100)
        }
        fail("SAF did not reach $predicate; " + js(scenario, "window.folderWriteFailure"))
    }
    @Test
    fun grantedProviderCopiesQueuedBytesAndPropagatesDeniedWritesWithoutLeakingStaging() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(CloudUiActivity::class.java).use { scenario ->
            await(scenario, "!!window.AndroidAppUI")
            js(scenario, """
                window.testGrantCount=0;
                window.AndroidFSResult=(value,error)=>{
                  if(error){window.folderWriteFailure=error;return;}
                  window.testFolder=reviveAndroidHandle({androidHandle:1,...value});
                  window.testGrantCount++;
                };
            """.trimIndent())
            scenario.onActivity { activity ->
                activity.startActivityForResult(Intent().setComponent(ComponentName(
                    instrumentation.context.packageName, FolderGrantActivity::class.java.name,
                )), NativeFiles.PICK_DIRECTORY)
            }
            await(scenario, "!!window.testFolder")
            js(scenario, """
                (async()=>{
                  const name=crypto.randomUUID()+'.bin';
                  const file=await testFolder.getFileHandle(name,{create:true});
                  const expected=Uint8Array.from({length:900017},(_,i)=>i%251);
                  const writer=await file.createWritable();
                  const one=writer.write(new Blob([expected.subarray(0,400003)]));
                  const two=writer.write(new Blob([expected.subarray(400003)]));
                  const closing=writer.close(),duplicate=writer.close();
                  await writer.abort();
                  await Promise.all([one,two,closing,duplicate]);
                  const actual=new Uint8Array(await(await file.getFile()).arrayBuffer());
                  if(actual.length!==expected.length||!actual.every((byte,i)=>byte===expected[i]))throw Error('Provider bytes changed');
                  const denied=await testFolder.getFileHandle('denied-'+crypto.randomUUID()+'.bin',{create:true});
                  const failed=await denied.createWritable();await failed.write('kein Zugriff');
                  let failure;try{await failed.close();}catch(e){failure=e.name;}
                  if(failure!=='NotAllowedError')throw Error('Unexpected permission result: '+failure);
                  await failed.abort();
                  const retry=await testFolder.getFileHandle('retry-'+crypto.randomUUID()+'.bin',{create:true});
                  const next=await retry.createWritable();await next.write('erneut');await next.close();
                  if(await(await retry.getFile()).text()!=='erneut')throw Error('Retry bytes changed');
                  window.folderWriteResult={size:actual.length,sameClose:closing===duplicate,denied:failure};
                })().catch(error=>window.folderWriteFailure=String(error.stack||error));
            """.trimIndent())
            await(scenario, "!!window.folderWriteResult || !!window.folderWriteFailure")
            assertEquals("null", js(scenario, "window.folderWriteFailure ?? null"))
            val result = JSONObject(org.json.JSONTokener(js(scenario, "JSON.stringify(window.folderWriteResult)")).nextValue() as String)
            assertEquals(900017, result.getInt("size"))
            assertTrue(result.getBoolean("sameClose"))
            assertEquals("NotAllowedError", result.getString("denied"))
            js(scenario, """
                (async()=>{
                  window.revokedHandle=await testFolder.getFileHandle('revoked-'+crypto.randomUUID()+'.bin',{create:true});
                  const initial=await revokedHandle.createWritable();await initial.write('vorher');await initial.close();
                  window.revokedWriter=await revokedHandle.createWritable();await revokedWriter.write('darf nicht kopiert werden');
                  window.revocationReady=true;
                })().catch(error=>window.folderWriteFailure=String(error.stack||error));
            """.trimIndent())
            await(scenario, "window.revocationReady === true || !!window.folderWriteFailure")
            assertEquals("null", js(scenario, "window.folderWriteFailure ?? null"))
            val resolver = instrumentation.targetContext.contentResolver
            val grant = resolver.persistedUriPermissions.single { it.uri.authority == FolderTestProvider.AUTHORITY }
            resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            js(scenario, "revokedWriter.close().then(()=>window.revokedResult='unexpected success',e=>window.revokedResult=e.name)")
            await(scenario, "!!window.revokedResult")
            assertEquals(JSONObject.quote("NotAllowedError"), js(scenario, "window.revokedResult"))
            scenario.onActivity { activity ->
                activity.startActivityForResult(Intent().setComponent(ComponentName(
                    instrumentation.context.packageName, FolderGrantActivity::class.java.name,
                )), NativeFiles.PICK_DIRECTORY)
            }
            await(scenario, "window.testGrantCount === 2")
            js(scenario, "revokedHandle.getFile().then(file=>file.text()).then(text=>window.revokedTarget=text)")
            await(scenario, "typeof window.revokedTarget === 'string'")
            assertEquals(JSONObject.quote("vorher"), js(scenario, "window.revokedTarget"))
            scenario.onActivity { activity ->
                assertTrue("Finished writers must release all staging", java.io.File(activity.cacheDir, "saf-writes").listFiles().orEmpty().isEmpty())
            }
        }
    }
}
