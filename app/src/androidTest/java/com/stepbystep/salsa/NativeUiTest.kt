package com.stepbystep.salsa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun waitText(value: String): androidx.test.uiautomator.UiObject2 {
        val matcher =
            By.text(
                Pattern.compile(
                    Pattern.quote(value),
                    Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
                )
            )
        val deadline = android.os.SystemClock.uptimeMillis() + 30000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            // AOSP Quickstep can show an unrelated ANR during headless emulator boot.
            // Dismiss only the launcher dialog; an ANR from this app must fail the test.
            if (device.hasObject(By.text("Quickstep isn't responding"))) {
                device.findObject(By.text("Close app"))?.click()
                device.waitForIdle()
            }
            device.wait(Until.findObject(matcher), 500)?.let {
                return it
            }
        }
        val hierarchy = ByteArrayOutputStream()
        device.dumpWindowHierarchy(hierarchy)
        error("Native control missing: $value; UI: ${hierarchy.toString("UTF-8").take(12000)}")
    }

    @Test
    fun nativeEditorProtectsUnsavedChanges() {
        ActivityScenario.launch(NativeActivity::class.java).use { scenario ->
            waitText("Meine Figuren")
            waitText("＋ Neue Figur").click()
            waitText("Figur bearbeiten")
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            assertTrue(fields.isNotEmpty())
            fields[0].text = "Ungespeicherter Entwurf"
            scenario.onActivity { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            waitText("‹ Zurück").click()
            waitText("Änderungen verwerfen?")
            waitText("Weiter bearbeiten").click()
            waitText("Figur bearbeiten")
            waitText("‹ Zurück").click()
            waitText("Verwerfen").click()
            waitText("Meine Figuren")
        }
    }

    @Test
    fun sharedVideosRequireIndividualConfirmationAndPlayInNativePlayer() {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        for (i in 1..2) {
            val file = File(directory, "native-test-$i.mp4")
            instrumentation.context.assets.open("video.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        }
        val intent =
            Intent(context, NativeActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ActivityScenario.launch<NativeActivity>(intent).use { scenario ->
            for (i in 1..2) {
                val accept = waitText("Video übernehmen")
                assertTrue(accept.wait(Until.enabled(true), 30000))
                accept.click()
                device.waitForIdle()
            }
            waitText("Figur bearbeiten")
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            fields[0].text = "Native Testfigur"
            scenario.onActivity { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Figur speichern")
            waitText("Figur speichern").click()
            waitText("Meine Figuren")
            waitText("Native Testfigur").click()
            waitText("▶ / Ⅱ").click()
            waitText("Fit / Crop").click()
            waitText("Loop aus").click()
            waitText("Loop an")
            waitText("↻").click()
            device.waitForIdle()
            assertNotNull(waitText("Fit / Crop"))
        }
    }
}
