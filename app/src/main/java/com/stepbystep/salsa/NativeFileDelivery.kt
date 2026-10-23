package com.stepbystep.salsa

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** Android adapter for the complete native file handoff, shared by both activity paths. */
internal class NativeFileDelivery(private val activity: Activity) : AutoCloseable {
    private val session = FileDeliverySession(File(activity.cacheDir, "exports"))
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var pendingPicker: String? = null
    private var choiceDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null

    @JavascriptInterface
    fun start(name: String): Boolean = session.begin(name)

    @JavascriptInterface
    fun chunk(data: String): Boolean {
        if (data.length > 349528) { session.abortReceiving(); return false }
        return try { session.append(Base64.decode(data, Base64.DEFAULT)) }
        catch (_: Exception) { session.abortReceiving(); false }
    }

    @JavascriptInterface
    fun finish(mime: String): Boolean {
        val prepared = session.prepare(mime) ?: return false
        activity.runOnUiThread { if (!closed) showChoice(prepared.id) }
        return true
    }

    @JavascriptInterface
    fun abort() = session.abortReceiving()

    private fun showChoice(id: String, message: String? = null) {
        val prepared = session.prepared()?.takeIf { it.id == id } ?: return
        if (closed) return
        choiceDialog?.dismiss()
        val builder = AlertDialog.Builder(activity)
        if (message == null) builder.setTitle("Export bereit")
        else {
            // A custom title keeps the two choice items visible alongside retry feedback.
            val title = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(12))
                addView(TextView(activity).apply { text = "Export bereit"; textSize = 20f })
                addView(TextView(activity).apply { text = message; textSize = 14f; setPadding(0, dp(8), 0, 0) })
            }
            builder.setCustomTitle(title)
        }
        val dialog = builder
            .setItems(arrayOf("Auf Gerät speichern", "Mit anderer App teilen")) { _, choice ->
                if (choice == 0) chooseDestination(prepared) else share(prepared)
            }
            .setNegativeButton("Abbrechen") { _, _ -> session.discard(id) }
            .setOnCancelListener { session.discard(id) }
            .create()
        choiceDialog = dialog
        dialog.setOnDismissListener { if (choiceDialog === dialog) choiceDialog = null }
        dialog.show()
    }

    private fun chooseDestination(prepared: FileDeliverySession.Prepared) {
        if (closed || !session.requestSave(prepared.id)) return
        pendingPicker = prepared.id
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = prepared.mime
                putExtra(Intent.EXTRA_TITLE, prepared.name)
            }, SAVE_REQUEST)
        } catch (_: Exception) {
            pendingPicker = null
            if (session.cancelPicker(prepared.id)) showChoice(prepared.id, "Speichern nicht verfügbar. Das Archiv bleibt für einen neuen Versuch bereit.")
        }
    }

    fun onActivityResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code != SAVE_REQUEST) return false
        val id = pendingPicker ?: return true
        pendingPicker = null
        if (closed) return true
        val target = data?.data
        if (result != Activity.RESULT_OK || target == null) {
            if (session.cancelPicker(id)) showChoice(id, "Speichern abgebrochen. Das Archiv bleibt bereit.")
            return true
        }
        val status = TextView(activity).apply { text = "Datei wird gespeichert …"; textSize = 16f }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) })
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        progressDialog = AlertDialog.Builder(activity).setTitle("Datei speichern").setView(content).setCancelable(false).show()
        worker.execute {
            val outcome = session.save(id, {
                activity.contentResolver.openOutputStream(target, "wt") ?: error("Zieldatei nicht schreibbar.")
            }) { percent ->
                activity.runOnUiThread { if (!closed) status.text = "Datei wird gespeichert … $percent %" }
            }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                progressDialog?.dismiss()
                progressDialog = null
                when (outcome) {
                    FileDeliverySession.Result.Saved -> {
                        session.releaseSaved(id)
                        Toast.makeText(activity, "Export gespeichert", Toast.LENGTH_LONG).show()
                    }
                    is FileDeliverySession.Result.Failed -> showChoice(id, "Speichern fehlgeschlagen. Das Archiv bleibt für einen neuen Versuch bereit.")
                    FileDeliverySession.Result.Cancelled -> Unit
                }
            }
        }
        return true
    }

    private fun share(prepared: FileDeliverySession.Prepared) {
        if (closed) return
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", prepared.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = prepared.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, "Export", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Datei teilen"))
            check(session.handedOff(prepared.id))
            Toast.makeText(activity, "Datei an Android zum Teilen übergeben", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            showChoice(prepared.id, "Teilen fehlgeschlagen. Das Archiv bleibt für einen neuen Versuch bereit.")
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    override fun close() {
        closed = true
        pendingPicker = null
        choiceDialog?.dismiss()
        progressDialog?.dismiss()
        choiceDialog = null
        progressDialog = null
        session.close()
        worker.shutdownNow()
    }

    companion object { const val SAVE_REQUEST = 735 }
}
