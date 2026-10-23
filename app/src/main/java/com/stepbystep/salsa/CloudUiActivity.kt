package com.stepbystep.salsa

import android.app.AlertDialog
import android.content.Intent
import android.webkit.JavascriptInterface

/** The bundled SalsaAppCloud UI, with Android storage, sharing and playback integrations. */
class CloudUiActivity : WebAppActivity() {
    private lateinit var intake: NativeIntake

    override fun configureNativeBridge() {
        intake = NativeIntake(this, web, nativeFiles) { token -> openSharedLogin(token) }
        web.addJavascriptInterface(intake, "AndroidIntake")
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun ready(document: String) = intake.ready(document)

                @JavascriptInterface
                fun home() = runOnUiThread {
                    AlertDialog.Builder(this@CloudUiActivity)
                        .setMessage("Step by Step schließen?")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Schließen") { _, _ -> finish() }
                        .show()
                }
            },
            "AndroidShell",
        )
    }

    override fun receiveInitialIntent(incoming: Intent?) = intake.offer(incoming)
    override fun receiveNewIntent(incoming: Intent) = intake.offer(incoming)

    override fun onDestroy() {
        if (::intake.isInitialized) intake.close()
        super.onDestroy()
    }

    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (closeFullscreenIfOpen()) return
        if (intake.ready) web.evaluateJavascript("window.AndroidAppUI.back()", null)
        else super.onBackPressed()
    }
}
