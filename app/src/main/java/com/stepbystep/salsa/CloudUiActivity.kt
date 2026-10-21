package com.stepbystep.salsa

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** The bundled SalsaAppCloud UI, with Android storage, sharing and playback integrations. */
class CloudUiActivity : WebAppActivity() {
    private var ready = false
    private var pending: Intent? = null

    override fun configureNativeBridge() {
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun ready() = runOnUiThread {
                    ready = true
                    receivePending()
                }

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

    override fun onCreate(state: Bundle?) {
        pending = intent
        super.onCreate(state)
    }

    override fun onNewIntent(intent: Intent) {
        // Let the shared UI protect open editors and running imports before accepting another
        // share.
        web.evaluateJavascript("window.AndroidAppUI?.canReceive() === true") { result ->
            if (result != "true") {
                Toast.makeText(
                        this,
                        "Bitte zuerst den aktuellen Vorgang abschließen und erneut teilen.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            } else {
                pending = intent
                super.onNewIntent(intent)
                receivePending()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun receivePending() {
        if (!ready) return
        val incoming = pending ?: return
        pending = null
        if (
            incoming.action == Intent.ACTION_VIEW ||
                (incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain")
        ) {
            val raw = incoming.dataString ?: incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link =
                Regex("""https://[^\s<>"']+""").find(raw)?.value?.trimEnd('.', ',', ')') ?: return
            val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return
            if (
                uri.scheme != "https" ||
                    uri.host != "rodriges17.github.io" ||
                    !uri.path.orEmpty().startsWith("/SalsaAppCloud/")
            )
                return
            if (uri.getQueryParameter("token_hash") != null)
                return // Base activity handles authentication.
            web.evaluateJavascript(
                "window.AndroidAppUI.openLibrary(${JSONObject.quote(uri.getQueryParameter("library"))})",
                null,
            )
            return
        }
        val uris =
            when (incoming.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    incoming
                        .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        ?.toList()
                        .orEmpty()
                else -> emptyList()
            }
        if (uris.isEmpty()) return
        if (uris.size > 100) {
            Toast.makeText(
                    this,
                    "Bitte höchstens 100 Dateien gleichzeitig teilen.",
                    Toast.LENGTH_LONG,
                )
                .show()
            return
        }
        val files = JSONArray()
        try {
            for (uri in uris) {
                require(uri.scheme == "content") {
                    "Bitte Dateien aus der Android-Dateiauswahl teilen."
                }
                var name = uri.lastPathSegment ?: "import"
                contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) name = it.getString(0) }
                files.put(
                    JSONObject()
                        .put("url", nativeFiles.register(uri))
                        .put("name", name)
                        .put(
                            "type",
                            contentResolver.getType(uri)
                                ?: incoming.type
                                ?: "application/octet-stream",
                        )
                )
            }
            web.evaluateJavascript(
                "window.AndroidAppUI.receive($files).catch(e=>alert(e.message))",
                null,
            )
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Datei nicht lesbar", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (closeFullscreenIfOpen()) return
        if (ready) web.evaluateJavascript("window.AndroidAppUI.back()", null)
        else super.onBackPressed()
    }
}
