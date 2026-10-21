package com.stepbystep.salsa

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun waitText(value:String)=device.wait(Until.findObject(By.text(value)),30000) ?: error("Native control missing: $value")
    @Test fun nativeEditorProtectsUnsavedChanges() {
        ActivityScenario.launch(NativeActivity::class.java).use {
            waitText("Meine Figuren");waitText("＋ Neue Figur").click();waitText("Figur bearbeiten")
            val fields=device.findObjects(By.clazz("android.widget.EditText"));assertTrue(fields.isNotEmpty());fields[0].text="Ungespeicherter Entwurf"
            device.pressBack() // closes the keyboard, if open
            waitText("‹ Zurück").click();waitText("Änderungen verwerfen?");waitText("Weiter bearbeiten").click();waitText("Figur bearbeiten")
            waitText("‹ Zurück").click();waitText("Verwerfen").click();waitText("Meine Figuren")
        }
    }
    @Test fun sharedVideosRequireIndividualConfirmationAndPlayInNativePlayer() {
        val directory=File(context.cacheDir,"exports").apply{mkdirs()}
        val uris=ArrayList<Uri>()
        for(i in 1..2){val file=File(directory,"native-test-$i.mp4");instrumentation.context.assets.open("video.mp4").use{input->file.outputStream().use{input.copyTo(it)}};uris.add(FileProvider.getUriForFile(context,"${context.packageName}.files",file))}
        val intent=Intent(context,NativeActivity::class.java).apply{action=Intent.ACTION_SEND_MULTIPLE;type="video/mp4";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        ActivityScenario.launch<NativeActivity>(intent).use {
            for(i in 1..2){val accept=waitText("Video übernehmen");assertTrue(accept.wait(Until.enabled(true),30000));accept.click();device.waitForIdle()}
            waitText("Figur bearbeiten");val fields=device.findObjects(By.clazz("android.widget.EditText"));fields[0].text="Native Testfigur"
            device.pressBack();UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Figur speichern");waitText("Figur speichern").click();waitText("Meine Figuren")
            waitText("Native Testfigur").click();waitText("▶ / Ⅱ").click();waitText("Fit / Crop").click();waitText("Loop aus").click();waitText("Loop an")
            waitText("↻").click();device.waitForIdle();assertNotNull(waitText("Fit / Crop"));device.pressHome();device.pressRecentApps();device.pressBack()
        }
    }
}
