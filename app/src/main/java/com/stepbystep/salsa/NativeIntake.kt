package com.stepbystep.salsa

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Owns an incoming action from reservation to dispatch, cancellation or activity destruction. */
@Suppress("DEPRECATION")
class NativeIntake(
    private val activity: Activity,
    private val web: WebView,
    private val files: NativeFiles,
    private val login: (String) -> Unit,
) : AutoCloseable {
    private sealed interface Action {
        data class Link(val target: IncomingLink) : Action
        data class Files(val uris: List<Uri>, val type: String?) : Action
    }
    private data class Document(val uri: Uri, val name: String, val type: String)
    private val session = IncomingSession<Action>()
    private val worker = Executors.newCachedThreadPool()
    private var task: Future<*>? = null
    private var signal: CancellationSignal? = null
    private var progress: ProgressDialog? = null
    private var registered = emptyList<String>()
    private var currentId: Long? = null
    private var closed = false
    private var document: String? = null
    private val retiredDocuments = mutableSetOf<String>()
    private var loginTicket: Long? = null
    var ready = false
        private set

    @JavascriptInterface
    fun ready(page: String) = activity.runOnUiThread {
        if (!closed && page.isNotBlank()) {
            // Old-document callbacks cannot unlock the login reload or replay an action.
            if (page in retiredDocuments) return@runOnUiThread
            document?.takeIf { it != page }?.let(retiredDocuments::add)
            document = page
            loginTicket?.let(::release)
            loginTicket = null
            ready = true
            session.ready()?.let(::dispatch)
        }
    }

    /** Synchronous bridge seam: cancellation and UI hand-off have one atomic winner. */
    @JavascriptInterface
    fun claim(id: String): Boolean {
        val ticket = id.toLongOrNull() ?: return false
        if (!session.claim(ticket)) return false
        activity.runOnUiThread {
            if (session.active(ticket)) {
                progress?.setCancelable(false)
                progress?.getButton(ProgressDialog.BUTTON_NEGATIVE)?.isEnabled = false
            }
        }
        return true
    }

    @JavascriptInterface
    fun completed(id: String, error: String) = activity.runOnUiThread {
        val ticket = id.toLongOrNull() ?: return@runOnUiThread
        if (!session.active(ticket)) return@runOnUiThread
        release(ticket)
        if (error.isNotBlank()) notice(error)
    }

    fun offer(intent: Intent?) {
        if (closed || intent == null) return
        val action = try { classify(intent) } catch (error: Exception) {
            notice(error.message ?: "Übergabe nicht lesbar")
            return
        } ?: return
        val ticket = session.offer(action)
        if (ticket == null) { notice("Bitte zuerst den aktuellen Vorgang abschließen und erneut teilen."); return }
        currentId = ticket.id
        if (ticket.dispatchNow) dispatch(ticket)
    }

    private fun classify(intent: Intent): Action? {
        if (intent.action == Intent.ACTION_VIEW || (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")) {
            val raw = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            return IncomingLink.classify(raw)?.let { Action.Link(it) }
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return null
        require(uris.size <= 100) { "Bitte höchstens 100 Dateien gleichzeitig teilen." }
        require(uris.all { it.scheme == "content" }) { "Bitte Dateien aus der Android-Dateiauswahl teilen." }
        return Action.Files(uris, intent.type)
    }

    private fun dispatch(ticket: IncomingSession.Ticket<Action>) {
        val id = ticket.id
        web.evaluateJavascript("window.AndroidAppUI?.beginReceive(${JSONObject.quote(id.toString())}) === true") { allowed ->
            if (!session.active(id)) return@evaluateJavascript
            if (allowed != "true") {
                release(id)
                notice("Bitte zuerst den aktuellen Vorgang abschließen und erneut teilen.")
                return@evaluateJavascript
            }
            when (val action = ticket.action) {
                is Action.Files -> prepare(id, action)
                is Action.Link -> when (val target = action.target) {
                    is IncomingLink.Login -> {
                        ready = false
                        session.notReady()
                        loginTicket = id
                        document?.let(retiredDocuments::add)
                        login(target.token)
                    }
                    is IncomingLink.Library -> {
                        web.evaluateJavascript("Promise.resolve().then(()=>window.AndroidAppUI.openLibrary(${JSONObject.quote(target.id)})).then(()=>AndroidIntake.completed('$id',''),e=>AndroidIntake.completed('$id',String(e.message||e))).finally(()=>window.AndroidAppUI.cancelReceive('$id'))", null)
                    }
                }
            }
        }
    }

    private fun showProgress(id: Long, message: String) {
        progress = ProgressDialog(activity).apply {
            setTitle("Übergabe vorbereiten")
            setMessage(message)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Abbrechen") { _, _ -> cancel(id) }
            setOnCancelListener { cancel(id) }
            show()
        }
    }

    private fun prepare(id: Long, action: Action.Files) {
        showProgress(id, "Dateien werden vorbereitet …")
        val cancellation = CancellationSignal()
        signal = cancellation
        task = worker.submit {
            try {
                val documents = action.uris.mapIndexed { index, uri ->
                    cancellation.throwIfCanceled()
                    activity.runOnUiThread { if (session.active(id)) progress?.setMessage("Datei ${index + 1} von ${action.uris.size} wird vorbereitet …") }
                    var name = uri.lastPathSegment ?: "import"
                    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use {
                        if (it.moveToFirst()) name = it.getString(0) ?: name
                    }
                    val type = activity.contentResolver.getType(uri) ?: action.type ?: "application/octet-stream"
                    cancellation.throwIfCanceled()
                    Document(uri, name, type)
                }
                activity.runOnUiThread {
                    if (!session.active(id)) return@runOnUiThread
                    try {
                        val items = JSONArray()
                        documents.forEach { document ->
                            val url = files.register(document.uri)
                            registered = registered + url
                            items.put(JSONObject().put("url", url).put("name", document.name).put("type", document.type))
                        }
                        progress?.setMessage("Dateien werden eingelesen …")
                        web.evaluateJavascript("window.AndroidAppUI.receive($items,'$id').then(()=>AndroidIntake.completed('$id',''),e=>AndroidIntake.completed('$id',String(e.message||e)))", null)
                    } catch (error: Exception) { fail(id, error) }
                }
            } catch (error: Exception) { activity.runOnUiThread { fail(id, error) } }
        }
    }

    private fun fail(id: Long, error: Exception) {
        if (!session.active(id)) return
        web.evaluateJavascript("window.AndroidAppUI?.cancelReceive('$id')", null)
        release(id)
        notice(error.message ?: "Datei nicht lesbar")
    }

    private fun cancel(id: Long) {
        if (!session.cancel(id)) return
        signal?.cancel()
        task?.cancel(true)
        web.evaluateJavascript("window.AndroidAppUI?.cancelReceive('$id')", null)
        cleanup()
    }

    private fun release(id: Long) {
        if (!session.finish(id)) return
        cleanup()
    }

    private fun cleanup() {
        registered.forEach(files::unregister)
        registered = emptyList()
        progress?.dismiss()
        progress = null
        signal = null
        task = null
        currentId = null
    }

    private fun notice(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    override fun close() {
        if (closed) return
        closed = true
        session.close()
        signal?.cancel()
        task?.cancel(true)
        cleanup()
        worker.shutdownNow()
    }
}
